package com.parrot.hellodrone.main

import android.util.Log
import android.os.Build
import android.os.VibrationEffect
import android.media.ToneGenerator
import com.parrot.hellodrone.bio.Zone
import com.parrot.hellodrone.MainActivity

    internal fun MainActivity.handleRedAudioAlert(zone: Zone, flightLogging: Boolean, nowMs: Long) {
        if (!flightLogging) {
            redEnteredAtMs = -1L
            lastRedAlertAtMs = -1L
            return
        }

        if (zone != Zone.RED) {
            redEnteredAtMs = -1L
            lastRedAlertAtMs = -1L
            return
        }

        // Eintritt in RED merken (nur beim ersten Tick)
        if (redEnteredAtMs < 0L) {
            redEnteredAtMs = nowMs
            lastRedAlertAtMs = -1L
            return
        }

        val redDurationMs = nowMs - redEnteredAtMs

        // Erst nach 45s in RED scharf
        if (redDurationMs < redAlertStartAfterMs) return

        // Dann alle 10s
        if (lastRedAlertAtMs < 0L || nowMs - lastRedAlertAtMs >= redAlertRepeatMs) {
            triggerRedZoneAlert()
            lastRedAlertAtMs = nowMs
        }
    }

    internal fun MainActivity.triggerRedZoneAlert() {
        redTone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)

        // Optional: Vibration
        if (isVibratorInitialized && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        }

        Log.i("BIOFEEDBACK", "RED alert beep")
    }
