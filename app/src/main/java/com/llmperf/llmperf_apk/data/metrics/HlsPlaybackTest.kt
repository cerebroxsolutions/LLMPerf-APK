package com.llmperf.llmperf_apk.data.metrics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.llmperf.llmperf_apk.data.model.HlsMetrics
import com.llmperf.llmperf_apk.ws.HlsTestConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TAG = "HlsPlaybackTest"

/** How often FPS is sampled and the stop conditions are checked. */
private const val SAMPLE_INTERVAL_MS = 1_000L

/** Max video size for each resolution the console offers (adaptive mode cap). */
private val RESOLUTION_SIZES = mapOf(
    "1080p" to (1920 to 1080),
    "720p" to (1280 to 720),
    "480p" to (854 to 480),
    "360p" to (640 to 360),
    "240p" to (426 to 240),
)

/**
 * Plays an HLS stream with Media3 ExoPlayer for up to the configured
 * timeout (or until the stream ends / errors) and measures playback quality.
 *
 * ExoPlayer must be driven from the main thread, so [run] switches to
 * Dispatchers.Main. The live player is exposed via [player] so the UI can
 * attach a TextureView — without an output surface ExoPlayer skips video
 * frames instead of rendering them, which would make FPS and dropped-frame
 * counts meaningless.
 *
 * Adaptive mode lets ABR switch freely (capped at a resolution if one is
 * sent). Fixed mode locks playback to the operator-picked variant
 * (fixedBitrateKbps), or the highest variant if none was sent, and reports
 * the locked track's bitrate as fixedBitrateKbps.
 */
@OptIn(UnstableApi::class)
class HlsPlaybackTest(private val context: Context) {

    data class Outcome(val metrics: HlsMetrics, val errorMessage: String?)

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    suspend fun run(config: HlsTestConfig): Outcome = withContext(Dispatchers.Main) {
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters().apply {
                if (config.adaptive) {
                    RESOLUTION_SIZES[config.resolution]?.let { (w, h) -> setMaxVideoSize(w, h) }
                } else {
                    // Fixed mode: no adaptation, and the highest track within
                    // the cap — i.e. the picked variant. The +1 absorbs float
                    // rounding of BANDWIDTH/1000 → kbps → bps.
                    config.fixedBitrateKbps?.let { setMaxVideoBitrate((it * 1000).roundToInt() + 1) }
                    setForceHighestSupportedBitrate(true)
                }
            }.build()
        }
        val player = ExoPlayer.Builder(context).setTrackSelector(trackSelector).build()
        val stats = PlaybackStats()
        player.addAnalyticsListener(stats)

        val timeoutMs = config.timeoutSec * 1000L
        Log.d(TAG, "starting playback: url=${config.url}, timeout=${config.timeoutSec}s, adaptive=${config.adaptive}, resolution=${config.resolution}")

        val startedAt = SystemClock.elapsedRealtime()
        stats.startedAt = startedAt
        val fpsSamples = mutableListOf<Double>()
        var endedAt: Long

        try {
            _player.value = player
            player.setMediaItem(MediaItem.fromUri(config.url))
            player.playWhenReady = true
            player.prepare()

            var lastSampleAt = startedAt
            var lastRendered = renderedFrames(player)
            while (true) {
                delay(SAMPLE_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                val rendered = renderedFrames(player)
                if (player.isPlaying && now > lastSampleAt) {
                    fpsSamples += (rendered - lastRendered).coerceAtLeast(0) * 1000.0 / (now - lastSampleAt)
                }
                lastRendered = rendered
                lastSampleAt = now

                if (stats.error != null || stats.ended || now - startedAt >= timeoutMs) break
            }
        } finally {
            endedAt = SystemClock.elapsedRealtime()
            stats.closeRebuffer(endedAt)
            _player.value = null
            player.release()
        }

        val error = stats.error
        val errorMessage = when {
            error != null -> "Playback error (${error.errorCodeName}): ${error.message}"
            stats.firstPlayingAt == null -> "Playback did not start within ${config.timeoutSec}s"
            else -> null
        }

        val metrics = HlsMetrics(
            hlsUrl = config.url,
            hlsAdaptive = config.adaptive,
            hlsResolution = config.resolution,
            fixedBitrateKbps = if (config.adaptive) null else stats.firstBitrateKbps,
            maxBitrateKbps = stats.maxBitrateKbps,
            minBitrateKbps = stats.minBitrateKbps,
            resolutionUpCount = stats.resolutionUpCount,
            resolutionDownCount = stats.resolutionDownCount,
            startupDelayMs = stats.firstPlayingAt?.let { (it - startedAt).toDouble() },
            totalDurationMs = (endedAt - startedAt).toDouble(),
            rebufferCount = stats.rebufferCount,
            rebufferTimeMs = stats.rebufferTimeMs.toDouble(),
            droppedFrames = stats.droppedFrames,
            fpsAvg = fpsSamples.takeIf { it.isNotEmpty() }?.average(),
            fpsPeak = fpsSamples.maxOrNull(),
            avgSegmentBitrateKbps = stats.segmentKbps.takeIf { it.isNotEmpty() }?.average(),
            peakSegmentBitrateKbps = stats.segmentKbps.maxOrNull(),
        )
        Log.d(TAG, "playback finished: error=$errorMessage, metrics=$metrics")
        Outcome(metrics, errorMessage)
    }

