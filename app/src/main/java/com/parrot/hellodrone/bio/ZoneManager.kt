package com.parrot.hellodrone.bio

import android.os.SystemClock
import kotlin.math.roundToInt
import android.util.Log

/**
 * ZoneInfo: Enthaelt Zone + relative Position innerhalb der Zone
 *
 * @param zone Die aktuelle HR-Zone
 * @param relativePosition Position in Zone (0.0 = untere Grenze, 1.0 = obere Grenze)
 */
data class ZoneInfo(
    val zone: Zone,
    val relativePosition: Float  // 0.0 - 1.0
)
enum class Zone { GREEN, YELLOW, RED }

/**
 * ZoneManager mit individueller Kalibrierung pro Proband.
 *
 * VERSION 2: Mit Position-Tracking fuer optimale Geschwindigkeitsanpassung
 *
 * Grundlage:
 *  - hrRest: Ruheherzfrequenz (aus Pre-HRV-Phase, z.B. Mittelwert der 60s)
 *  - hrWalk: Herzfrequenz bei zuegigem Gehen (Mittelwert der letzten 60-90s des Kalibrier-Spaziergangs)
 *  - rmssdPre: HRV (RMSSD) aus der Pre-HRV-Phase, wird als Stress-/Belastbarkeits-Indikator genutzt
 *
 * Zonen:
 *  - GREEN: unterhalb ~0.5 * Delta_walk ueber Ruhe
 *  - YELLOW: um den kalibrierten Walk-Bereich
 *  - RED: deutlich oberhalb des Walk-Bereichs (~1.1 * Delta_walk)
 *
 * RMSSD-Feintuning:
 *  - rmssdPre < 20 ms  -> Schwellen -5 bpm (vorsichtiger)
 *  - rmssdPre >= 40 ms -> Schwellen +5 bpm (robuster)
 */
