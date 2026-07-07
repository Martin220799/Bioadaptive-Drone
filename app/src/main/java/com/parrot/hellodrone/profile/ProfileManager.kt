package com.parrot.hellodrone.profile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.*
import android.content.ContentUris  // FIX #2: Import hinzugefügt
import org.json.JSONException

/**
 * ProfileManager: Verwaltung von Probanden-Profilen als JSON-Dateien
 *
 * Speicherort konsistent mit CsvLogger:
 * - Android 10+: MediaStore Downloads/AdaptiveDrone/profiles/
 * - Android <10: /storage/emulated/0/Download/AdaptiveDrone/profiles/
 *
 * Dateinamen: {participantId}_profile.json (z.B. "P01_profile.json")
 */
class ProfileManager(private val context: Context) {

    private val baseDir = "AdaptiveDrone"
    private val profilesSubdir = "profiles"
    private val mimeJson = "application/json"

    companion object {
        private const val TAG = "ProfileManager"
    }

    private fun profilesRelativePathNoSlash(): String {
        return "Download/$baseDir/$profilesSubdir"
    }

    private fun profilesRelativePathForInsert(): String {
        return profilesRelativePathNoSlash() + "/"
    }

    /**
     * Speichert ein Probanden-Profil als JSON
     *
     * @param profile Das zu speichernde Profil
     * @return true bei Erfolg, false bei Fehler
     */
    fun saveProfile(profile: ParticipantProfile): Boolean {
        if (!profile.isValid()) {
            Log.e(TAG, "Profil-Validierung fehlgeschlagen: ${profile.participantId}")
            return false
        }

        val fileName = "${profile.participantId}_profile.json"
        val jsonString = profileToJson(profile)

        return try {
            val success = if (Build.VERSION.SDK_INT >= 29) {
                saveProfileMediaStore(fileName, jsonString)
            } else {
                saveProfileLegacy(fileName, jsonString)
            }

            if (success) {
                Log.i(TAG, "Profil gespeichert: $fileName")
            } else {
                Log.e(TAG, "Profil konnte nicht gespeichert werden: $fileName")
            }

            success  // Korrekter Return-Wert
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Speichern von $fileName: ${e.message}", e)
            false
        }
    }

