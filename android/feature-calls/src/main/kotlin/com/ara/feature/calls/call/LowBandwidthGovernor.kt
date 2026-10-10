package com.ara.feature.calls.call

import org.webrtc.RTCStatsReport

/**
 * Adaptive low-bandwidth mode (spec §2 Calling, §8 Phase 7 DoD).
 *
 * A first-class design constraint: instead of letting a congested link drop
 * frames (or the call), the governor steps the encoder's max bitrate down a
 * ladder as RTT rises / available bandwidth falls, and steps back up when the
 * link recovers. Audio always keeps priority — voice never drops before video.
 */
class LowBandwidthGovernor(
    private val onBitrateChange: (videoMaxBitrateKbps: Int) -> Unit,
) {
    private var currentStep: Int = -1 // -1 = auto/default

    /** Ladder in kbps. Default is the top step; each step down halves roughly. */
    private val ladder = intArrayOf(2500, 1200, 600, 300, 150, 80)

    /**
     * Feed periodic getStats() samples; returns the applied step (for UI).
     * Heuristics:
     *  - RTT > 400ms or available outbound bitrate under the current step → step down
     *  - RTT < 120ms and headroom > 2× current step → step up (at most one step)
     */
    fun onStats(rttMs: Double, availableOutboundKbps: Double, packetsLostPercent: Double): Int {
        val target = currentStep.coerceAtLeast(0)
        var step = if (currentStep < 0) 0 else currentStep

        val degraded = rttMs > 400.0 || packetsLostPercent > 8.0 ||
            (availableOutboundKbps in 1.0..ladder[step].toDouble() * 0.7)

        val healthy = rttMs < 120.0 && packetsLostPercent < 2.0 &&
            availableOutboundKbps > ladder[step] * 2.2

        when {
            degraded && step < ladder.lastIndex -> step++
            healthy && step > 0 -> step--
        }
        if (step != currentStep) {
            currentStep = step
            onBitrateChange(ladder[step])
        }
        return target
    }

    /** Parse an RTCStatsReport for the metrics the governor needs. */
    fun extract(report: RTCStatsReport): Triple<Double, Double, Double> {
        var rtt = 0.0
        var outboundKbps = 0.0
        var lostPercent = 0.0
        for (stats in report.statsMap.values) {
            when (stats.type) {
                "candidate-pair" -> {
                    stats.members["currentRoundTripTime"]?.let { rtt = (it as Number).toDouble() * 1000 }
                }
                "outbound-rtp" -> {
                    stats.members["availableOutgoingBitrate"]?.let { outboundKbps = (it as Number).toDouble() / 1000 }
                    val lost = (stats.members["packetsLost"] as? Number)?.toDouble() ?: 0.0
                    val sent = (stats.members["packetsSent"] as? Number)?.toDouble() ?: 0.0
                    if (sent > 0) lostPercent = lost / (lost + sent) * 100
                }
            }
        }
        return Triple(rtt, outboundKbps, lostPercent)
    }

    fun reset() { currentStep = -1 }
}
