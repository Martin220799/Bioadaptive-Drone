package com.parrot.hellodrone.config

/**
 * PrototypeType: Enum für die drei Prototyp-Varianten der Masterarbeit
 *
 * OUTDOOR:          Prototyp 3 - Outdoor-Drohne mit horizontaler Geschwindigkeitsanpassung
 * INDOOR_ALTITUDE:  Prototyp 2 - Indoor-Drohne mit vertikaler Höhenanpassung (stationär)
 * SIMULATION:       Prototyp 1 - Laptop-Simulation der Drohne (Laufband/Ergometer)
 */
enum class PrototypeType(
    val displayName: String,        // UI-Anzeige
    val csvIdentifier: String,      // CSV-Logging (muss mit CsvLogger übereinstimmen)
    val description: String         // Längere Beschreibung für UI
) {
    OUTDOOR(
        displayName = "Outdoor Drohne",
        csvIdentifier = "OUTDOOR",
        description = "Outdoor-Flug mit horizontaler Geschwindigkeitsanpassung"
    ),
    
    INDOOR_ALTITUDE(
        displayName = "Indoor Höhe",
        csvIdentifier = "INDOOR_ALTITUDE",
        description = "Indoor-Drohne mit vertikaler Höhenanpassung (stationär)"
    ),
    
    SIMULATION(
        displayName = "Simulation",
        csvIdentifier = "SIMULATION",
        description = "Laptop-Simulation der Drohne am Laufband/Ergometer"
    );

    companion object {
        /**
         * Findet PrototypeType anhand des CSV-Identifiers
         */
        fun fromCsvIdentifier(identifier: String): PrototypeType? {
            return values().find { it.csvIdentifier == identifier }
        }
    }
}

/**
 * Globale Prototyp-Konfiguration für die MainActivity
 * 
 * Diese Klasse kann später erweitert werden um zusätzliche
 * prototyp-spezifische Parameter zu speichern.
 */
object PrototypeConfig {
    /**
     * Aktuell ausgewählter Prototyp (wird zur Laufzeit gesetzt)
     */
    @Volatile
    var currentPrototype: PrototypeType = PrototypeType.OUTDOOR
        private set

    /**
     * Setzt den aktiven Prototyp
     */
    @Synchronized
    fun setPrototype(type: PrototypeType) {
        currentPrototype = type
    }

    /**
     * Prüft ob der aktuelle Prototyp eine echte Drohne benötigt
     */
    fun requiresRealDrone(): Boolean {
        return currentPrototype == PrototypeType.OUTDOOR || 
               currentPrototype == PrototypeType.INDOOR_ALTITUDE
    }

    /**
     * Prüft ob der aktuelle Prototyp horizontale Steuerung benötigt
     */
    fun usesHorizontalControl(): Boolean {
        return currentPrototype == PrototypeType.OUTDOOR ||
               currentPrototype == PrototypeType.SIMULATION
    }

    /**
     * Prüft ob der aktuelle Prototyp vertikale Steuerung benötigt
     */
    fun usesVerticalControl(): Boolean {
        return currentPrototype == PrototypeType.INDOOR_ALTITUDE
    }
}
