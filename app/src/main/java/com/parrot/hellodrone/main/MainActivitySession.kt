package com.parrot.hellodrone.main

import android.os.SystemClock
import com.parrot.hellodrone.MainActivity

    /**
     * Startet eine neue Session (auch Zeit-Base) - nur wenn du wirklich eine neue willst.
     */
    internal fun MainActivity.startNewSessionWithTimebase(reason: String) {
        csvLogger.startNewSession()
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        csvLogger.logEvent("SESSION_START $reason")
    }

    /**
     * Stellt sicher, dass eine Session existiert. Setzt Timebase nur beim Erststart.
     */
    internal fun MainActivity.ensureSessionExistsWithTimebase() {
        if (csvLogger.getCurrentSessionId().isEmpty()) {
            csvLogger.startNewSession()
            sessionStartElapsedMs = SystemClock.elapsedRealtime()
            csvLogger.logEvent("SESSION_START implicit")
        } else if (sessionStartElapsedMs == 0L) {
            // Safety: falls Session-ID existiert (z.B. Restore) aber Timebase fehlt
            sessionStartElapsedMs = SystemClock.elapsedRealtime()
            csvLogger.logEvent("SESSION_TIMEBASE_RECOVERED")
        }
    }

    /**
     * Elapsed ms seit Session-Start, monotone clock.
     */
    internal fun MainActivity.elapsedSinceSessionStartMs(nowElapsed: Long): Long {
        return if (sessionStartElapsedMs > 0L) (nowElapsed - sessionStartElapsedMs) else 0L
    }
