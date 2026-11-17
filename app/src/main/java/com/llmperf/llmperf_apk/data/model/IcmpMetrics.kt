package com.llmperf.llmperf_apk.data.model

import com.google.gson.annotations.SerializedName

data class IcmpMetrics(
    val packetsSent: Int,
    val packetsReceived: Int,
    val packetLossPct: Double,
    // Null when zero packets were received — nothing to average/rank.
    val latencyAvgMs: Double?,
    val latencyMinMs: Double?,
    val latencyMaxMs: Double?,
    val latencyP50Ms: Double?,
    val latencyP90Ms: Double?,
    val jitterMs: Double?
)

data class ServerLocation(
    val serverCity: String?,
    val serverState: String?,
    val serverCountry: String?,
    val serverLatitude: Double?,
    val serverLongitude: Double?
)

data class GeolocationResponse(
    @SerializedName("city") val city: String?,
    @SerializedName("state_prov") val stateProv: String?,
    @SerializedName("country_name") val countryName: String?,
    @SerializedName("latitude") val latitude: String?,
    @SerializedName("longitude") val longitude: String?
)