    /**
     * Lädt ein Probanden-Profil anhand der ID
     *
     * @param participantId Die Probanden-ID (z.B. "P01")
     * @return Das geladene Profil oder null bei Fehler
     */
    fun loadProfile(participantId: String): ParticipantProfile? {
        val fileName = "${participantId}_profile.json"

        return try {
            val jsonString = if (Build.VERSION.SDK_INT >= 29) {
                loadProfileMediaStore(fileName)
            } else {
                loadProfileLegacy(fileName)
            }

            jsonString?.let { json ->
                val profile = try {
                    jsonToProfile(json)
                } catch (e: JSONException) {
                    Log.e(TAG, "Korruptes JSON in $fileName: ${e.message}")
                    return null
                } catch (e: NumberFormatException) {
                    Log.e(TAG, "Ungültige Zahlen in $fileName: ${e.message}")
                    return null
                } catch (e: Exception) {
                    Log.e(TAG, "Unerwarteter Fehler bei $fileName: ${e.message}", e)
                    return null
                }

                if (profile.isValid()) {
                    Log.i(TAG, "Profil geladen: $fileName")
                    profile
                } else {
                    Log.e(TAG, "Geladenes Profil ist ungültig: $fileName - ${profile.participantId}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Laden von $fileName: ${e.message}", e)
            null
        }
    }

    /**
     * Listet alle verfügbaren Probanden-IDs auf
     *
     * @return Liste der IDs (z.B. ["P01", "P02", "P03"])
     */
    fun listAvailableProfiles(): List<String> {
        return try {
            val fileNames = if (Build.VERSION.SDK_INT >= 29) {
                listProfilesMediaStore()
            } else {
                listProfilesLegacy()
            }

            // Extrahiere IDs aus Dateinamen ("P01_profile.json" -> "P01")
            fileNames.mapNotNull { fileName ->
                if (fileName.endsWith("_profile.json")) {
                    fileName.removeSuffix("_profile.json")
                } else {
                    null
                }
            }.sorted()
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Listen der Profile: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Löscht ein Probanden-Profil
     *
     * @param participantId Die zu löschende Probanden-ID
     * @return true bei Erfolg
     */
    fun deleteProfile(participantId: String): Boolean {
        val fileName = "${participantId}_profile.json"

        return try {
            val success = if (Build.VERSION.SDK_INT >= 29) {
                deleteProfileMediaStore(fileName)
            } else {
                deleteProfileLegacy(fileName)
            }

            if (success) {
                Log.i(TAG, "Profil gelöscht: $fileName")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Löschen von $fileName: ${e.message}", e)
            false
        }
    }

    // ==================================
    // JSON Serialisierung / Deserialisierung (manuell mit org.json)
    // ==================================

    private fun profileToJson(profile: ParticipantProfile): String {
        return JSONObject().apply {
            put("participantId", profile.participantId)
            put("age", profile.age)
            put("gender", profile.gender)
            put("fitnessLevel", profile.fitnessLevel)
            put("calibrationTimestamp", profile.calibrationTimestamp)
            put("hrRest", profile.hrRest)
            put("hrWalk", profile.hrWalk)
            put("deltaWalk", profile.deltaWalk)

            // NEU: Kalibrierungs-QC
            put("calibrationDurationSec", profile.calibrationDurationSec)
            put("calibrationValidRatio", profile.calibrationValidRatio)
            put("calibrationRrCount", profile.calibrationRrCount)

            put("rmssdBaseline", profile.rmssdBaseline)
            put("sdnnBaseline", profile.sdnnBaseline)

            // NEU: HRV-QC
            put("hrvDurationSec", profile.hrvDurationSec)
            put("hrvRrCollected", profile.hrvRrCollected)
            put("hrvRrValid", profile.hrvRrValid)
            put("hrvValidRatio", profile.hrvValidRatio)

            put("thresholdGreenYellow", profile.thresholdGreenYellow)
            put("thresholdYellowRed", profile.thresholdYellowRed)

            // NEU: System-Parameter
            put("hrWindowSize", profile.hrWindowSize)
            put("rrArtifactThreshold", profile.rrArtifactThreshold)

            put("notes", profile.notes)
            put("createdBy", profile.createdBy)
        }.toString(2)
    }

    /**
     * FIX #6: Robusteres JSON-Parsing mit expliziten has()-Checks
     */
    private fun jsonToProfile(jsonString: String): ParticipantProfile {
        val json = JSONObject(jsonString)
        return ParticipantProfile(
            participantId = json.getString("participantId"),
            age = if (json.has("age") && !json.isNull("age")) {
                val ageVal = json.getInt("age")
                if (ageVal in 1..120) ageVal else null
            } else null,

            gender = if (json.has("gender") && !json.isNull("gender")) {
                val genderVal = json.getString("gender")
                if (genderVal in listOf("M", "F", "D", "N/A")) genderVal else null
            } else null,

            fitnessLevel = json.optString("fitnessLevel").takeIf { it.isNotBlank() },
            calibrationTimestamp = json.getString("calibrationTimestamp"),
            hrRest = json.getInt("hrRest"),
            hrWalk = json.getInt("hrWalk"),
            deltaWalk = json.getInt("deltaWalk"),

            // NEU: Kalibrierungs-QC (mit Fallback für alte Profile)
            calibrationDurationSec = json.optInt("calibrationDurationSec", 90),
            calibrationValidRatio = json.optDouble("calibrationValidRatio", 0.95).toFloat(),
            calibrationRrCount = json.optInt("calibrationRrCount", 90),

            rmssdBaseline = json.getDouble("rmssdBaseline"),
            sdnnBaseline = if (json.has("sdnnBaseline") && !json.isNull("sdnnBaseline")) {
                val sdnnVal = json.getDouble("sdnnBaseline")
                if (!sdnnVal.isNaN() && sdnnVal > 0) sdnnVal else null
            } else null,

            // NEU: HRV-QC (mit Fallback für alte Profile)
            hrvDurationSec = json.optInt("hrvDurationSec", 180),
            hrvRrCollected = json.optInt("hrvRrCollected", 180),
            hrvRrValid = json.optInt("hrvRrValid", 170),
            hrvValidRatio = json.optDouble("hrvValidRatio", 0.95).toFloat(),

            thresholdGreenYellow = json.getInt("thresholdGreenYellow"),
            thresholdYellowRed = json.getInt("thresholdYellowRed"),

            // NEU: System-Parameter (mit Fallback für alte Profile)
            hrWindowSize = json.optInt("hrWindowSize", 4),
            rrArtifactThreshold = json.optDouble("rrArtifactThreshold", 0.2),

            notes = json.optString("notes").takeIf { it.isNotBlank() },
            createdBy = json.optString("createdBy", "FlyingPacer")
        )
    }

    // ===========================
    // Android 10+ (API 29+): MediaStore-basiertes Speichern
    // ===========================

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveProfileMediaStore(fileName: String, content: String): Boolean {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = profilesRelativePathForInsert()

        // Prüfe ob Datei bereits existiert
        val existingUri = findFileMediaStore(collection, fileName, relativePath)
        val targetUri = existingUri ?: createFileMediaStore(collection, fileName, relativePath)

        return targetUri?.let { uri ->
            context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
            }
            true
        } ?: false
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun loadProfileMediaStore(fileName: String): String? {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = profilesRelativePathForInsert()
        val uri = findFileMediaStore(collection, fileName, relativePath) ?: return null

        return context.contentResolver.openInputStream(uri)?.use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { it.readText() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listProfilesMediaStore(): List<String> {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val relNoSlash = profilesRelativePathNoSlash()
        val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)

        // KRITISCH: LIKE statt = für Slash-Toleranz!
        // Findet sowohl "…/profiles" als auch "…/profiles/"
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("$relNoSlash%", "%_profile.json")

        val fileNames = mutableListOf<String>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                fileNames.add(cursor.getString(nameColumn))
            }
        }

        Log.d(TAG, "Found ${fileNames.size} profiles in MediaStore")
        return fileNames
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteProfileMediaStore(fileName: String): Boolean {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = profilesRelativePathForInsert()
        val uri = findFileMediaStore(collection, fileName, relativePath) ?: return false

        val deleted = context.contentResolver.delete(uri, null, null)
        return deleted > 0
    }

    private fun findFileMediaStore(collection: Uri, fileName: String, relativePath: String): Uri? {
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)

        val relNoSlash = profilesRelativePathNoSlash()

        // KRITISCH: LIKE statt = für RELATIVE_PATH!
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(fileName, "$relNoSlash%")

        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                Log.d(TAG, "Found file in MediaStore: $fileName")
                return ContentUris.withAppendedId(collection, id)
            }
        }

        Log.w(TAG, "File NOT found in MediaStore: $fileName")
        return null
    }

    private fun createFileMediaStore(collection: Uri, fileName: String, relativePath: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeJson)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
        }
        return context.contentResolver.insert(collection, values)
    }

    // ========================
    // Android <10 (API <29): Legacy File-basiertes Speichern
    // =========================

    /**
     * FIX #3: Explizit UTF-8 beim Schreiben verwenden
     */
    private fun saveProfileLegacy(fileName: String, content: String): Boolean {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$baseDir/$profilesSubdir"
        )
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, fileName)
        // FIX #3: OutputStreamWriter mit UTF-8 statt FileWriter (System-Default)
        OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8).use { writer ->
            writer.write(content)
            writer.flush()
        }
        return true
    }

    private fun loadProfileLegacy(fileName: String): String? {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$baseDir/$profilesSubdir/$fileName"
        )
        return if (file.exists()) {
            file.readText(Charsets.UTF_8)
        } else {
            null
        }
    }

    private fun listProfilesLegacy(): List<String> {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$baseDir/$profilesSubdir"
        )
        return if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.map { it.name } ?: emptyList()
        } else {
            emptyList()
        }
    }

    private fun deleteProfileLegacy(fileName: String): Boolean {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$baseDir/$profilesSubdir/$fileName"
        )
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }
}