package com.parrot.hellodrone.main

import android.util.Log
import com.parrot.hellodrone.bio.ZoneManager
import com.parrot.hellodrone.MainActivity
import com.parrot.hellodrone.R
import android.widget.*

    internal fun MainActivity.onToggleCalibWalk() {
        if (!isCalibratingWalk) {
            if (baselineHr == null) {
                Toast.makeText(
                    this,
                    "Please measure PreFlight-HRV first (Resting-HR).",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            synchronized(calibSamples) { calibSamples.clear() }
            isCalibratingWalk = true
            btnCalibWalk.text = getString(R.string.btn_stop_calib_walk)

            // Tracking starten
            isCalibrating = true
            calibrationRrCounter = 0
            calibrationStartTime = System.currentTimeMillis()
            synchronized(calibRrBuffer) { calibRrBuffer.clear() }
            hrProcessor.resetRrFilterState()

            Toast.makeText(
                this,
                "Calibration started. Please move brisk for 3min",
                Toast.LENGTH_LONG
            ).show()

            polar.startScan()

        } else {
            isCalibratingWalk = false
            btnCalibWalk.text = getString(R.string.btn_start_calib_walk)

            // Tracking stoppen
            isCalibrating = false
            calibrationDurationSec = ((System.currentTimeMillis() - calibrationStartTime) / 1000).toInt()

            val samples: List<MainActivity.CalibSample> = synchronized(calibSamples) {
                calibSamples.toList()
            }

            if (samples.size < 10) {
                Toast.makeText(
                    this,
                    "Insufficient HR-Data for Calibration.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val windowMs = 180_000L
            val lastTime = samples.last().timestampMs
            val fromTime = lastTime - windowMs
            val window = samples.filter { it.timestampMs >= fromTime }

            if (window.isEmpty()) {
                Toast.makeText(
                    this,
                    "No Calibration Data found in time.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val meanWalkHr = window.map { it.hrBpm }.average().toInt()
            walkCalibrationMeanHr = meanWalkHr
            val nSamples = window.size

            // 2. RR-QC BERECHNEN

            val calibRrCopy = synchronized(calibRrBuffer) { calibRrBuffer.toList() }
            if (calibRrCopy.isNotEmpty()) {
                hrProcessor.resetRrFilterState()  // Reset vor Filter!
                val calibRrValid = hrProcessor.filterRrForHrv(calibRrCopy)
                calibrationValidRatioStored = calibRrValid.size.toFloat() / calibRrCopy.size
                Log.i("CALIB", "QC: ${calibRrCopy.size} RR collected, ${calibRrValid.size} valid (${(calibrationValidRatioStored*100).toInt()}%)")
            } else {
                calibrationValidRatioStored = 0f
                Log.w("CALIB", "No RR-Data collected during calibration!")
            }

            val hrRest = baselineHr
            if (hrRest != null) {
                // Validierung Δwalk
                val deltaWalk = meanWalkHr - hrRest
                if (deltaWalk < 10) {
                    Toast.makeText(
                        this,
                        "Delta - Walk zu low ($deltaWalk bpm). Pls move faster and repeat.",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.w("CALIB", "Delta Walk=$deltaWalk < 10 bpm (hrRest=$hrRest, hrWalk=$meanWalkHr)")
                }

                zoneManager.configureFromCalibration(
                    hrRest = hrRest,
                    hrWalk = meanWalkHr,
                    rmssdPre = rmssdPre
                )

                csvLogger.logCalibrationResult(
                    hrRest = hrRest,
                    hrWalk = meanWalkHr,
                    rmssdPre = rmssdPre,
                    nSamples = nSamples,
                    thresholdYellow = zoneManager.getThresholdYellow(),
                    thresholdRed = zoneManager.getThresholdRed()
                )

                Toast.makeText(
                    this,
                    "Calibration set: Rest-HR=$hrRest, Walk-HR≈$meanWalkHr bpm.",
                    Toast.LENGTH_LONG
                ).show()
                Log.i("CALIB",
                    "Configured calibration: hrRest=$hrRest, hrWalk=$meanWalkHr, rmssdPre=$rmssdPre")
            } else {
                Toast.makeText(
                    this,
                    "Calibration: Walk-HR≈$meanWalkHr bpm (without Ruhe-HR, ZoneManager not configured).",
                    Toast.LENGTH_LONG
                ).show()
                Log.w("CALIB", "Walk-HR=$meanWalkHr, but baselineHr=null - ZoneManager not configured.")
            }
        }
    }
