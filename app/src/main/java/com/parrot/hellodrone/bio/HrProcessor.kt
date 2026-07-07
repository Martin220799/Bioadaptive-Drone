package com.parrot.hellodrone.bio

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * QC-State für Datenqualitäts-Kategorisierung
 */
enum class QcState {
    OK,        // > 70% valid, connection stable
    DEGRADED,  // connection OK, aber < 70% valid oder keine RR
    LOST       // connection lost (> 3s ohne Daten)
}

/**
 * Erweiterter QC-Status mit detaillierten Metriken
 */
data class QcStatus(
    val validRatioOverall: Float,   // Gesamt-Validität seit resetQc()
    val rrInPerSec: Int,            // Incoming RR im letzten Tick
    val rrValidPerSec: Int,         // Valid RR im letzten Tick
    val rrValidRatioTick: Float,    // Validität des letzten Ticks
    val artifactsFilteredCountTick: Int, // NEU: rrInPerSec - rrValidPerSec
    val msSinceLastBle: Long,       // Millisekunden seit letztem Datenpaket
    val connectionStable: Boolean,  // < 3000ms seit letzten Daten
    val qcState: QcState            // Kategorisierter Status
)

/**
 * Verarbeitung von Herzfrequenz- und RR-Daten mit:
 * 1. Artefaktfilter (RR-Intervallprüfung)
 * 2. HR-Glättung (Moving Average)
 * 3. Erweiterte QC-Metriken (incoming/valid/state)
 */
class HrProcessor(
    private val windowSize: Int = 4,              // Glättungsfenster für HR (Sekunden)
    private val rrArtifactThreshold: Double = 0.2 // max. 20% Sprung erlaubt
) {
    private val hrWindow = ConcurrentLinkedQueue<Int>()
    private var lastRR: Int? = null

    // Gesamt-Tracking (seit resetQc)
    private var validRRCount = 0
    private var totalRRCount = 0

    // Tick-Tracking (letzter Aufruf)
    private var lastRrInCount: Int = 0      // Incoming RR im letzten Tick
    private var lastRrValidCount: Int = 0   // Valid RR im letzten Tick

    // Zeitstempel des letzten Datenpakets (für Watchdog)
    private var lastDataTimestamp: Long = 0L

    /**
     * Verarbeitung eines neuen Datensatzes vom Polar H10
     * @param hr aktuelle Herzfrequenz in bpm
     * @param rrList RR-Intervalle (Millisekunden)
     * @return geglättete Herzfrequenz in bpm
     */
    fun process(hr: Int, rrList: List<Int>): Int {
        lastDataTimestamp = System.currentTimeMillis()

        val validRR = filterRR(rrList)

        // Gesamt-Zähler
        totalRRCount += rrList.size
        validRRCount += validRR.size

        // Tick-Zähler
        lastRrInCount = rrList.size
        lastRrValidCount = validRR.size

        // HR glätten über Fenster
        hrWindow.add(hr)
        if (hrWindow.size > windowSize) hrWindow.poll()
        return hrWindow.average().toInt()
    }

    /**
     * Filtert ungültige RR-Werte (Artefakte)
     */
    private fun filterRR(rrList: List<Int>): List<Int> {
        val valid = mutableListOf<Int>()
        for (rr in rrList) {
            if (rr in 300..2000) { // physiologisch plausible Grenzen
                val last = lastRR
                if (last == null || abs(rr - last) <= rrArtifactThreshold * last) {
                    valid.add(rr)
                    lastRR = rr
                }
            }
        }
        return valid
    }

    /**
     * Öffentlicher Artefaktfilter für HRV-Berechnung (verwendet dieselbe Logik wie QC).
     */
    fun filterRrForHrv(rrList: List<Int>): List<Int> = filterRR(rrList)

    /**
     * Liefert erweiterten Qualitätsstatus (für UI / Logging)
     */
    fun getQcStatus(): QcStatus {
        val now = System.currentTimeMillis()
        val msSinceLast = if (lastDataTimestamp == 0L) {
            Long.MAX_VALUE
        } else {
            now - lastDataTimestamp
        }

        val artifactsFilteredCountTick = (lastRrInCount - lastRrValidCount).coerceAtLeast(0)
        val stable = lastDataTimestamp != 0L && msSinceLast < 3000L

        // Gesamt-Validität (seit resetQc)
        val overall = if (totalRRCount > 0) {
            validRRCount.toFloat() / totalRRCount
        } else 0f

        // Tick-Validität (letzter Aufruf)
        val tickRatio = if (lastRrInCount > 0) {
            lastRrValidCount.toFloat() / lastRrInCount
        } else 0f

        // QC-State Kategorisierung
        val state = when {
            !stable -> QcState.LOST                          // Verbindung verloren
            lastRrInCount == 0 -> QcState.DEGRADED          // Keine Daten im Tick
            tickRatio < 0.7f -> QcState.DEGRADED            // Zu viele Artefakte
            else -> QcState.OK                               // Alles gut
        }

        return QcStatus(
            validRatioOverall = overall,
            rrInPerSec = lastRrInCount,
            rrValidPerSec = lastRrValidCount,
            rrValidRatioTick = tickRatio,
            artifactsFilteredCountTick = artifactsFilteredCountTick,
            msSinceLastBle = msSinceLast,
            connectionStable = stable,
            qcState = state
        )
    }

    /**
     * Setzt alle QC-Zähler und State zurück (zwischen Sessions)
     */
    fun resetQc() {
        validRRCount = 0
        totalRRCount = 0
        lastRrInCount = 0
        lastRrValidCount = 0
        lastDataTimestamp = 0L
        lastRR = null
        hrWindow.clear()
    }

    /**
     * Setzt nur RR-Filter State zurück (nicht QC-Counters oder HR-Window)
     * Verwendet bei Calibration Walk wo HR-Smoothing erhalten bleiben soll
     */
    fun resetRrFilterState() {
        lastRR = null
    }

    // Getter für Logging/Reproduzierbarkeit
    fun getArtifactThreshold(): Double = rrArtifactThreshold
    fun getHrWindowSize(): Int = windowSize
}