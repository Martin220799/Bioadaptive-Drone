# HelloDrone

Android application (Kotlin) for bioadaptive drone control based on real-time heart rate data.

## Overview

This project connects a Polar H10 chest strap (via BLE) to a Parrot ANAFI drone (via Parrot GroundSDK) and adjusts drone behavior in real time based on the operator's physiological state. Heart rate zones are calculated per participant using an individual calibration procedure and mapped to either drone speed (outdoor prototype) or drone altitude (indoor prototype). A simulation mode allows testing without physical drone hardware.

## Prototypes

The application supports three experimental prototypes, selectable at runtime:

- **Outdoor**: horizontal speed adaptation for an outdoor-flying drone
- **Indoor Altitude**: vertical altitude adaptation for a stationary indoor drone
- **Simulation**: laptop/treadmill simulation without physical drone hardware, communicating with a Python server via WebSocket

## Features

- Real-time heart rate and RR-interval acquisition from a Polar H10 sensor via Bluetooth LE
- Artifact filtering and moving-average smoothing of heart rate data
- Connection quality monitoring (OK / degraded / lost) with live metrics
- Per-participant calibration (resting HR, walking HR, HRV baseline) stored as reusable JSON profiles
- HR-zone classification (green / yellow / red) with hysteresis and minimum dwell time
- Multiple interchangeable speed controllers (zone-based, trend-based, position-and-trend-based)
- Multiple interchangeable altitude controllers (zone-based, trend-based, position-and-trend-based)
- Manual joystick override alongside automatic HR-based control
- Drone control via Parrot GroundSDK (arm/disarm, forward speed, altitude)
- WebSocket client for streaming biometric data to a Python-based simulation server
- Session-based CSV logging of heart rate, zone, and controller output for scientific reproducibility
- Participant profile management (create, save, reload across sessions and prototypes)

## Tech Stack and Versions

| Component | Version |
|---|---|
| Kotlin | 1.9.24 |
| Android Gradle Plugin | 8.5.2 |
| Gradle | 8.9 |
| Compile / Target SDK | 34 |
| Minimum SDK | 24 |
| Java/Kotlin JVM target | 17 |
| Parrot GroundSDK | 7.7.3 |
| OkHttp | 4.12.0 |
| AndroidX Core KTX | 1.13.1 |
| AndroidX AppCompat | 1.7.0 |
| Material Components | 1.12.0 |
| ConstraintLayout | 2.1.4 |

## Required Permissions

- Bluetooth (scan, connect; legacy Bluetooth/Bluetooth Admin for SDK < 31)
- Body sensors
- Nearby WiFi devices
- Internet and network state (drone connection, simulation WebSocket)
- Notifications
- Vibrate

## Hardware

- Polar H10 heart rate sensor (Bluetooth LE)
- Parrot ANAFI drone (for Outdoor and Indoor Altitude prototypes)
- Android device with SDK 24 or higher (Simulation prototype only requires this)

## Build

```
./gradlew assembleDebug
```

## Third-Party Notices

This application uses the Parrot Ground SDK for Android (com.parrot.drone.groundsdk:groundsdk, version 7.7.3), Copyright Parrot Drone SAS, licensed under the 3-Clause BSD License. See https://opensource.org/licenses/BSD-3-Clause for the full license text.

ANAFI and Parrot are trademarks of Parrot Drone SAS. This project is an independent academic work and is not affiliated with, endorsed by, or sponsored by Parrot Drone SAS.

