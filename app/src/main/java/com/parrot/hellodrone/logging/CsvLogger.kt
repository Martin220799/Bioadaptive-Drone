package com.parrot.hellodrone.logging

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.pow
import kotlin.math.sqrt
import android.content.ContentUris  // Am Anfang der Datei

class CsvLogger(private val context: Context) {

    private val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    private val baseDir = "AdaptiveDrone"
    private val mimeCsv = "text/csv"

    // Session-ID und Ordner für alle Logs einer Testsession
    private var currentSessionId: String = ""
    private var sessionSubdir: String = ""


    // I/O Synchronisation + Header-Bookkeeping:
    // - verhindert doppelte Header (MediaStore SIZE ist nicht zuverlässig "live")
    // - verhindert Race-Conditions, wenn mehrere Threads gleichzeitig loggen (BLE Callback + UI)
    private val ioLock = Any()
    private val headerWritten: MutableSet<String> = mutableSetOf()   // pro File innerhalb der aktuellen Session
    private val uriCache: MutableMap<String, Uri> = mutableMapOf()   // MediaStore Uri Cache pro File

    /** Neue Session starten - erstellt neuen Ordner */
    fun startNewSession(): String {
        synchronized(ioLock) {
            currentSessionId = generateSessionId()
            sessionSubdir = "$baseDir/$currentSessionId"

            // Neue Session => neue Files => Header/Uri Cache zurücksetzen
            headerWritten.clear()
            uriCache.clear()

            return currentSessionId
        }
    }

    fun getCurrentSessionId(): String = currentSessionId

    private fun generateSessionId(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        return "session_${dateFormat.format(Date())}"
    }

    /** Session-Metadaten (einmal pro Test) - ERWEITERT für 3-Prototypen-Studie */
    fun logSessionMeta(
        condition: String,
        streckeMeters: Int?,
        prototypeType: String = "OUTDOOR",      //  "OUTDOOR", "INDOOR_ALTITUDE", "SIMULATION"
        participantId: String = "ToFill",       //  Probanden-ID (z.B. "P01")
        participantAge: Int? = null,             //  Optional aus Profil
        speedControlVersion: String? = null,      // für Prototype 1+3
        altitudeControlVersion: String? = null    //  für Prototype 2
    ) {
        val fileName = "session_meta.csv"
        val header = "timestamp,session_id,prototype_type,condition,participant_id,age,strecke_m,speed_version,altitude_version\n"

        val streckeStr = streckeMeters?.toString() ?: ""
        val ageStr = participantAge?.toString() ?: "ToFill"
        val speedVerStr = speedControlVersion ?: ""
        val altVerStr = altitudeControlVersion ?: ""

        val line = buildString {
            append("${sdf.format(Date())},")
            append("$currentSessionId,")
            append("$prototypeType,")
            append("$condition,")
            append("$participantId,")
            append("$ageStr,")
            append("$streckeStr,")
            append("$speedVerStr,")
            append("$altVerStr\n")
        }
        appendCsv(fileName, header, line)
    }

    /** HRV-Ergebnisse (Pre/PostFlight) mit erweiterten QC-Metriken */
    fun logHrvResult(
        mode: String,
        durationSec: Int,
        rmssdMs: Double,
        sdnnMs: Double?,
        rrCollected: Int,          // vor Filter
        rrValid: Int,              // nach Filter
        meanHr: Int,
        minHr: Int,
        maxHr: Int,
        artifactThreshold: Double,
        hrWindowSize: Int
    ) {
        val fileName = "hrv_results.csv"
        val header = "timestamp,session_id,mode,duration_s,rmssd_ms,sdnn_ms," +
                "rr_collected,rr_valid,rr_valid_ratio,mean_hr,min_hr,max_hr," +
                "artifact_threshold,hr_window_size\n"

        val sdnnStr = sdnnMs?.let { "%.1f".format(Locale.US, it) } ?: ""
        val validRatio = if (rrCollected > 0) {
            rrValid.toDouble() / rrCollected
        } else 0.0

        val line = buildString {
            append("${sdf.format(Date())},")
            append("$currentSessionId,")
            append("$mode,")
            append("$durationSec,")
            append("${"%.1f".format(Locale.US, rmssdMs)},")
            append("$sdnnStr,")
            append("$rrCollected,")
            append("$rrValid,")
            append("%.2f,".format(Locale.US, validRatio))
            append("$meanHr,")
            append("$minHr,")
            append("$maxHr,")
            append("%.2f,".format(Locale.US, artifactThreshold))
            append("$hrWindowSize\n")
        }
        appendCsv(fileName, header, line)
    }

