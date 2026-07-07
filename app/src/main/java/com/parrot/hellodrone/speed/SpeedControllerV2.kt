package com.parrot.hellodrone.speed
import com.parrot.hellodrone.bio.Zone
import com.parrot.hellodrone.bio.ZoneInfo

import android.util.Log

/**
 * SpeedController VERSION 2 (Position + Trend - Optimal)
 *
 * Kombiniert:
 * - Relative Position innerhalb der Zone
 * - HR-Trend (steigend/fallend)
 * - Kontinuierliche lineare Skalierung
 *
 * Beste physiologische Regelung.
 */

class SpeedControllerV2 (
    private val vBase: Float = 1.0f,
    private val vMin: Float = 0.5f,
    private val vMax: Float = 2.0f,
    private val maxAccel: Float = 0.4f,
    private val redPauseThresholdMs: Long = 45_000L
) : ISpeedController {

    private var lastV: Float = 0f
    private var lastTsMs: Long = 0L
    private var lastHr: Int = 0
    private var redZoneDurationMs: Long = 0L
    private val window: ArrayDeque<Float> = ArrayDeque()
    private var lastSmoothedOutput: Float = 0f

    /**
     * V2 Fallback: Wenn nur Zone übergeben wird, verwende 0.5 als Position
     */
    override fun update(zone: Zone, hrSmooth: Int, nowMs: Long): Float {
        Log.w("SPEED_V2", "V2 sollte mit ZoneInfo aufgerufen werden - verwende Position=0.5 als Fallback")
        return updateInternal(zone, 0.5f, hrSmooth, nowMs)
    }

    /**
     * V2 Hauptmethode: Mit ZoneInfo (Zone + Position)
     */
    override fun update(zoneInfo: ZoneInfo, hrSmooth: Int, nowMs: Long): Float {
        return updateInternal(zoneInfo.zone, zoneInfo.relativePosition, hrSmooth, nowMs)
    }

    /**
     * Interne Update-Logik mit Position + Trend
     */
    private fun updateInternal(
        zone: Zone,
        relativePosition: Float,
        hrSmooth: Int,
        nowMs: Long
    ): Float {
        if (lastTsMs == 0L) {
            lastTsMs = nowMs
        }
        val dtSec = ((nowMs - lastTsMs).coerceAtLeast(1L)) / 1000f
        lastTsMs = nowMs

        val hrTrend = if (lastHr == 0) 0 else hrSmooth - lastHr
        lastHr = hrSmooth

        // V2 LOGIK: Position + Trend kombiniert
        val vTargetBase = when (zone) {
            Zone.GREEN -> {
                // GREEN: 0.6 -> 1.0 m/s (linear mit Position)
                val baseSpeed = 0.6f + (relativePosition * 0.4f)

                val trendMod = when {
                    hrTrend > 3  -> -0.05f
                    hrTrend < -2 -> +0.05f
                    else         -> 0f
                }

                (baseSpeed + trendMod).coerceIn(0.6f, vBase)
            }

            Zone.YELLOW -> {
                // YELLOW: 1.0 -> 0.6 m/s (linear mit Position)
                val baseSpeed = vBase - (relativePosition * 0.4f)

                val trendMod = when {
                    hrTrend > 2  -> -0.15f
                    hrTrend > 0  -> -0.08f
                    hrTrend < -2 -> +0.10f
                    hrTrend < 0  -> +0.05f
                    else         -> 0f
                }

                (baseSpeed + trendMod).coerceIn(0.5f, vBase)
            }

            Zone.RED -> {
                // RED: 0.6 -> 0.3 m/s (linear mit Position)
                val baseSpeed = 0.6f - (relativePosition * 0.3f)

                val trendMod = when {
                    hrTrend > 2  -> -0.20f
                    hrTrend > 0  -> -0.10f
                    hrTrend < -2 -> +0.10f
                    else         -> 0f
                }

                val vRedDynamic = (baseSpeed + trendMod).coerceIn(vMin, 0.6f)

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

        Log.d("SPEED_V2", "Zone=$zone, pos=${"%.2f".format(relativePosition)}, trend=$hrTrend, target=${"%.2f".format(vTargetBase)}, out=${"%.2f".format(smoothed)}")

        return smoothed
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
        Log.i("SPEED_V2", "SpeedController V2 reset")
    }

    override fun getLastOutput(): Float = lastSmoothedOutput

    override fun setOutput(value: Float) {
        val clamped = value.coerceIn(0f, vMax)
        lastV = clamped
        lastSmoothedOutput = clamped
        window.clear()
        window.addLast(clamped)
        Log.d("SPEED_V2", "Output gesetzt auf ${"%.2f".format(clamped)} m/s")
    }

    override fun getRedZoneDurationMs(): Long = redZoneDurationMs
    override fun isPauseActive(): Boolean = redZoneDurationMs >= redPauseThresholdMs
    override fun getLastHr(): Int = lastHr
}