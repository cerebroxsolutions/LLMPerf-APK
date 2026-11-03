package com.llmperf.llmperf_apk.data.model

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