    private fun renderedFrames(player: ExoPlayer): Int =
        player.videoDecoderCounters?.let {
            it.ensureUpdated()
            it.renderedOutputBufferCount
        } ?: 0

    /** Accumulates playback events; all callbacks arrive on the main thread. */
    private class PlaybackStats : AnalyticsListener {
        var startedAt = 0L
        var firstPlayingAt: Long? = null
        var ended = false
        var error: PlaybackException? = null

        var rebufferCount = 0
        var rebufferTimeMs = 0L
        private var rebufferStartAt: Long? = null

        var droppedFrames = 0

        var minBitrateKbps: Double? = null
        var maxBitrateKbps: Double? = null
        var firstBitrateKbps: Double? = null
        var resolutionUpCount = 0
        var resolutionDownCount = 0
        private var lastQuality: Int? = null

        val segmentKbps = mutableListOf<Double>()

        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
            if (isPlaying && firstPlayingAt == null) {
                firstPlayingAt = SystemClock.elapsedRealtime()
                Log.d(TAG, "first playing after ${firstPlayingAt!! - startedAt}ms")
            }
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            val now = SystemClock.elapsedRealtime()
            when (state) {
                // Buffering before playback first starts is startup delay, not a rebuffer.
                Player.STATE_BUFFERING -> if (firstPlayingAt != null && rebufferStartAt == null) {
                    rebufferCount++
                    rebufferStartAt = now
                }
                Player.STATE_READY -> closeRebuffer(now)
                Player.STATE_ENDED -> {
                    closeRebuffer(now)
                    ended = true
                }
            }
        }

        fun closeRebuffer(now: Long) {
            rebufferStartAt?.let { rebufferTimeMs += now - it }
            rebufferStartAt = null
        }

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            Log.e(TAG, "player error", error)
            this.error = error
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this.droppedFrames += droppedFrames
        }

        // Variant switches: trackFormat carries the playlist variant's
        // resolution and BANDWIDTH. Muxed A/V streams report TRACK_TYPE_DEFAULT.
        override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
            if (!isVideoLoad(mediaLoadData)) return
            val format = mediaLoadData.trackFormat ?: return

            if (format.bitrate != Format.NO_VALUE && format.bitrate > 0) {
                val kbps = format.bitrate / 1000.0
                if (firstBitrateKbps == null) firstBitrateKbps = kbps
                minBitrateKbps = minOf(minBitrateKbps ?: kbps, kbps)
                maxBitrateKbps = maxOf(maxBitrateKbps ?: kbps, kbps)
            }

            // Compare by height when known, else by bitrate (audio-less variants without dimensions).
            val quality = if (format.height != Format.NO_VALUE) format.height else format.bitrate
            if (quality == Format.NO_VALUE) return
            val prev = lastQuality
            when {
                prev == null -> Unit
                quality > prev -> resolutionUpCount++
                quality < prev -> resolutionDownCount++
            }
            lastQuality = quality
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA || !isVideoLoad(mediaLoadData)) return
            if (loadEventInfo.loadDurationMs <= 0 || loadEventInfo.bytesLoaded <= 0) return
            // bits per millisecond == kilobits per second
            segmentKbps += loadEventInfo.bytesLoaded * 8.0 / loadEventInfo.loadDurationMs
        }

        private fun isVideoLoad(data: MediaLoadData) =
            data.trackType == C.TRACK_TYPE_VIDEO || data.trackType == C.TRACK_TYPE_DEFAULT
    }
}
