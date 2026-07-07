package com.parrot.hellodrone.speed
import com.parrot.hellodrone.bio.Zone
import com.parrot.hellodrone.bio.ZoneInfo
/**
 * Interface für alle SpeedControllerV0-Versionen.
 *
 * Unterstützt zwei Update-Methoden:
 * - update(zone, ...) für V0 und V1 (nur Zone)
 * - update(zoneInfo, ...) für V2 (Zone + Position)
 */
interface ISpeedController {
    /**
     * Update mit Zone (für V0 und V1)
     */
    fun update(zone: Zone, hrSmooth: Int, nowMs: Long): Float

    /**
     * Update mit ZoneInfo (für V2 - Position + Trend)
     */
    fun update(zoneInfo: ZoneInfo, hrSmooth: Int, nowMs: Long): Float

    /**
     * Reset des Controllers (z.B. zwischen Sessions)
     */
    fun reset()

    /**
     * Gibt den letzten berechneten Output zurück
     */
    fun getLastOutput(): Float

    /**
     * Setzt den Output manuell (für Joystick-Override)
     */
    fun setOutput(value: Float)

    /**
     * Prüft ob Pausenmodus aktiv ist (lange RED-Zeit)
     */
    fun isPauseActive(): Boolean

    /**
     * Gibt die Dauer in RED-Zone zurück (ms)
     */
    fun getRedZoneDurationMs(): Long

    /**
     * Gibt den zuletzt verarbeiteten HR-Wert zurück
     */
    fun getLastHr(): Int
}