package com.parrot.hellodrone.speed
import com.parrot.hellodrone.bio.Zone
import com.parrot.hellodrone.bio.ZoneInfo
import android.util.Log

class SpeedControllerV1 (
    private val vBase: Float = 1.0f,
    private val vMin: Float = 0.5f,
    private val vMax: Float = 2.0f,
    private val maxAccel: Float = 0.4f,
    private val redPauseThresholdMs: Long = 45_000L,
    private val yellowScalingFactor: Float = 0.4f,
    private val redScalingFactor: Float = 0.5f
    ) : ISpeedController {

        private var lastV: Float = 0f
        private var lastTsMs: Long = 0L
        private var lastHr: Int = 0
        private var redZoneDurationMs: Long = 0L
        private val window: ArrayDeque<Float> = ArrayDeque()
        private var lastSmoothedOutput: Float = 0f

        /**
         * V1 verwendet nur Zone (nicht Position)
         */
        override fun update(zone: Zone, hrSmooth: Int, nowMs: Long): Float {
            if (lastTsMs == 0L) {
                lastTsMs = nowMs
            }
            val dtSec = ((nowMs - lastTsMs).coerceAtLeast(1L)) / 1000f
            lastTsMs = nowMs

            val hrTrend = if (lastHr == 0) 0 else hrSmooth - lastHr
            lastHr = hrSmooth

            // V1 LOGIK: Trend-basierte Geschwindigkeitsanpassung
            val vTargetBase = when (zone) {
                Zone.GREEN -> vBase

                Zone.YELLOW -> {
                    when {
                        hrTrend > 2  -> vBase * (1.0f - yellowScalingFactor * 0.8f)  // 0.68 m/s
                        hrTrend > 0  -> vBase * (1.0f - yellowScalingFactor * 0.5f)  // 0.80 m/s
                        hrTrend < -2 -> vBase * (1.0f - yellowScalingFactor * 0.2f)  // 0.92 m/s
                        else         -> vBase * (1.0f - yellowScalingFactor * 0.5f)  // 0.80 m/s
                    }.coerceIn(vMin, vBase)
                }

                Zone.RED -> {
                    val vRedDynamic = when {
                        hrTrend > 2  -> vBase * (1.0f - redScalingFactor - 0.2f)  // 0.30 m/s
                        hrTrend > 0  -> vBase * (1.0f - redScalingFactor)         // 0.50 m/s
                        else         -> vBase * (1.0f - redScalingFactor + 0.1f)  // 0.60 m/s
                    }.coerceIn(vMin, vBase * 0.7f)

                    redZoneDurationMs += (dtSec * 1000f).toLong()
                    if (redZoneDurationMs >= redPauseThresholdMs) {
                        0.0f
                    } else {
                        vRedDynamic
                    }
                }
            }

            if (zone != Zone.RED) {
                redZoneDurationMs = 0L
            }

            val vLimited = applyAccelLimit(vTargetBase, dtSec)
            val smoothed = smooth(vLimited)
            lastSmoothedOutput = smoothed

            Log.d("SPEED_V1", "Zone=$zone, hrTrend=$hrTrend, target=${"%.2f".format(vTargetBase)}, out=${"%.2f".format(smoothed)}")

            return smoothed
        }

        /**
         * V1 unterstützt kein ZoneInfo - Fallback auf Zone
         */
        override fun update(zoneInfo: ZoneInfo, hrSmooth: Int, nowMs: Long): Float {
            Log.w("SPEED_V1", "V1 unterstützt keine Position - verwende nur Zone")
            return update(zoneInfo.zone, hrSmooth, nowMs)
        }

        private fun applyAccelLimit(vTarget: Float, dtSec: Float): Float {
            val dvMax = maxAccel * dtSec
            val vClamped = when {
                vTarget > lastV + dvMax -> lastV + dvMax
                vTarget < lastV - dvMax -> lastV - dvMax
                else -> vTarget
            }
            val vFinal = vClamped.coerceIn(0f, vMax)
            lastV = vFinal
            return vFinal
        }

        private fun smooth(v: Float): Float {
            if (window.size == 3) window.removeFirst()
            window.addLast(v)
            return window.average().toFloat()
        }

        override fun reset() {
            lastV = 0f
            lastTsMs = 0L
            lastHr = 0
            redZoneDurationMs = 0L
            window.clear()
            lastSmoothedOutput = 0f
            Log.i("SPEED_V1", "SpeedController V1 reset")
        }

        override fun getLastOutput(): Float = lastSmoothedOutput

        override fun setOutput(value: Float) {
            val clamped = value.coerceIn(0f, vMax)
            lastV = clamped
            lastSmoothedOutput = clamped
            window.clear()
            window.addLast(clamped)
            Log.d("SPEED_V1", "Output gesetzt auf ${"%.2f".format(clamped)} m/s")
        }

        override fun getRedZoneDurationMs(): Long = redZoneDurationMs
        override fun isPauseActive(): Boolean = redZoneDurationMs >= redPauseThresholdMs
        override fun getLastHr(): Int = lastHr
}