    /** Kalibrier-Ergebnis (Ruhe + Walk + RMSSD + Schwellen) */
    fun logCalibrationResult(
        hrRest: Int,
        hrWalk: Int,
        rmssdPre: Double?,
        nSamples: Int,
        thresholdYellow: Int?,
        thresholdRed: Int?
    ) {
        val fileName = "calibration_results.csv"
        val header = "timestamp,session_id,hr_rest,hr_walk,delta_walk,rmssd_pre_ms,n_samples,threshold_yellow,threshold_red\n"

        val rmssdStr = rmssdPre?.let { "%.1f".format(Locale.US, it) } ?: ""
        val deltaWalk = hrWalk - hrRest
        val thYellowStr = thresholdYellow?.toString() ?: ""
        val thRedStr = thresholdRed?.toString() ?: ""

        val line = buildString {
            append("${sdf.format(Date())},")
            append("$currentSessionId,")
            append("$hrRest,")
            append("$hrWalk,")
            append("$deltaWalk,")
            append("$rmssdStr,")
            append("$nSamples,")
            append("$thYellowStr,")
            append("$thRedStr\n")
        }
        appendCsv(fileName, header, line)
    }

    /** Erweiterte Live-HR-Logs während des Flugs */
    fun logHrSampleExtended(
        prototypeType: String,           // z.B. "SIMULATION" / "INDOOR_ALTITUDE" / "OUTDOOR"
        elapsedMs: Long,                 // monotone Zeit seit Session-Start (ms)
        relativePosition: Float,         // 0.0..1.0 innerhalb der Zone
        manualOverrideActive: Boolean,   // true wenn Joystick Override aktiv
        phase: String,
        hrRaw: Int,
        hrSmooth: Int,
        rrCount: Int,
        validRatioOverall: Float,
        rrInPerSec: Int,
        rrValidPerSec: Int,
        rrValidRatioTick: Float,
        msSinceLastBle: Long,
        connectionStable: Boolean,
        qcState: String,
        zone: String,
        vSet: Float,
        state: String,
        baselineHr: Int?,
        hrTrend: Int?,
        pauseActive: Boolean,
        mode: String,
        segmentId: Int,
        speedControlVersion: String,
        vSetV0: Float,
        vSetV1: Float,
        vSetV2: Float,
        artifactsFilteredCountTick: Int, // rr_in - rr_valid in diesem Tick

        altitudeControlVersion: String? = null,
        altTargetA0: Float? = null,
        altTargetA1: Float? = null,
        altTargetA2: Float? = null,
        altCurrent: Float? = null
    ) {
        val fileName = "hr_live.csv"
        val header =
            "timestamp,session_id,prototype_type,elapsed_ms,relative_position,manual_override_active," +
                    "phase,hr_raw,hr_smooth,baseline_hr,delta_hr,hr_trend," +
                    "rr_count,valid_ratio_overall,rr_in_per_sec,rr_valid_per_sec,rr_valid_ratio_tick," +
                    "artifacts_filtered_count_tick," +
                    "ms_since_last_ble,connection_stable,qc_state," +
                    "zone,v_set,state,pause_active,mode,segment_id,SpeedCtr_V," +
                    "v_set_v0,v_set_v1,v_set_v2," +
                    "AltCtr_V,alt_target_a0,alt_target_a1,alt_target_a2,alt_current\n"

        val baselineStr = baselineHr?.toString() ?: ""
        val deltaHr = if (baselineHr != null && baselineHr > 0) hrSmooth - baselineHr else null
        val deltaStr = deltaHr?.toString() ?: ""
        val trendStr = hrTrend?.toString() ?: ""

        val line = buildString {
            append("${sdf.format(Date())},")
            append("$currentSessionId,")
            append("$prototypeType,")
            append("$elapsedMs,")
            append("%.3f,".format(Locale.US, relativePosition))
            append("$manualOverrideActive,")
            append("$phase,")
            append("$hrRaw,")
            append("$hrSmooth,")
            append("$baselineStr,")
            append("$deltaStr,")
            append("$trendStr,")
            append("$rrCount,")
            append("%.2f,".format(Locale.US, validRatioOverall))
            append("$rrInPerSec,")
            append("$rrValidPerSec,")
            append("%.2f,".format(Locale.US, rrValidRatioTick))
            append("$artifactsFilteredCountTick,")
            append("$msSinceLastBle,")
            append("$connectionStable,")
            append("$qcState,")
            append("$zone,")
            append("%.2f,".format(Locale.US, vSet))
            append("$state,")
            append("${pauseActive},")
            append("$mode,")
            append("$segmentId,")
            append("$speedControlVersion,")
            append("%.2f,".format(Locale.US, vSetV0))
            append("%.2f,".format(Locale.US, vSetV1))
            append("%.2f,".format(Locale.US, vSetV2))
            append("${altitudeControlVersion ?: ""},")
            append("${altTargetA0?.let { "%.2f".format(Locale.US, it) } ?: ""},")
            append("${altTargetA1?.let { "%.2f".format(Locale.US, it) } ?: ""},")
            append("${altTargetA2?.let { "%.2f".format(Locale.US, it) } ?: ""},")
            append("${altCurrent?.let { "%.2f".format(Locale.US, it) } ?: ""}\n")
        }
        appendCsv(fileName, header, line)
    }