class ZoneManager(
    private val minDwellMs: Long = 2000L, // Mindest-Verweilzeit in einer Zone
    private val hysteresisGap: Int = 2    // Hysterese in bpm
) {

    // Kalibrierwerte pro Proband
    private var hrRest: Int? = null
    private var hrWalk: Int? = null
    private var rmssdPre: Double? = null

    // Absolut-Schwellen fuer Zonen (nicht nur Deltas)
    private var thYellowEnter: Int? = null
    private var thRedEnter: Int? = null

    // interner Zustand fuer Hysterese/Dwell
    private var lastZone: Zone = Zone.GREEN
    private var lastSwitchMs: Long = 0L

    /**
     * Einmal pro Proband aufrufen, nachdem:
     *  - Pre-HRV (hrRest, rmssdPre) vorliegt
     *  - Kalibrier-Spaziergang (hrWalk) vorliegt
     */

    fun configureFromCalibration(
        hrRest: Int,
        hrWalk: Int,
        rmssdPre: Double?
    ) {
        this.hrRest = hrRest
        this.hrWalk = hrWalk
        this.rmssdPre = rmssdPre

        val deltaWalk = (hrWalk - hrRest).coerceAtLeast(5) // mind. 5 bpm Abstand

        // Basis-Schwellen ohne HRV-Feintuning
        var thY = hrRest + (0.5 * deltaWalk).roundToInt()   // ~ halber Weg zwischen Ruhe und Walk
        var thR = hrRest + (1.1 * deltaWalk).roundToInt()   // etwas ueber Walk

        // RMSSD-Feintuning: sensibler vs. robuster
        rmssdPre?.let { r ->
            when {
                r < 20.0 -> { // eher gestresst / wenig Reserven
                    thY -= 5
                    thR -= 5
                }
                r >= 40.0 -> { // erholt / fit
                    thY += 5
                    thR += 5
                }
                // 20-40 ms: keine Anpassung
            }
        }

        // auf sinnvolle Bereiche clampen
        if (thY < hrRest + 3) thY = hrRest + 3
        if (thR < thY + 3) thR = thY + 3

        thYellowEnter = thY
        thRedEnter = thR

        // Zustand zuruecksetzen
        reset(SystemClock.elapsedRealtime())
    }

    /**
     * NEU: Setzt Schwellwerte direkt (fuer Profile-Laden)
     *
     * Verwende diese Methode wenn Schwellwerte aus einem gespeicherten Profil
     * wiederhergestellt werden sollen, um absolute Konsistenz ueber Sessions zu garantieren.
     */
    fun setThresholds(
        hrRest: Int,
        hrWalk: Int,
        rmssdPre: Double?,
        thresholdYellow: Int,
        thresholdRed: Int
    ) {
        this.hrRest = hrRest
        this.hrWalk = hrWalk
        this.rmssdPre = rmssdPre
        this.thYellowEnter = thresholdYellow
        this.thRedEnter = thresholdRed

        // Zustand zuruecksetzen
        reset(SystemClock.elapsedRealtime())

        Log.i("ZoneManager", "Schwellwerte direkt gesetzt: Y=$thresholdYellow, R=$thresholdRed")
    }

    /**
     * Rueckwaertskompatible API: gibt Zone fuer aktuellen hrSmooth zurueck.
     * Muss vor der Benutzung per configureFromCalibration(...) konfiguriert werden.
     */
    fun getZone(hrSmooth: Int, nowMs: Long): Zone {
        return update(hrSmooth, nowMs)
    }

    /**
     * NEU: Zone MIT relativer Position zurueckgeben
     *
     * Dies ermoeglicht dem SpeedControllerV0, innerhalb einer Zone
     * kontinuierlich die Geschwindigkeit anzupassen.
     */
    fun getZoneWithPosition(hrSmooth: Int, nowMs: Long): ZoneInfo {
        val zone = update(hrSmooth, nowMs)
        val relPos = calculateRelativePosition(hrSmooth, zone)
        return ZoneInfo(zone, relPos)
    }

    /**
     * Berechnet relative Position in der aktuellen Zone (0.0 - 1.0)
     */
    private fun calculateRelativePosition(hrSmooth: Int, zone: Zone): Float {
        val thY = thYellowEnter ?: return 0f
        val thR = thRedEnter ?: return 0f
        val rest = hrRest ?: return 0f

        return when (zone) {
            Zone.GREEN -> {
                // GREEN: 0.0 bei hrRest, 1.0 bei thYellowEnter
                val range = (thY - rest).toFloat()
                if (range > 0) {
                    ((hrSmooth - rest) / range).coerceIn(0f, 1f)
                } else {
                    0.5f
                }
            }

            Zone.YELLOW -> {
                // YELLOW: 0.0 bei thYellowEnter, 1.0 bei thRedEnter
                val range = (thR - thY).toFloat()
                if (range > 0) {
                    ((hrSmooth - thY) / range).coerceIn(0f, 1f)
                } else {
                    0.5f
                }
            }

            Zone.RED -> {
                // RED: 0.0 bei thRedEnter, 1.0 bei thRedEnter + 20 bpm
                val range = 20f
                ((hrSmooth - thR) / range).coerceIn(0f, 1f)
            }
        }
    }


    /**
     * Zustandsbehaftete Aktualisierung mit Hysterese und Dwell-Time.
     */
    fun update(
        hrSmooth: Int,
        nowMs: Long
    ): Zone {
        val thY = thYellowEnter
        val thR = thRedEnter
        val base = hrRest

        // Falls noch nicht kalibriert / kein Profil geladen:
        // Ohne valide Schwellen ist keine sinnvolle Zonierung moeglich. Daher bleiben wir stabil in GREEN.
        if (thY == null || thR == null || base == null) {
            if (lastZone != Zone.GREEN) {
                lastZone = Zone.GREEN
                lastSwitchMs = nowMs
            }
            return lastZone
        }

        val dwellOk = (nowMs - lastSwitchMs) >= minDwellMs

        // Hysterese-Grenzen
        val enterYellow = thY
        val exitYellowToGreen = thY - hysteresisGap
        val enterRed = thR
        val exitRedToYellow = thR - hysteresisGap

        val hr = hrSmooth
        var zone = lastZone

        when (lastZone) {
            Zone.GREEN -> {
                if (dwellOk && hr >= enterYellow) {
                    zone = Zone.YELLOW
                }
            }
            Zone.YELLOW -> {
                if (dwellOk && hr >= enterRed) {
                    zone = Zone.RED
                } else if (dwellOk && hr <= exitYellowToGreen) {
                    zone = Zone.GREEN
                }
            }
            Zone.RED -> {
                if (dwellOk && hr <= exitRedToYellow) {
                    zone = Zone.YELLOW
                }
            }
        }

        if (zone != lastZone) {
            lastZone = zone
            lastSwitchMs = nowMs
        }

        return zone
    }

    /**
     * Zwischen Sessions/Fluege aufrufen, um die Hysterese zurueckzusetzen.
     */
    fun reset(nowMs: Long) {
        lastZone = Zone.GREEN
        lastSwitchMs = nowMs
    }

    // In ZoneManager.kt am Ende der Klasse hinzufuegen:
    fun getThresholdYellow(): Int? = thYellowEnter
    fun getThresholdRed(): Int? = thRedEnter
}