package com.llmperf.llmperf_apk.data.remote
import com.llmperf.llmperf_apk.BuildConfig
/**
 * Live deployed server (18.189.166.164). ICMP tests against this server also
 * need this to match SERVER_PEER_HOST on the server-observer deploy.
 *
 * For local dev instead: physical device over WiFi, same LAN as the host
 * machine — use the host's LAN IP directly (re-check with
 * `ipconfig getifaddr en0` on the Mac if it changes, e.g. after reconnecting
 * to WiFi).
 *
 * Alternatively, over USB via `adb reverse` (no WiFi needed):
 *   adb reverse tcp:8080 tcp:8080
 *   adb reverse tcp:8081 tcp:8081
 * This forwards the device's own "localhost:PORT" to the host machine's
 * same port over the USB connection, so the device can use "localhost"
 * directly — swap the values below back to "localhost" for that mode.
 * Switching back to the emulator? Use "10.0.2.2" instead (the emulator's
 * alias for host localhost) — adb reverse doesn't apply there.
 */
object ApiConfig {

    const val API_SERVICE_BASE_URL = "http://192.168.1.3:8080/"
    const val WEBSOCKET_URL = "ws://192.168.1.3:8081/ws/android"
    const val GEOLOCATION_API_KEY = BuildConfig.GEOLOCATION_API_KEY
//    const val API_SERVICE_BASE_URL = "http://18.189.166.164:8080/"
//    const val WEBSOCKET_URL = "ws://18.189.166.164:8081/ws/android"
}
