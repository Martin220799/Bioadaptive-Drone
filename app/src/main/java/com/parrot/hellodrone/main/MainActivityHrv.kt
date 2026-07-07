package com.parrot.hellodrone.main

import android.os.CountDownTimer
import android.util.Log
import kotlin.math.pow
import kotlin.math.sqrt
import com.parrot.hellodrone.MainActivity
import com.parrot.hellodrone.R
import android.widget.*

    internal fun MainActivity.onToggleHrv() {
        if (!measuring) startHrvMeasurement() else stopHrvMeasurement(userStopped = true)
    }

    internal fun MainActivity.startHrvMeasurement() {
        synchronized(rrBuffer) { rrBuffer.clear() }
        synchronized(hrSamples) { hrSamples.clear() }
        hrProcessor.resetQc()

        val mode = currentHrvMode()

        // Neue Session immer beim PreFlight-HRV starten
        if (mode == "PreFlight") {
            // Jede PreFlight-Messung = Start einer neuen Session/Probanden
            //csvLogger.startNewSession()
            //csvLogger.logEvent("SESSION_START PreFlight HRV")
            startNewSessionWithTimebase("PreFlight HRV")
        }

        polar.startScan()

        measuring = true
        btnHrvToggle.text = getString(R.string.btn_stop_hrv)

        Toast.makeText(this, "HRV-Measurement started ($mode, ${measureSeconds}s)", Toast.LENGTH_SHORT).show()

        hrvTimerTxt.text = formatCountdown(measureSeconds)
        timer?.cancel()
        timer = object : CountDownTimer(measureSeconds * 1000L, 1000L) {
            override fun onTick(ms: Long) {
                val sec = (ms / 1000).toInt()
                hrvTimerTxt.text = formatCountdown(sec)
            }
            override fun onFinish() {
                stopHrvMeasurement(userStopped = false)
            }
        }.start()
    }

    internal fun MainActivity.stopHrvMeasurement(userStopped: Boolean) {
        timer?.cancel()
        measuring = false
        btnHrvToggle.text = getString(R.string.btn_start_hrv)
        hrvTimerTxt.text = getString(R.string.hrv_timer_idle)

        val mode = currentHrvMode()
        val rrCopy: List<Int>
        val hrCopy: List<Int>
        synchronized(rrBuffer) { rrCopy = rrBuffer.toList() }
        synchronized(hrSamples) { hrCopy = hrSamples.toList() }

        // Reset vor Filter für saubere HRV-Berechnung
        hrProcessor.resetRrFilterState()
        // Artefaktgefilterte RR-Intervalle für HRV-Berechnung verwenden
        val rrFiltered = hrProcessor.filterRrForHrv(rrCopy)
        val rrCollected = rrCopy.size
        val rrValid = rrFiltered.size
        val rmssd = computeRmssd(rrFiltered)
        val sdnn = computeSdnn(rrFiltered)
        val meanHr = if (hrCopy.isNotEmpty()) hrCopy.sum() / hrCopy.size else -1
        val minHr = if (hrCopy.isNotEmpty()) hrCopy.minOrNull() ?: -1 else -1
        val maxHr = if (hrCopy.isNotEmpty()) hrCopy.maxOrNull() ?: -1 else -1

        // Baseline & rmssdPre setzen, wenn PreFlight
        if (mode == "PreFlight") {
            if (meanHr > 0) {
                baselineHr = meanHr

                if (meanHr < 40 || meanHr > 120) {
                    Toast.makeText(
                        this,
                        "Unusual Resting-HR ($meanHr bpm) - please verify",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.w("HRV", "Baseline HR out of normal area: $meanHr bpm")
                }
            } else {
                Toast.makeText(
                    this,
                    "Baseline invalid - please repeat",
                    Toast.LENGTH_LONG
                ).show()
            }

            if (rmssd != null) rmssdPre = rmssd
            if (sdnn != null) sdnnPre = sdnn

        }

        val seconds = measureSeconds
        if (rmssd != null) {
            hrvResultTxt.text = getString(R.string.hrv_result_format,
                rmssd, mode, seconds, rrFiltered.size)
            csvLogger.logHrvResult(
                mode = mode,
                durationSec = seconds,
                rmssdMs = rmssd,
                sdnnMs = sdnn,
                rrCollected = rrCollected,
                rrValid = rrValid,
                meanHr = meanHr.coerceAtLeast(0),
                minHr = minHr,
                maxHr = maxHr,
                artifactThreshold = hrProcessor.getArtifactThreshold(),
                hrWindowSize = hrProcessor.getHrWindowSize()
            )
            Toast.makeText(this, "HRV saved (CSV).", Toast.LENGTH_SHORT).show()

            // Speichere für Profil-Erstellung
            lastHrvDurationSec = seconds
            lastHrvRrCollected = rrCollected
            lastHrvRrValid = rrValid
        } else {
            hrvResultTxt.text = getString(R.string.hrv_result_no_data)
            Toast.makeText(this, "Insufficient RR-Data for RMSSD.", Toast.LENGTH_SHORT).show()
        }

        if (userStopped) Log.i("HRV", "User stopped - Mode=$mode, nRR=${rrCopy.size}, meanHR=$meanHr")
        else Log.i("HRV", "Ended automatically - Mode=$mode, nRR=${rrCopy.size}, meanHR=$meanHr")
    }

    internal fun MainActivity.currentHrvMode(): String = if (rbPostFlight.isChecked) "PostFlight" else "PreFlight"
    internal fun MainActivity.formatCountdown(sec: Int): String = "Timer: ${sec}s"

    internal fun MainActivity.computeRmssd(rrMs: List<Int>): Double? {
        if (rrMs.size < 3) return null
        var sumSq = 0.0
        var n = 0
        for (i in 0 until rrMs.size - 1) {
            val diff = (rrMs[i + 1] - rrMs[i]).toDouble()
            sumSq += diff.pow(2.0)
            n++
        }
        if (n == 0) return null
        return sqrt(sumSq / n)
    }

    internal fun MainActivity.computeSdnn(rrMs: List<Int>): Double? {
        if (rrMs.size < 2) return null
        val mean = rrMs.average()
        val variance = rrMs.map { (it - mean).toDouble().pow(2) }.average()
        return sqrt(variance)
    }
