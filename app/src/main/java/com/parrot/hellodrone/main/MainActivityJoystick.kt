package com.parrot.hellodrone.main

import android.util.Log
import android.view.View
import android.view.MotionEvent
import androidx.core.graphics.toColorInt
import com.parrot.hellodrone.flight.ManualDriver
import com.parrot.hellodrone.config.PrototypeType
import com.parrot.hellodrone.config.PrototypeConfig
import com.parrot.hellodrone.MainActivity

internal fun MainActivity.normalizeJoystickInput(x: Float, y: Float, w: Int, h: Int): Pair<Float, Float> {
    val cx = w / 2f
    val cy = h / 2f
    val dx = ((x - cx) / cx).coerceIn(-1f, 1f)
    val dy = ((y - cy) / cy).coerceIn(-1f, 1f)
    return dx to dy
}

internal fun MainActivity.handleLeftJoystick(event: MotionEvent, w: Int, h: Int) {
    val wasOverride = manualOverrideActive
    val action = event.actionMasked
    val actionIdx = event.actionIndex

    when (action) {
        MotionEvent.ACTION_DOWN -> {
            leftPointerId = event.getPointerId(actionIdx)
            leftJoystickActive = true

            val (dx, dy) = normalizeJoystickInput(event.getX(actionIdx), event.getY(actionIdx), w, h)
            // Invert Y: oben = positive vertical
            jsLeftX = dx      // yaw
            jsLeftY = -dy     // vertical
        }

        MotionEvent.ACTION_POINTER_DOWN -> {
            // Falls aus irgendeinem Grund der "erste" Pointer nicht sauber kommt:
            if (!leftJoystickActive) {
                leftPointerId = event.getPointerId(actionIdx)
                leftJoystickActive = true

                val (dx, dy) = normalizeJoystickInput(event.getX(actionIdx), event.getY(actionIdx), w, h)
                jsLeftX = dx
                jsLeftY = -dy
            }
        }

        MotionEvent.ACTION_MOVE -> {
            if (leftJoystickActive && leftPointerId != MotionEvent.INVALID_POINTER_ID) {
                val idx = event.findPointerIndex(leftPointerId)
                if (idx >= 0) {
                    val (dx, dy) = normalizeJoystickInput(event.getX(idx), event.getY(idx), w, h)
                    jsLeftX = dx
                    jsLeftY = -dy
                } else {
                    // Pointer verloren (z.B. durch Multi-Touch Reordering) -> reset
                    leftJoystickActive = false
                    leftPointerId = MotionEvent.INVALID_POINTER_ID
                    jsLeftX = 0f
                    jsLeftY = 0f
                }
            }
        }

        MotionEvent.ACTION_POINTER_UP -> {
            val pid = event.getPointerId(actionIdx)
            if (pid == leftPointerId) {
                leftJoystickActive = false
                leftPointerId = MotionEvent.INVALID_POINTER_ID
                jsLeftX = 0f
                jsLeftY = 0f
            }
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            leftJoystickActive = false
            leftPointerId = MotionEvent.INVALID_POINTER_ID
            jsLeftX = 0f
            jsLeftY = 0f
        }
    }

    // Gemeinsame Aktualisierung nach dem when:
    manualOverrideActive = leftJoystickActive || rightJoystickActive

    if (smoothRejoinEnabled) {
        if (!wasOverride && manualOverrideActive) {
            // Override beginnt
            frozenVSet = speedController.getLastOutput()
            Log.i("JOYSTICK", "Manual override ON (frozenVSet=$frozenVSet)")
        } else if (wasOverride && !manualOverrideActive) {
            // Override endet
            frozenVSet?.let {
                speedController.setOutput(it)
                Log.i("JOYSTICK", "Manual override OFF - resume at vSet=$it")
                frozenVSet = null
            }
        }
    }

    // Thumb-Position aktualisieren (Y wieder invertieren, damit oben = oben)
    updateThumbPosition(joystickLeftThumb, joystickLeftContainer, jsLeftX, -jsLeftY)

    // Override-Status-Text aktualisieren
    updateOverrideStatusUI()

    sendJoystickCommandsToDrone()
}

