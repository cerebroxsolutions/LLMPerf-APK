package com.llmperf.llmperf_apk.data.metrics

import android.util.Log
import com.llmperf.llmperf_apk.data.model.IcmpMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil

private const val TAG = "IcmpPingTest"
private const val DEFAULT_PING_COUNT = 10
private const val MIN_PING_COUNT = 1
private const val PING_TIMEOUT_SEC = 2L

private val TIME_REGEX = Regex("""time[=<]([0-9.]+)""")

/**
 * Shells out to the system `ping` binary — Android apps don't hold
 * CAP_NET_RAW, so a raw ICMP socket (the approach used by aiperf's Android
 * telemetry) isn't available here. RTTs are recorded in the order replies
 * arrive (send order), not sorted, so jitter reflects successive-packet
 * variation rather than an artifact of sorting.
 */
class IcmpPingTest {

    suspend fun run(targetHost: String, count: Int = DEFAULT_PING_COUNT): IcmpMetrics =
        withContext(Dispatchers.IO) {
            val safeCount = count.coerceAtLeast(MIN_PING_COUNT)
            // Bounds how long this run may take if the process hangs instead of
            // exiting after its own -c/-W bounds (e.g. stuck DNS resolution) —
            // scaled to the actual requested count, not a fixed constant, so a
            // larger operator-chosen count isn't killed early.
            val processTimeoutSec = safeCount * (PING_TIMEOUT_SEC + 1) + 5
            Log.d(TAG, "starting icmp test: host=$targetHost, count=$safeCount, perPacketTimeout=${PING_TIMEOUT_SEC}s")
            val rtts = mutableListOf<Double>()

            val process = ProcessBuilder(
                "/system/bin/ping", "-c", safeCount.toString(), "-W", PING_TIMEOUT_SEC.toString(), targetHost,
            )
                .redirectErrorStream(true)
                .start()

            // Drained concurrently with waitFor below — reading only after
            // waitFor risks deadlock if ping's stdout buffer fills before it
            // exits, and reading without a bound risks hanging forever if it
            // never exits and never closes its output.
            val readerJob = launch {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        Log.d(TAG, "ping: $line")
                        TIME_REGEX.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let(rtts::add)
                    }
                }
            }

            if (!process.waitFor(processTimeoutSec, TimeUnit.SECONDS)) {
                Log.w(TAG, "ping process for $targetHost did not exit within ${processTimeoutSec}s, destroying")
                process.destroyForcibly()
            }
            readerJob.join()

            val metrics = buildMetrics(safeCount, rtts)
            if (metrics.packetsReceived == 0) {
                Log.e(
                    TAG,
                    "icmp test to $targetHost got 0/${metrics.packetsSent} replies (100% packet loss) — " +
                        "target may be unreachable, blocking ICMP, or a firewall/security-group rule is dropping echo requests",
                )
            }
            Log.d(
                TAG,
                "icmp test complete: host=$targetHost, sent=${metrics.packetsSent}, received=${metrics.packetsReceived}, " +
                    "loss=${metrics.packetLossPct}%, avg=${metrics.latencyAvgMs}ms, min=${metrics.latencyMinMs}ms, " +
                    "max=${metrics.latencyMaxMs}ms, p50=${metrics.latencyP50Ms}ms, p90=${metrics.latencyP90Ms}ms, " +
                    "jitter=${metrics.jitterMs}ms",
            )
            metrics
        }

    private fun buildMetrics(sent: Int, rtts: List<Double>): IcmpMetrics {
        val received = rtts.size
        val sorted = rtts.sorted()
        return IcmpMetrics(
            packetsSent = sent,
            packetsReceived = received,
            packetLossPct = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent,
            latencyAvgMs = if (received > 0) rtts.average() else null,
            latencyMinMs = sorted.firstOrNull(),
            latencyMaxMs = sorted.lastOrNull(),
            latencyP50Ms = if (received > 0) median(sorted) else null,
            latencyP90Ms = if (received > 0) percentile(sorted, 90.0) else null,
            jitterMs = jitter(rtts),
        )
    }

    private fun median(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    // Nearest-rank percentile: rank = ceil(p/100 * n), 1-based. Using ceil
    // (not truncation) matters at n=10, p=90 — truncating gives index 9
    // (== max) every time; ceil gives index 8, the actual 90th-percentile sample.
    private fun percentile(sorted: List<Double>, p: Double): Double {
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun jitter(rtts: List<Double>): Double? {
        if (rtts.size < 2) return null
        return (1 until rtts.size).map { abs(rtts[it] - rtts[it - 1]) }.average()
    }
}
