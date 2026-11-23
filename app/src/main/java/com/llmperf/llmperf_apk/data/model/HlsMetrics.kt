package com.llmperf.llmperf_apk.data.model

/**
 * hls_streaming result, posted as `hlsMetrics` — field names match
 * api-service's models.HlsMetricsInput. Any field the run couldn't measure
 * is null (Gson omits it).
 */
data class HlsMetrics(
    // Config the device actually played with (echoed from run_test).
    val hlsUrl: String,
    val hlsAdaptive: Boolean,
    val hlsResolution: String?,
    // Bitrate of the variant playback was locked to — non-adaptive runs only.
    val fixedBitrateKbps: Double?,

    // Playback quality. Variant bitrates come from the playlist's BANDWIDTH.
    val maxBitrateKbps: Double?,
    val minBitrateKbps: Double?,
    val resolutionUpCount: Int,
    val resolutionDownCount: Int,
    val startupDelayMs: Double?,
    val totalDurationMs: Double,
    val rebufferCount: Int,
    val rebufferTimeMs: Double,
    val droppedFrames: Int,
    val fpsAvg: Double?,
    val fpsPeak: Double?,
    // Segment download throughput (bytes × 8 / load time).
    val avgSegmentBitrateKbps: Double?,
    val peakSegmentBitrateKbps: Double?,

    // Transport round-trip probe to the stream host, run alongside playback.
    // transportPingStartAt is ISO-8601 with offset, e.g. 2026-05-19T22:01:34.000+05:30.
    val transportPingStartAt: String? = null,
    val transportPingCount: Int? = null,
    val transportRttAvgMs: Double? = null,
    val transportRttMinMs: Double? = null,
    val transportRttMaxMs: Double? = null,
    val transportRttP50Ms: Double? = null,
    val transportRttP90Ms: Double? = null,
    val transportRttJitterMs: Double? = null
)
