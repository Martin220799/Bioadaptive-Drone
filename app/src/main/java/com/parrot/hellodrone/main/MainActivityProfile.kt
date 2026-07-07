package com.parrot.hellodrone.main

import android.util.Log
import android.os.Build
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.annotation.RequiresApi
import java.io.File
import com.parrot.hellodrone.profile.ParticipantProfile
import com.parrot.hellodrone.MainActivity
import com.parrot.hellodrone.R
import android.widget.*

    internal fun MainActivity.setupProfileSpinner(selectId: String? = null) {  // Optimierung TODO if time
        val availableProfiles = profileManager.listAvailableProfiles().toMutableList()

        // "Neues Profil" als erste Option hinzufügen
        availableProfiles.add(0, getString(R.string.profile_new))

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            availableProfiles
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        profileSpinner.adapter = adapter

        // Optimierung TODO if time: Optional direkt ID auswählen (verhindert UI-Springen)
        val position = if (selectId != null) {
            availableProfiles.indexOf(selectId).takeIf { it >= 0 } ?: 0
        } else {
            0
        }
        profileSpinner.setSelection(position)
    }

    /**
     * Lädt das im Spinner ausgewählte Profil
     */
    internal fun MainActivity.onLoadProfile() {
        val selectedItem = profileSpinner.selectedItem?.toString() ?: return

        // "Neues Profil" kann nicht geladen werden
        if (selectedItem == getString(R.string.profile_new)) {
            Toast.makeText(this, "Please select a profile to load", Toast.LENGTH_SHORT).show()
            return
        }

        // Profil laden
        val profile = profileManager.loadProfile(selectedItem)

        if (profile != null) {
            currentProfile = profile

            // ZoneManager mit gespeicherten Schwellwerten konfigurieren (WICHTIG: reproduzierbar!)
            zoneManager.setThresholds(
                hrRest = profile.hrRest,
                hrWalk = profile.hrWalk,
                rmssdPre = profile.rmssdBaseline,
                thresholdYellow = profile.thresholdGreenYellow,
                thresholdRed = profile.thresholdYellowRed
            )

            // Baseline für Debug-Anzeige setzen
            baselineHr = profile.hrRest
            rmssdPre = profile.rmssdBaseline
            walkCalibrationMeanHr = profile.hrWalk     // <<< FIX: fehlte!
            sdnnPre = profile.sdnnBaseline             // optional, aber konsistent
            // UI aktualisieren
            profileStatusTxt.text = getString(R.string.profile_loaded_format, profile.participantId)

            Toast.makeText(
                this,
                "Profile ${profile.participantId} loaded\nThresholds: ${profile.thresholdGreenYellow}/${profile.thresholdYellowRed} bpm",
                Toast.LENGTH_LONG
            ).show()

            Log.i("PROFILE", "Profil loaded: ${profile.participantId}, HR: ${profile.hrRest}->${profile.hrWalk}")
        } else {
            Toast.makeText(this, "Error while loading profile", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Speichert ein neues Profil mit aktuellen Kalibrierungsdaten
     */
    internal fun MainActivity.onSaveProfile() {
        // Prüfe ob Kalibrierung vorhanden
        val hrRest = baselineHr
        val hrWalk = walkCalibrationMeanHr

        val rmssd = rmssdPre
        val sdnn = sdnnPre  // Auch lokal
        val thYellow = zoneManager.getThresholdYellow()
        val thRed = zoneManager.getThresholdRed()

        if (hrRest == null || hrWalk == null || rmssd == null || thYellow == null || thRed == null) {
            Toast.makeText(
                this,
                "Please dp Pre-HRV AND Calibration first!",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Erfasse Kalibrierungs-QC (HIER!)
        val calibrationDuration = calibrationDurationSec  // Aus deiner Klassen-Variable
        //val calibrationQc = hrProcessor.getQcStatus()
        //val calibrationValidRatio = calibrationQc.validRatioOverall
        val calibrationValidRatio = calibrationValidRatioStored
        val calibrationRrCount = calibrationRrCounter  // Aus deiner Klassen-Variable

        // Erfasse HRV-QC (wird beim letzten HRV gesetzt)
        val hrvDuration = lastHrvDurationSec  // Aus deiner Klassen-Variable
        val hrvRrCollected = lastHrvRrCollected  // Aus deiner Klassen-Variable
        val hrvRrValid = lastHrvRrValid  // Aus deiner Klassen-Variable
        val hrvValidRatio = if (hrvRrCollected > 0) {
            hrvRrValid.toFloat() / hrvRrCollected
        } else 0f

        // Probanden-ID abfragen
        val input = EditText(this).apply {
            hint = getString(R.string.profile_id_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Save Profile")
            .setMessage("Enter User-ID (e.g. P01):")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val participantId = input.text.toString().trim()

                if (participantId.isBlank()) {
                    Toast.makeText(this, "Invalid ID", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Profil erstellen
                val profile = ParticipantProfile(
                    participantId = participantId,
                    calibrationTimestamp = ParticipantProfile.getCurrentTimestamp(),
                    hrRest = hrRest,
                    hrWalk = hrWalk,
                    deltaWalk = hrWalk - hrRest,

                    // Kalibrierungs-QC
                    calibrationDurationSec = calibrationDuration,
                    calibrationValidRatio = calibrationValidRatio,
                    calibrationRrCount = calibrationRrCount,

                    rmssdBaseline = rmssd,
                    sdnnBaseline = sdnn,  // Falls vorhanden

                    // HRV-QC
                    hrvDurationSec = hrvDuration,
                    hrvRrCollected = hrvRrCollected,
                    hrvRrValid = hrvRrValid,
                    hrvValidRatio = hrvValidRatio,

                    thresholdGreenYellow = thYellow,
                    thresholdYellowRed = thRed,

                    // System-Parameter
                    hrWindowSize = hrProcessor.getHrWindowSize(),
                    rrArtifactThreshold = hrProcessor.getArtifactThreshold()
                )

                // Speichern
                if (profileManager.saveProfile(profile)) {
                    currentProfile = profile
                    profileStatusTxt.text = getString(R.string.profile_loaded_format, participantId)

                    // Spinner aktualisieren und direkt neue ID selektieren
                    setupProfileSpinner(selectId = participantId)  // Optimierung TODO if time

                    // Gespeichertes Profil im Spinner auswählen (SICHER)
                    (profileSpinner.adapter as? ArrayAdapter<*>)?.let { adapter ->
                        val position = (0 until adapter.count).firstOrNull {
                            adapter.getItem(it) == participantId
                        }
                        position?.let { profileSpinner.setSelection(it) }
                    }

                    Toast.makeText(
                        this,
                        "Profile $participantId saved successfully",
                        Toast.LENGTH_LONG
                    ).show()

                    Log.i("PROFILE", "Profile saved: $participantId")
                } else {
                    Toast.makeText(this, "Error while saving", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun MainActivity.syncProfilesWithMediaStore() {
        Thread {
            val profilesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AdaptiveDrone/profiles"
            )

            if (!profilesDir.exists() || !profilesDir.isDirectory) {
                Log.w("MediaStoreSync", "Profiles directory not found: ${profilesDir.absolutePath}")
                return@Thread
            }

            val jsonFiles = profilesDir.listFiles { file ->
                file.extension == "json" && file.name.endsWith("_profile.json")
            }

            if (jsonFiles == null || jsonFiles.isEmpty()) {
                Log.w("MediaStoreSync", "No profile files found in directory")
                return@Thread
            }

            Log.d("MediaStoreSync", "Found ${jsonFiles.size} profile files - triggering MediaStore scan")

            MediaScannerConnection.scanFile(
                this,
                jsonFiles.map { it.absolutePath }.toTypedArray(),
                arrayOf("application/json"),
                { path, uri ->
                    Log.d("MediaStoreSync", "Scanned: $path -> $uri")
                }
            )

            // UI aktualisieren nach Scan (500ms Verzögerung)
            Thread.sleep(500)
            runOnUiThread {
                val profiles = profileManager.listAvailableProfiles()
                Log.d("MediaStoreSync", "After sync: ${profiles.size} profiles visible")

                setupProfileSpinner()
            }

        }.start()
    }