internal fun MainActivity.handleRightJoystick(event: MotionEvent, w: Int, h: Int) {
    val wasOverride = manualOverrideActive
    val action = event.actionMasked
    val actionIdx = event.actionIndex

    when (action) {
        MotionEvent.ACTION_DOWN -> {
            rightPointerId = event.getPointerId(actionIdx)
            rightJoystickActive = true

            val (dx, dy) = normalizeJoystickInput(event.getX(actionIdx), event.getY(actionIdx), w, h)
            // Invert Y: oben = vorwärts (positive Pitch)
            jsRightX = dx   // roll
            jsRightY = -dy  // pitch
        }

        MotionEvent.ACTION_POINTER_DOWN -> {
            if (!rightJoystickActive) {
                rightPointerId = event.getPointerId(actionIdx)
                rightJoystickActive = true

                val (dx, dy) = normalizeJoystickInput(event.getX(actionIdx), event.getY(actionIdx), w, h)
                jsRightX = dx
                jsRightY = -dy
            }
        }

        MotionEvent.ACTION_MOVE -> {
            if (rightJoystickActive && rightPointerId != MotionEvent.INVALID_POINTER_ID) {
                val idx = event.findPointerIndex(rightPointerId)
                if (idx >= 0) {
                    val (dx, dy) = normalizeJoystickInput(event.getX(idx), event.getY(idx), w, h)
                    jsRightX = dx
                    jsRightY = -dy
                } else {
                    rightJoystickActive = false
                    rightPointerId = MotionEvent.INVALID_POINTER_ID
                    jsRightX = 0f
                    jsRightY = 0f
                }
            }
        }

        MotionEvent.ACTION_POINTER_UP -> {
            val pid = event.getPointerId(actionIdx)
            if (pid == rightPointerId) {
                rightJoystickActive = false
                rightPointerId = MotionEvent.INVALID_POINTER_ID
                jsRightX = 0f
                jsRightY = 0f
            }
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            rightJoystickActive = false
            rightPointerId = MotionEvent.INVALID_POINTER_ID
            jsRightX = 0f
            jsRightY = 0f
        }
    }

    // Gemeinsame Aktualisierung nach dem when:
    manualOverrideActive = leftJoystickActive || rightJoystickActive

    if (smoothRejoinEnabled) {
        if (!wasOverride && manualOverrideActive) {
            // Override beginnt
            frozenVSet = speedController.getLastOutput()
            Log.i("JOYSTICK", "Manual override ON (frozenVSet=$frozenVSet)")
        } else if (wasOverride && !manualOverrideActive) {
            // Override endet
            frozenVSet?.let {
                speedController.setOutput(it)
                Log.i("JOYSTICK", "Manual override OFF - resume at vSet=$it")
                frozenVSet = null
            }
        }
    }

    // Thumb-Position aktualisieren
    updateThumbPosition(joystickRightThumb, joystickRightContainer, jsRightX, -jsRightY)

    // Override-Status aktualisieren
    updateOverrideStatusUI()

    sendJoystickCommandsToDrone()
}

internal fun MainActivity.sendJoystickCommandsToDrone() {
    // Safety: im Simulation-Prototyp niemals Real-Drohnenkommandos senden
    if (!PrototypeConfig.requiresRealDrone()) return
    if (noDroneMode) return
    if (safetyLandingActive) return

    val driver = flightDriver as? ManualDriver ?: return

    // Prototype 2: Vertical wird vom AltitudeDriver geregelt -> Joystick-Vertical ignorieren
    val verticalCmd = if (PrototypeConfig.currentPrototype == PrototypeType.INDOOR_ALTITUDE) 0f else jsLeftY

    driver.applyManualControls(
        pitch = jsRightY,
        roll = jsRightX,
        yaw = jsLeftX,
        vertical = verticalCmd
    )
}

    // Bewegt den Thumb relativ zum Container entsprechend normalizedX/Y in [-1, 1]
    internal fun MainActivity.updateThumbPosition(thumb: View, container: View, normalizedX: Float, normalizedY: Float) {
        val radiusX = (container.width - thumb.width) / 2f
        val radiusY = (container.height - thumb.height) / 2f
        if (radiusX <= 0f || radiusY <= 0f) return

        val x = normalizedX.coerceIn(-1f, 1f) * radiusX
        val y = normalizedY.coerceIn(-1f, 1f) * radiusY

        thumb.translationX = x
        thumb.translationY = y
    }

    internal fun MainActivity.updateOverrideStatusUI() {
        val (text, colorHex) = if (manualOverrideActive) {
            "MANUAL" to "#FF9800"   // Orange
        } else {
            "AUTO" to "#4CAF50"     // Grün
        }

        overrideStatusTxt.text = text
        overrideStatusTxt.setTextColor(colorHex.toColorInt())
    }