    fun logEvent(event: String) {
        val fileName = "events.csv"
        val header = "timestamp,session_id,event\n"

        val line = buildString {
            append("${sdf.format(Date())},")
            append("$currentSessionId,")
            append("$event\n")
        }
        appendCsv(fileName, header, line)
    }

    // --------------------------------------------------------------------
    // Intern: CSV schreiben mit Session-Ordner
    // --------------------------------------------------------------------
    private fun appendCsv(fileName: String, header: String, line: String) {
        synchronized(ioLock) {
            // Sicherstellen dass Session gestartet wurde
            if (sessionSubdir.isEmpty()) {
                startNewSession()
            }

            if (Build.VERSION.SDK_INT >= 29) {
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

                // Uri pro File cachen: verhindert, dass bei schnellen Writes / MediaStore-Latenzen
                // versehentlich mehrere Dateien mit ähnlichem Namen entstehen.
                val targetUri = uriCache[fileName] ?: run {
                    val existing = findExistingMediaStore(collection, fileName)
                    val created = existing ?: createNewMediaStore(collection, fileName)
                    if (created != null) {
                        uriCache[fileName] = created
                    }
                    created
                }

                targetUri?.let { uri ->
                    val needHeader = if (headerWritten.contains(fileName)) {
                        false
                    } else {
                        // Wenn wir das File zum ersten Mal in dieser Session anfassen:
                        // Header nur schreiben, wenn es wirklich leer ist.
                        isTrulyEmptyMediaStoreFile(uri)
                    }

                    context.contentResolver.openOutputStream(uri, "wa")?.use { os ->
                        OutputStreamWriter(os, Charsets.UTF_8).use { w ->
                            if (needHeader) w.write(header)
                            w.write(line)
                            w.flush()
                        }
                    }

                    headerWritten.add(fileName)
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    sessionSubdir
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)

                val needHeader = if (headerWritten.contains(fileName)) {
                    false
                } else {
                    !file.exists() || file.length() == 0L
                }

                FileOutputStream(file, true).use { fos ->
                    OutputStreamWriter(fos, Charsets.UTF_8).use { w ->
                        if (needHeader) w.write(header)
                        w.write(line)
                        w.flush()
                    }
                }

                headerWritten.add(fileName)
            }
        }
    }

    private fun findExistingMediaStore(collection: Uri, fileName: String): Uri? {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH
        )

        val rel1 = "Download/$sessionSubdir/"
        val rel2 = "Download/$sessionSubdir"
        val sel = "${MediaStore.Downloads.DISPLAY_NAME}=? AND (${MediaStore.Downloads.RELATIVE_PATH}=? OR ${MediaStore.Downloads.RELATIVE_PATH}=?)"
        val args = arrayOf(fileName, rel1, rel2)

        context.contentResolver.query(collection, projection, sel, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    private fun createNewMediaStore(collection: Uri, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeCsv)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$sessionSubdir/")
        }
        return context.contentResolver.insert(collection, values)
    }

    private fun isTrulyEmptyMediaStoreFile(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                ins.read() == -1
            } ?: true
        } catch (e: Exception) {
            isEmptyMediaStoreFile(uri)
        }
    }

    private fun isEmptyMediaStoreFile(uri: Uri): Boolean {

        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0) == 0L
        }
        return true
    }
}