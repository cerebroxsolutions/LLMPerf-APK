package com.llmperf.llmperf_apk.data.location

/** Reverse-geocoded GPS fix, same shape as Exfo-GenAI's utils.GpsCityState. */
data class GpsCityState(
    val latitude: Double?,
    val longitude: Double?,
    val city: String?,
    val state: String?,
    val country: String?
)
