package com.parrot.hellodrone.profile

import java.text.SimpleDateFormat
import java.util.*

/**
 * ParticipantProfile: Alle relevanten Daten eines Probanden für wiederverwendbare Kalibrierung
 *
 * Wird als JSON gespeichert und kann über mehrere Sessions/Prototypen hinweg geladen werden.
 * Garantiert konsistente HR-Schwellwerte über alle drei Prototypen (Outdoor/Indoor-Altitude/Simulation).
 *
 * ERWEITERT mit Qualitätsmetriken für wissenschaftliche Reproduzierbarkeit.
 */
data class ParticipantProfile(
    // Eindeutige ID des Probanden (z.B. "P01", "P02", ...)
    val participantId: String,

    // Demographische Daten
    val age: Int? = null,
    val gender: String? = null,  // "M", "F", "D", "N/A"
    val fitnessLevel: String? = null,  // "beginner", "intermediate", "advanced"

    // Kalibrierungs-Zeitstempel
    val calibrationTimestamp: String,  // ISO-Format: "2025-01-04T14:23:00Z"

    // HR-Kalibrierung (aus Calibration-Walk)
    val hrRest: Int,          // Ruheherzfrequenz (bpm)
    val hrWalk: Int,          // Herzfrequenz bei zügigem Gehen (bpm)
    val deltaWalk: Int,       // hrWalk - hrRest

    // NEU: Kalibrierungs-Qualitätsmetriken
    val calibrationDurationSec: Int,           // Dauer des Calibration Walks
    val calibrationValidRatio: Float,          // Datenqualität (% valide RR)
    val calibrationRrCount: Int,               // Anzahl RR während Kalibrierung

    // HRV-Baseline (aus Pre-Flight HRV-Messung)
    val rmssdBaseline: Double,  // RMSSD in ms
    val sdnnBaseline: Double? = null,  // Optional: SDNN in ms

    // NEU: HRV-Qualitätsmetriken
    val hrvDurationSec: Int,                   // Dauer der HRV-Messung
    val hrvRrCollected: Int,                   // RR vor Filter
    val hrvRrValid: Int,                       // RR nach Filter
    val hrvValidRatio: Float,                  // Datenqualität HRV

    // Berechnete Schwellwerte (von ZoneManager)
    val thresholdGreenYellow: Int,  // Schwelle GREEN -> YELLOW (bpm)
    val thresholdYellowRed: Int,    // Schwelle YELLOW -> RED (bpm)

    // NEU: System-Parameter (Reproduzierbarkeit)
    val hrWindowSize: Int = 4,                 // Glättungsfenster (Sekunden)
    val rrArtifactThreshold: Double = 0.2,     // Artefakt-Threshold (20%)

    // Metadaten
    val notes: String? = null,  // Optionale Notizen (z.B. "Leicht erkältet")
    val createdBy: String = "FlyingPacer"  // System-Identifikator
) {
    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        /**
         * Erzeugt aktuellen Zeitstempel im ISO-8601 Format
         */
        fun getCurrentTimestamp(): String {
            return dateFormat.format(Date())
        }

        /**
         * Parst ISO-Zeitstempel zurück zu Date (für Anzeige)
         */
        fun parseTimestamp(timestamp: String): Date? {
            return try {
                dateFormat.parse(timestamp)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Gibt formatiertes Datum für UI-Anzeige zurück
     */
    fun getFormattedCalibrationDate(): String {
        return parseTimestamp(calibrationTimestamp)?.let {
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN).format(it)
        } ?: "Unbekannt"
    }

    /**
     * Validierung: Prüft ob die Profil-Daten plausibel sind
     *
     * ERWEITERT mit QC-Metrik-Validierung
     */
    fun isValid(): Boolean {
        return participantId.isNotBlank() &&
                hrRest in 40..100 &&
                hrWalk in 60..180 &&
                hrWalk > hrRest &&
                deltaWalk == (hrWalk - hrRest) &&
                deltaWalk > 0 &&
                rmssdBaseline > 0 &&
                (age == null || age in 18..100) &&
                (gender == null || gender in listOf("M", "F", "D", "N/A")) &&
                (sdnnBaseline == null || sdnnBaseline > 0) &&
                thresholdGreenYellow > hrRest &&
                thresholdGreenYellow <= hrWalk + 30 &&
                thresholdYellowRed > thresholdGreenYellow + 5 &&
                thresholdYellowRed < 220 &&
                // NEU: Kalibrierungs-QC Validierung
                calibrationDurationSec in 30..600 &&
                calibrationValidRatio >= 0f && calibrationValidRatio <= 1f &&
                calibrationRrCount > 0 &&
                // NEU: HRV-QC Validierung
                hrvDurationSec in 30..600 &&
                hrvRrCollected > 0 &&
                hrvRrValid > 0 &&
                hrvRrValid <= hrvRrCollected &&
                hrvValidRatio >= 0f && hrvValidRatio <= 1f &&
                // NEU: System-Parameter Validierung
                hrWindowSize in 1..10 &&
                rrArtifactThreshold >= 0.0 && rrArtifactThreshold <= 1.0
    }

    /**
     * Kurze Zusammenfassung für UI-Display
     *
     * ERWEITERT mit QC-Metriken
     */
    fun getSummary(): String {
        return buildString {
            append("$participantId")
            age?.let { append(" ($it J.)") }
            append("\n")
            append("Kalibriert: ${getFormattedCalibrationDate()}\n")
            append("HR: $hrRest -> $hrWalk bpm (Δ=$deltaWalk)\n")
            append("Schwellen: $thresholdGreenYellow / $thresholdYellowRed bpm\n")
            append("RMSSD: ${"%.1f".format(Locale.US, rmssdBaseline)} ms\n")
            // NEU: QC-Info
            append("QC: Cal=${(calibrationValidRatio*100).toInt()}%, ")
            append("HRV=${(hrvValidRatio*100).toInt()}%")
        }
    }

    /**
     * Detaillierte Qualitäts-Info für Logging/Reports
     */
    fun getQualityReport(): String {
        return buildString {
            appendLine("=== Kalibrierungs-Qualität ===")
            appendLine("Dauer: ${calibrationDurationSec}s")
            appendLine("RR-Anzahl: $calibrationRrCount")
            appendLine("Validität: ${(calibrationValidRatio*100).toInt()}%")
            appendLine()
            appendLine("=== HRV-Baseline-Qualität ===")
            appendLine("Dauer: ${hrvDurationSec}s")
            appendLine("RR: $hrvRrValid/$hrvRrCollected (${(hrvValidRatio*100).toInt()}%)")
            appendLine("RMSSD: ${"%.1f".format(Locale.US, rmssdBaseline)} ms")
            sdnnBaseline?.let { appendLine("SDNN: ${"%.1f".format(Locale.US, it)} ms") }
            appendLine()
            appendLine("=== System-Parameter ===")
            appendLine("HR-Fenster: ${hrWindowSize}s")
            appendLine("RR-Threshold: ${(rrArtifactThreshold*100).toInt()}%")
        }
    }
}