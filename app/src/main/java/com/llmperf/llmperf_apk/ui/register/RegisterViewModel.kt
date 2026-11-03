package com.llmperf.llmperf_apk.ui.register

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llmperf.llmperf_apk.data.metrics.IcmpPingTest
import com.llmperf.llmperf_apk.data.metrics.RanMetricsCollector
import com.llmperf.llmperf_apk.data.model.IcmpMetrics
import com.llmperf.llmperf_apk.data.model.TokenResponse
import com.llmperf.llmperf_apk.data.repository.DeviceRepository
import com.llmperf.llmperf_apk.ws.RunTestCommand
import com.llmperf.llmperf_apk.ws.WebSocketManager
import com.llmperf.llmperf_apk.ws.WsConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Fixed retry interval for reconnecting a dropped WebSocket. */
private const val RECONNECT_DELAY_MS = 10_000L

/** How long a run_test command waits for a fresh RAN metrics reading before posting a failure. */
private const val RAN_METRICS_WAIT_TIMEOUT_MS = 30_000L

/** Token-fetch retry backoff: 10s, 20s, 30s, ... capped at 10 minutes. */
private const val TOKEN_RETRY_STEP_MS = 10_000L
private const val TOKEN_RETRY_MAX_DELAY_MS = 600_000L

private const val TAG = "RegisterViewModel"

