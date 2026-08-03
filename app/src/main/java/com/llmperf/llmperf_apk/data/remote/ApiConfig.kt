package com.llmperf.llmperf_apk.data.remote

/**
 * Physical device over USB via `adb reverse` (no WiFi needed):
 *   adb reverse tcp:8080 tcp:8080
 *   adb reverse tcp:8081 tcp:8081
 * This forwards the device's own "localhost:PORT" to the host machine's
 * same port over the USB connection, so the device can use "localhost"
 * directly. Switching back to the emulator? Use "10.0.2.2" instead
 * (the emulator's alias for host localhost) — adb reverse doesn't apply there.
 */
object ApiConfig {
    const val API_SERVICE_BASE_URL = "http://localhost:8080/"
    const val WEBSOCKET_URL = "ws://localhost:8081/ws/android"
}
