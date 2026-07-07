package com.parrot.hellodrone.altitude
import com.parrot.hellodrone.bio.Zone
import com.parrot.hellodrone.bio.ZoneInfo

/**
* Interface für alle AltitudeController-Versionen (Prototyp 2 - Indoor Altitude).
*
* Unterstützt zwei Update-Methoden:
* - update(zone, ...) für A0 und A1 (nur Zone)
* - update(zoneInfo, ...) für A2 (Zone + Position)
*/
interface IAltitudeController {
    /**
     * Update mit Zone (für A0 und A1)
     *
     * @param zone Aktuelle HR-Zone (GREEN/YELLOW/RED)
     * @param hrSmooth Geglättete Herzfrequenz (bpm)
     * @param nowMs Aktueller Timestamp (System.currentTimeMillis())
     * @return Ziel-Flughöhe in Metern
     */
    fun update(zone: Zone, hrSmooth: Int, nowMs: Long): Float

    /**
     * Update mit ZoneInfo (für A2 - Position + Trend)
     *
     * @param zoneInfo Zone + relative Position innerhalb der Zone (0.0-1.0)
     * @param hrSmooth Geglättete Herzfrequenz (bpm)
     * @param nowMs Aktueller Timestamp (System.currentTimeMillis())
     * @return Ziel-Flughöhe in Metern
     */
    fun update(zoneInfo: ZoneInfo, hrSmooth: Int, nowMs: Long): Float

    /**
     * Reset des Controllers (z.B. zwischen Sessions)
     */
    fun reset()

    /**
     * Gibt die letzte berechnete Zielhöhe zurück
     *
     * @return Flughöhe in Metern
     */
    fun getLastOutput(): Float

    /**
     * Setzt die Ausgangshöhe manuell (z.B. für manuellen Override oder sanften Start)
     *
     * @param value Flughöhe in Metern
     */
    fun setOutput(value: Float)

    /**
     * Prüft ob Pausenmodus aktiv ist (lange RED-Zeit)
     *
     * @return true wenn Pause aktiv (Drohne sollte auf Sicherheitshöhe)
     */
    fun isPauseActive(): Boolean

    /**
     * Gibt die Dauer in RED-Zone zurück (ms)
     *
     * @return Millisekunden in RED-Zone
     */
    fun getRedZoneDurationMs(): Long

    /**
     * Gibt den zuletzt verarbeiteten HR-Wert zurück
     *
     * @return Herzfrequenz (bpm)
     */
    fun getLastHr(): Int
}