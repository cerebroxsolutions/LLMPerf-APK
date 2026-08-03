package com.llmperf.llmperf_apk.ui.register

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llmperf.llmperf_apk.data.repository.DeviceRepository
import com.llmperf.llmperf_apk.ws.WebSocketManager
import com.llmperf.llmperf_apk.ws.WsConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val RECONNECT_DELAY_MS = 3000L

class RegisterViewModel(
    private val repository: DeviceRepository,
    private val webSocketManager: WebSocketManager
) : ViewModel() {

    private val _state = MutableStateFlow<RegisterUiState>(RegisterUiState.Idle)
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    val wsState: StateFlow<WsConnectionState> = webSocketManager.state

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
        val token = repository.issueToken(deviceId).getOrElse {
            _state.value = RegisterUiState.Error(it.message ?: "Token fetch failed")
            return
        }

        webSocketManager.connect(token.token)
        _state.value = RegisterUiState.Connected(deviceId, name)
    }

    override fun onCleared() {
        webSocketManager.disconnect()
    }

    class Factory(
        private val repository: DeviceRepository,
        private val webSocketManager: WebSocketManager
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return RegisterViewModel(repository, webSocketManager) as T
        }
    }
}