class RegisterViewModel(
    private val repository: DeviceRepository,
    private val webSocketManager: WebSocketManager,
    private val ranMetricsCollector: RanMetricsCollector,
    private val icmpPingTest: IcmpPingTest
) : ViewModel() {

    private val _state = MutableStateFlow<RegisterUiState>(RegisterUiState.Idle)
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    val wsState: StateFlow<WsConnectionState> = webSocketManager.state

    private val _ranMetricsState = MutableStateFlow<RanMetricsUiState>(RanMetricsUiState.Idle)
    val ranMetricsState: StateFlow<RanMetricsUiState> = _ranMetricsState.asStateFlow()

    private val _testExecutionState = MutableStateFlow<TestExecutionUiState>(TestExecutionUiState.Idle)
    val testExecutionState: StateFlow<TestExecutionUiState> = _testExecutionState.asStateFlow()

    private var currentDeviceId: String? = null
    private var currentName: String? = null

    init {
        // A dropped connection (server restart, network blip, expired-token
        // rejection) surfaces here as a non-normal Closed or a Failure. Not
        // distinguishing "expired token" from other drop causes is
        // deliberate — either way the fix is the same: fetch a fresh token
        // (issued fresh every time, never cached) and reconnect.
        viewModelScope.launch {
            webSocketManager.state.collect { wsState ->
                val droppedUnexpectedly = (wsState is WsConnectionState.Closed &&
                    wsState.code != WebSocketManager.NORMAL_CLOSURE_CODE) ||
                    wsState is WsConnectionState.Failure

                val deviceId = currentDeviceId
                val name = currentName
                if (droppedUnexpectedly && deviceId != null && name != null) {
                    delay(RECONNECT_DELAY_MS)
                    connectWithToken(deviceId, name)
                }
            }
        }

        viewModelScope.launch {
            webSocketManager.runTestCommands.collect { command -> handleRunTest(command) }
        }
    }

    /**
     * Dispatches one test by testType. Spawned per command so a slow test
     * doesn't block the next run_test from being received.
     */
    private fun handleRunTest(command: RunTestCommand) {
        val deviceId = currentDeviceId ?: return
        val (testId, testType, triggerType, targetHost, pingCount) = command
        Log.d(TAG, "test is received (testId=$testId, testType=$testType, triggerType=$triggerType, targetHost=$targetHost, pingCount=$pingCount)")
        Log.d(TAG, "starting test (testId=$testId)")
        _testExecutionState.value = TestExecutionUiState.Received(testType)

        viewModelScope.launch {
            when (testType) {
                "icmp" -> handleIcmpTest(testId, deviceId, triggerType, targetHost, pingCount)
                else -> handleDeviceInfoRanTest(testId, deviceId, testType, triggerType)
            }
        }
    }

    /**
     * Waits for the next Success reading already flowing in from
     * [refreshRanMetrics]'s periodic polling (or the current one, if it's
     * already fresh) for up to [RAN_METRICS_WAIT_TIMEOUT_MS], then posts a
     * success or failure result.
     */
    private suspend fun handleDeviceInfoRanTest(testId: String, deviceId: String, testType: String, triggerType: String) {
        _testExecutionState.value = TestExecutionUiState.Running(testType)
        val metrics = withTimeoutOrNull(RAN_METRICS_WAIT_TIMEOUT_MS) {
            ranMetricsState.filterIsInstance<RanMetricsUiState.Success>().first().metrics
        }
        val status = if (metrics != null) "success" else "failure"

        Log.d(TAG, "sending the results for testId=$testId (status=$status)")
        repository.postTestResult(
            testId = testId,
            deviceId = deviceId,
            status = status,
            testType = testType,
            triggerType = triggerType,
            ranMetrics = metrics
        ).onSuccess {
            Log.d(TAG, "result sent for testId=$testId")
            _testExecutionState.value = TestExecutionUiState.Sent(testType, status)
        }.onFailure {
            Log.e(TAG, "failed to post test result for $testId", it)
            _testExecutionState.value = TestExecutionUiState.Failed(testType, it.message ?: "failed to reach server")
        }
    }

    /** Runs a burst of ICMP pings against targetHost and posts the resulting stats. */
    private suspend fun handleIcmpTest(testId: String, deviceId: String, triggerType: String, targetHost: String?, pingCount: Int?) {
        if (targetHost.isNullOrBlank()) {
            Log.e(TAG, "icmp run_test missing targetHost, discarding testId=$testId")
            _testExecutionState.value = TestExecutionUiState.Failed("icmp", "missing target host")
            return
        }

        _testExecutionState.value = TestExecutionUiState.Running("icmp")
        Log.d(TAG, "running icmp ping test for testId=$testId against $targetHost (pingCount=$pingCount)")
        var metrics: IcmpMetrics? = null
        var errorMessage: String? = null
        try {
            metrics = if (pingCount != null) icmpPingTest.run(targetHost, pingCount) else icmpPingTest.run(targetHost)
            if (metrics.packetsReceived == 0) {
                errorMessage = "0/${metrics.packetsSent} packets received (100% packet loss) — target unreachable or ICMP blocked"
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "ping failed to run"
        }
        val status = if (errorMessage == null) "success" else "failure"
        if (errorMessage != null) Log.e(TAG, "testId=$testId marked failure: $errorMessage")

        // Sent right as the ping burst itself ends (not after RAN metrics
        // collection below, which is incidental enrichment) so a Server
        // Peer attached to targetHost stops observing as close as possible
        // to when the actual test traffic stopped.
        webSocketManager.sendTestComplete(testId)

        // Device info & RAN metrics are collected fresh right after the ping
        // test finishes (not the icmp test's success/failure signal — just
        // enrichment attached to the row) so the results table shows what
        // network the device was actually on while it ran the ping burst.
        val ranMetrics = try {
            ranMetricsCollector.collect()
        } catch (e: Exception) {
            Log.e(TAG, "failed to collect device info & RAN metrics after icmp test for testId=$testId", e)
            null
        }

        Log.d(TAG, "sending the results for testId=$testId (status=$status)")
        repository.postTestResult(
            testId = testId,
            deviceId = deviceId,
            status = status,
            testType = "icmp",
            triggerType = triggerType,
            targetHost = targetHost,
            ranMetrics = ranMetrics,
            errorMessage = errorMessage,
            icmpMetrics = metrics
        ).onSuccess {
            Log.d(TAG, "result sent for testId=$testId")
            _testExecutionState.value = TestExecutionUiState.Sent("icmp", status)
        }.onFailure {
            Log.e(TAG, "failed to post test result for $testId", it)
            _testExecutionState.value = TestExecutionUiState.Failed("icmp", it.message ?: "failed to reach server")
        }
    }

    /**
     * First-run path: called from the Register button's click listener,
     * never on text-change. Registers a brand new device, then shares the
     * token+connect steps with [connectExisting].
     */
    fun register(name: String, model: String) {
        if (name.isBlank()) {
            _state.value = RegisterUiState.Error("Name is required")
            return
        }

        viewModelScope.launch {
            _state.value = RegisterUiState.Registering
            val device = repository.registerDevice(name, model).getOrElse {
                _state.value = RegisterUiState.Error(it.message ?: "Registration failed")
                return@launch
            }
            connectWithToken(device.id, name)
        }
    }

    /**
     * Returning-launch path: device was already registered in a previous
     * session (id + name saved in DevicePrefs) — skips POST /android-devices
     * entirely so re-opening the app doesn't create a duplicate device row,
     * and goes straight to token fetch + WS connect.
     */
    fun connectExisting(deviceId: String, name: String) {
        viewModelScope.launch { connectWithToken(deviceId, name) }
    }

    private suspend fun connectWithToken(deviceId: String, name: String) {
        currentDeviceId = deviceId
        currentName = name

        _state.value = RegisterUiState.FetchingToken
        val token = fetchTokenWithRetry(deviceId) ?: return

        Log.d(TAG, "connecting WebSocket as deviceId=$deviceId")
        webSocketManager.connect(token.token)
        _state.value = RegisterUiState.Connected(deviceId, name)
    }

    /**
     * Keeps retrying token issuance with increasing backoff (10s, 20s, 30s,
     * ... capped at 10 minutes) instead of giving up after one failure —
     * api-service being briefly unreachable shouldn't strand a device in a
     * permanent error state. Bails out (returns null) only if a newer
     * connectWithToken call has since superseded this one.
     */
    private suspend fun fetchTokenWithRetry(deviceId: String): TokenResponse? {
        var attempt = 0
        while (currentDeviceId == deviceId) {
            val result = repository.issueToken(deviceId)
            result.onSuccess { return it }
            attempt++
            val delayMs = (attempt * TOKEN_RETRY_STEP_MS).coerceAtMost(TOKEN_RETRY_MAX_DELAY_MS)
            Log.w(
                TAG,
                "token fetch failed for $deviceId (attempt $attempt), retrying in ${delayMs / 1000}s",
                result.exceptionOrNull()
            )
            delay(delayMs)
        }
        return null
    }

    /** Called after permissions are confirmed granted; no-ops otherwise. */
    fun refreshRanMetrics() {
        if (!ranMetricsCollector.hasRequiredPermissions()) {
            _ranMetricsState.value = RanMetricsUiState.PermissionRequired
            return
        }

        viewModelScope.launch {
            // Only show "Loading" before the first successful read — once metrics are
            // showing, periodic refreshes should update the value in place rather than
            // flashing back to a loading/blank state every 5s.
            if (_ranMetricsState.value !is RanMetricsUiState.Success) {
                _ranMetricsState.value = RanMetricsUiState.Loading
            }

            val metrics = try {
                ranMetricsCollector.collect()
            } catch (e: Exception) {
                _ranMetricsState.value = RanMetricsUiState.Error(e.message ?: "Failed to read RAN metrics")
                return@launch
            }

            _ranMetricsState.value = if (metrics != null) {
                RanMetricsUiState.Success(metrics)
            } else {
                RanMetricsUiState.PermissionRequired
            }
        }
    }

    override fun onCleared() {
        webSocketManager.disconnect()
    }

    class Factory(
        private val repository: DeviceRepository,
        private val webSocketManager: WebSocketManager,
        private val ranMetricsCollector: RanMetricsCollector,
        private val icmpPingTest: IcmpPingTest
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return RegisterViewModel(repository, webSocketManager, ranMetricsCollector, icmpPingTest) as T
        }
    }
}
