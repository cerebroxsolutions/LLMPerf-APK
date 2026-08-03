package com.llmperf.llmperf_apk.data.local

import android.content.Context

private const val PREFS_NAME = "device_prefs"
private const val KEY_DEVICE_NAME = "device_name"
private const val KEY_DEVICE_ID = "device_id"

class DevicePrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()
}
