package com.llmperf.llmperf_apk.ws

import android.util.Log
import com.llmperf.llmperf_apk.data.remote.ApiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

private const val TAG = "WebSocketManager"
private const val HEARTBEAT_INTERVAL_MS = 10_000L

sealed class WsConnectionState {
    object Idle : WsConnectionState()
    object Connecting : WsConnectionState()
    data class Connected(val serverResponse: String) : WsConnectionState()
    data class Closed(val code: Int, val reason: String) : WsConnectionState()
    data class Failure(val message: String) : WsConnectionState()
}

/** A run_test command as received over the socket — testType/triggerType/targetHost are echoed back unchanged when posting the result. */
data class RunTestCommand(
    val testId: String,
    val testType: String,
    val triggerType: String,
    // Set only for test types that need a target (e.g. icmp) — null otherwise.
    val targetHost: String?,
    // Operator-configured ping count for icmp — null means "use the default".
    val pingCount: Int?,
    // Console username that started a manual run — null for scheduled runs.
    val triggeredBy: String? = null,
    // hls_streaming config — null for other test types.
    val hls: HlsTestConfig? = null
)

/**
 * hls_streaming settings from run_test. websocket-service has already
 * validated them (http(s) URL, timeout 1–3600s, adaptive flag present);
 * [resolution] is only set when [adaptive] is true.
 */
data class HlsTestConfig(
    val url: String,
    val timeoutSec: Int,
    val adaptive: Boolean,
    val resolution: String?,
    // Fixed mode only: the variant BANDWIDTH/1000 the operator picked —
    // playback locks to the track at (or just under) this bitrate.
    val fixedBitrateKbps: Double? = null,
    // Landscape fullscreen (Exfo-GenAI's HLS behavior): switch after
    // fullscreenDelaySec, return after fullscreenDurationSec (0 = until
    // playback ends). Null delay = stay in the normal card view.
    val fullscreenDelaySec: Int? = null,
    val fullscreenDurationSec: Int = 0
)

/**
 * Opens the Android WS connection and sends the JWT as the first message,
 * matching websocket-service's first-message auth handshake (see
 * ws/handler.go on the backend) — no ?token= query param, no header. Also
 * sends a small {"type":"heartbeat"} message every [HEARTBEAT_INTERVAL_MS]
 * while connected, which websocket-service's status.Tracker uses to drive
 * the console's alive/reconnecting/offline device status.
 */
class WebSocketManager(private val okHttpClient: OkHttpClient) {

    companion object {
        /** Close code used by [disconnect] — a deliberate client-initiated close, not a drop. */
        const val NORMAL_CLOSURE_CODE = 1000
    }

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<WsConnectionState>(WsConnectionState.Idle)
    val state: StateFlow<WsConnectionState> = _state.asStateFlow()

    // extraBufferCapacity so a run_test that arrives just before a collector
    // subscribes (e.g. right after process start) isn't silently dropped.
    private val _runTestCommands = MutableSharedFlow<RunTestCommand>(extraBufferCapacity = 4)
    val runTestCommands: SharedFlow<RunTestCommand> = _runTestCommands

    fun connect(token: String) {
        Log.d(TAG, "connecting to ${ApiConfig.WEBSOCKET_URL}")
        _state.value = WsConnectionState.Connecting

        val request = Request.Builder()
            .url(ApiConfig.WEBSOCKET_URL)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "socket open, sending auth token")
                webSocket.send(token)
                startHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "message: $text")

                val type = runCatching { JSONObject(text).optString("type") }.getOrNull()
                if (type == "run_test") {
                    val json = JSONObject(text)
                    val testId = json.optString("testId")
                    val testType = json.optString("testType")
                    val triggerType = json.optString("triggerType")
                    val targetHost = json.optString("targetHost").ifBlank { null }
                    val pingCount = if (json.has("pingCount")) json.optInt("pingCount") else null
                    val triggeredBy = json.optString("triggeredBy").ifBlank { null }
                    val hls = parseHlsConfig(json)
                    Log.d(TAG, "run_test message received over socket (testId=$testId, testType=$testType, triggerType=$triggerType, triggeredBy=$triggeredBy, targetHost=$targetHost, pingCount=$pingCount, hls=$hls)")
                    _runTestCommands.tryEmit(RunTestCommand(testId, testType, triggerType, targetHost, pingCount, triggeredBy, hls))
                    return
                }

                _state.value = WsConnectionState.Connected(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "closing: code=$code reason=$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "closed: code=$code reason=$reason")
                stopHeartbeat()
                _state.value = WsConnectionState.Closed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "connection failure", t)
                stopHeartbeat()
                _state.value = WsConnectionState.Failure(t.message ?: "unknown error")
            }
        })
    }

    /** Null when run_test carries no hlsUrl (i.e. any non-HLS test). */
    private fun parseHlsConfig(json: JSONObject): HlsTestConfig? {
        val url = json.optString("hlsUrl").ifBlank { return null }
        return HlsTestConfig(
            url = url,
            timeoutSec = json.optInt("hlsTimeoutSec", 0),
            adaptive = json.optBoolean("hlsAdaptive", false),
            resolution = json.optString("hlsResolution").ifBlank { null },
            fixedBitrateKbps = if (json.has("fixedBitrateKbps")) json.optDouble("fixedBitrateKbps").takeIf { it > 0 } else null,
            fullscreenDelaySec = if (json.has("hlsFullscreenDelaySec")) json.optInt("hlsFullscreenDelaySec").takeIf { it >= 0 } else null,
            fullscreenDurationSec = json.optInt("hlsFullscreenDurationSec", 0).coerceAtLeast(0)
        )
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                Log.d(TAG, "sending heartbeat")
                webSocket.send("""{"type":"heartbeat"}""")
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun disconnect() {
        stopHeartbeat()
        webSocket?.close(NORMAL_CLOSURE_CODE, "client disconnect")
        webSocket = null
    }

    /**
     * Tells websocket-service this testId's run is done, so it can relay
     * test_complete to whichever Server Peer was notified of test_starting
     * for it (see ws.Handler.routeTestComplete on the backend). A no-op on
     * the server side for test types/targets with no attached peer.
     */
    fun sendTestComplete(testId: String) {
        Log.d(TAG, "sending test_complete for testId=$testId")
        webSocket?.send("""{"type":"test_complete","testId":"$testId"}""")
    }
}
