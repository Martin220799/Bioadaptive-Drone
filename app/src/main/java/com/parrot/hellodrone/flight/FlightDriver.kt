package com.parrot.hellodrone.flight

interface FlightDriver {
    fun arm()        // Takeoff
    fun disarm()     // Land
    fun setForwardSpeed(vSet: Float) // m/s -> Vorwärtsfahrt
}
