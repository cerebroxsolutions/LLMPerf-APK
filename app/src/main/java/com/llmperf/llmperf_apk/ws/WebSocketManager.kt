package com.llmperf.llmperf_apk.ws

import android.util.Log
import com.llmperf.llmperf_apk.data.remote.ApiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val TAG = "WebSocketManager"
private const val HEARTBEAT_INTERVAL_MS = 10_000L

sealed class WsConnectionState {
    object Idle : WsConnectionState()
    object Connecting : WsConnectionState()
    data class Connected(val serverResponse: String) : WsConnectionState()
    data class Closed(val code: Int, val reason: String) : WsConnectionState()
    data class Failure(val message: String) : WsConnectionState()
}

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
}
