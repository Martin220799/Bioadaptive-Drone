package com.parrot.hellodrone

import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.content.Context
import android.graphics.Color
import android.os.Vibrator
import android.os.VibrationEffect
import android.view.MotionEvent
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.ManagedGroundSdk
import com.parrot.drone.groundsdk.Ref
import com.parrot.drone.groundsdk.device.DeviceState
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.RemoteControl
import com.parrot.drone.groundsdk.device.instrument.BatteryInfo
import com.parrot.drone.groundsdk.device.peripheral.StreamServer
import com.parrot.drone.groundsdk.device.peripheral.stream.CameraLive
import com.parrot.drone.groundsdk.device.pilotingitf.ManualCopterPilotingItf
import com.parrot.drone.groundsdk.facility.AutoConnection
import com.parrot.drone.groundsdk.stream.GsdkStreamView
import android.os.VibratorManager
import androidx.core.graphics.toColorInt
import android.os.Handler
import android.os.Looper
import android.annotation.SuppressLint
import java.util.concurrent.atomic.AtomicLong
import android.os.SystemClock
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.appcompat.app.AppCompatActivity
import com.parrot.hellodrone.bio.Zone
import com.parrot.hellodrone.bio.QcState
import com.parrot.hellodrone.bio.HrProcessor
import com.parrot.hellodrone.bio.PolarH10BleClient
import com.parrot.hellodrone.bio.ZoneManager
import com.parrot.hellodrone.speed.ISpeedController
import com.parrot.hellodrone.speed.SpeedControllerV0
import com.parrot.hellodrone.speed.SpeedControllerV1
import com.parrot.hellodrone.speed.SpeedControllerV2
import com.parrot.hellodrone.altitude.IAltitudeController
import com.parrot.hellodrone.altitude.AltitudeControllerA0
import com.parrot.hellodrone.altitude.AltitudeControllerA1
import com.parrot.hellodrone.altitude.AltitudeControllerA2
import com.parrot.hellodrone.altitude.AltitudeDriver
import com.parrot.hellodrone.flight.FlightDriver
import com.parrot.hellodrone.profile.ParticipantProfile
import com.parrot.hellodrone.profile.ProfileManager
import com.parrot.hellodrone.config.PrototypeType
import com.parrot.hellodrone.config.PrototypeConfig
import com.parrot.hellodrone.logging.CsvLogger
import com.parrot.hellodrone.sim.SimulationClient
import android.widget.*
import com.parrot.hellodrone.main.*




internal enum class SpeedControlVersion {
    ORIGINAL,    // V0 - Baseline (vGreen=0.8, vYellow=1.0, vRed=0.6)
    TREND,       // V1 - Trend-basiert (4-5 Geschwindigkeiten)
    OPTIMAL      // V2 - Position+Trend (kontinuierlich)
}

internal enum class AltitudeControlVersion {
    A0,    // Baseline - Fixe Höhen pro Zone
    A1,    // Trend - Zonenmitte +/- Trend-Modulation
    A2     // Position+Trend - Kontinuierliche Skala
}


// ------------------------------------------------------------
// Zentrales Speed-Profil für alle SpeedController-Versionen
// ------------------------------------------------------------

// Ziel-Geschwindigkeiten (m/s)
internal const val V_GREEN = 0.8f     // angenehmes Gehen
internal const val V_YELLOW = 1.05f    // zügiges Gehen, kein Joggen
internal const val V_RED_BASE = 0.6f  // Entlastungs-Tempo in ROT

// Globale Geschwindigkeitsgrenzen (für vMin/vMax)
internal const val V_MIN = 0.4f       // nie langsamer als das
internal const val V_MAX = 1.4f       // Hard-Cap (Safety & Komfort)

// Dynamik
internal const val MAX_ACCEL = 0.4f   // m/s Schrittbegrenzung zwischen Samples
internal const val RED_PAUSE_THRESHOLD_MS = 45_000L  // ab wann Pause-Logik greift

// Speed-Cap in der Main (zusätzlicher Hard-Limit)
internal const val VSET_CAP = 1.4f    // sollte zu V_MAX passen

// ================= SIMULATION (Prototype 1) =================
internal const val SIM_PORT = 8765
internal const val SIM_DEFAULT_IP = "10.0.0.180"

class MainActivity : AppCompatActivity() {

    // ------------------------------------------------------------
// Betriebsmodi der App
// ------------------------------------------------------------

    /**
     * noDroneMode:
     *  true  -> Drohne wird NICHT gesteuert. Walk-Test / Kontrolllauf ohne Drohne.
     *           Logging läuft normal (CONTROL_WALK), aber keine Pitch-/Throttle-Kommandos.
     *
     *  false -> Drohne ist aktiv verbunden und wird über Autopilot + Joysticks gesteuert.
     *           Normale Testphase mit Drohne (DRONE_WALK / FLIGHT).
     */
    internal val noDroneMode: Boolean = false

    /**
     * debugMode:
     *  true  -> App setzt künstliche Debug-Baseline (z. B. baselineHr = 60, hrWalk = 90),
     *           damit Kalibrierung & ZoneManager ohne echte Sensor-Daten getestet werden können.
     *           NICHT für echte Probandenversuche verwenden.
     *
     *  false -> Normales Verhalten: echte HR-/HRV-Daten werden verwendet.
     */
    internal val debugMode: Boolean = false  // ← FIX: false für Probandentests!


    /**
     * requireCalibrationForTakeoff:
     *  true  -> Drohne DARF NUR starten,
     *           wenn vorher PreFlight-HRV + Kalibrier-Spaziergang erfolgreich durchgeführt wurden.
     *           Sicherheit für Experimente: garantiert korrekte Zonenlogik.
     *
     *  false -> Takeoff jederzeit möglich,
     *           auch ohne Kalibrierung (nur für Debug sinnvoll).
     */
    internal val requireCalibrationForTakeoff = true

    /**
     * hrAutopilotEnabled:
     *  true  -> Autopilot aktiv: v_set (Drohnen-Geschwindigkeit) wird aus HR/Zone berechnet.
     *           Joystick-Eingaben überschreiben nur temporär (Manual Override).
     *
     *  false -> KEIN HR->Speed-Mapping. Drohne wird NUR über virtuelle Joysticks gesteuert.
     *           Sehr nützlich für reine Handling-/Safety-Tests.
     */
    internal val hrAutopilotEnabled: Boolean = true





    @Volatile internal var safetyLandingActive = false

    /**
     * Speed Control Version Auswahl
     */
    // ---------------------------------------------
    // Session-Timebase (monoton) für elapsed_ms in hr_live.csv
    // -------------------------------------------
    internal var sessionStartElapsedMs: Long = 0L

    internal var polarAutoScanStarted = false

    internal var speedControlVersion = SpeedControlVersion.ORIGINAL  // var = änderbar!
    internal var altitudeControlVersion = AltitudeControlVersion.A0  // var = änderbar!
    internal var segmentId: Int = 0   // Nummer des aktuellen Abschnitts (Walk/Flight)

    // Smooth-Übergang zurück zur HR-Steuerung nach Override
    internal val smoothRejoinEnabled: Boolean = true
    internal var frozenVSet: Float? = null

    // ========== SIMULATION CLIENT  ==========
    internal lateinit var simulationClient: SimulationClient
    // Sequence tracking für wissenschaftliche Auswertung
    internal val messageSeq = AtomicLong(0L)
    internal var sessionId: String = ""


    
    // Verhindert Log-Spam, falls Simulation verbunden ist, aber Prototyp != SIMULATION
    internal var simPrototypeMismatchWarned: Boolean = false
// UI Elements für Simulation (Checkbox entfernt - RadioGroup ist Single Source of Truth)
    internal lateinit var editTextSimIp: EditText
    internal lateinit var buttonSimConnect: Button
    internal lateinit var textViewSimStatus: TextView

    // GroundSDK / Drone / RC
    internal lateinit var groundSdk: GroundSdk
    internal var drone: Drone? = null
    internal var droneStateRef: Ref<DeviceState>? = null
    internal var droneBatteryInfoRef: Ref<BatteryInfo>? = null
    internal var pilotingItfRef: Ref<ManualCopterPilotingItf>? = null
    internal var altimeterRef: Ref<com.parrot.drone.groundsdk.device.instrument.Altimeter>? = null
    internal var streamServerRef: Ref<StreamServer>? = null
    internal var liveStreamRef: Ref<CameraLive>? = null
    internal var liveStream: CameraLive? = null
    internal var rc: RemoteControl? = null
    internal var rcStateRef: Ref<DeviceState>? = null
    internal var rcBatteryInfoRef: Ref<BatteryInfo>? = null

    // Telemetrie (Prototype 2)
    internal var currentAltitude: Float? = null  // Aktuelle Drohnen-Höhe in Metern

    // UI - Standard
    internal lateinit var streamView: GsdkStreamView
    internal lateinit var droneStateTxt: TextView
    internal lateinit var droneBatteryTxt: TextView
    internal lateinit var rcStateTxt: TextView
    internal lateinit var rcBatteryTxt: TextView
    internal lateinit var takeOffLandBt: Button

    // UI - Physiology
    internal lateinit var hrValueTxt: TextView
    internal lateinit var hrvTimerTxt: TextView
    internal lateinit var hrvResultTxt: TextView
    internal lateinit var qcStatusTxt: TextView

    // UI - Bioadaptive Feedback
    internal lateinit var zoneIndicatorTxt: TextView
    internal lateinit var speedRecommendationTxt: TextView

    // Kalibrierungs-Tracking
    internal var isCalibrating = false
    internal var calibrationRrCounter = 0

    internal val calibRrBuffer = mutableListOf<Int>()
    internal var calibrationValidRatioStored = 0.95f
    internal var calibrationStartTime = 0L
    internal var calibrationDurationSec = 180  // Default, wird überschrieben

    // HRV-Tracking (für Profil)
    internal var lastHrvDurationSec = 180
    internal var lastHrvRrCollected = 0
    internal var lastHrvRrValid = 0

    // UI - Controls
    internal lateinit var hrvModeGroup: RadioGroup
    internal lateinit var rbPreFlight: RadioButton
    internal lateinit var rbPostFlight: RadioButton
    internal lateinit var btnHrvToggle: Button
    internal lateinit var btnLogToggle: Button
    internal lateinit var btnCalibWalk: Button

    // UI Drone Control
    internal lateinit var joystickLeftView: View
    internal lateinit var joystickRightView: View

    // Joystick-UI
    internal lateinit var joystickLeftContainer: View
    internal lateinit var joystickRightContainer: View
    internal lateinit var joystickLeftThumb: View
    internal lateinit var joystickRightThumb: View

    // Override-Status
    internal lateinit var overrideStatusTxt: TextView

    // Emergency-Button
    internal lateinit var btnEmergencyLand: Button

    //Joystick-Override-State
    // Option A: volle manuelle Steuerung, solange ein Stick gedrückt wird
    internal var manualOverrideActive: Boolean = false
    internal var leftJoystickActive: Boolean = false
    internal var rightJoystickActive: Boolean = false


    // Multi-Touch Robustness: Pointer-IDs pro Stick.
    // Hintergrund: Bei Multi-Touch wird oft ACTION_POINTER_UP statt ACTION_UP ausgelöst.
    // Ohne Pointer-Tracking kann left/rightJoystickActive "true" bleiben -> manualOverrideActive bleibt aktiv
    // und der Autopilot setzt keine Vorwärts-Kommandos mehr.
    internal var leftPointerId: Int = MotionEvent.INVALID_POINTER_ID
    internal var rightPointerId: Int = MotionEvent.INVALID_POINTER_ID

    // Joystick-Achsen [-1 .. +1]
    // Linker Stick: yaw (X), vertical (Y)
    // Rechter Stick: roll (X), pitch (Y)
    internal var jsLeftX: Float = 0f   // yaw
    internal var jsLeftY: Float = 0f   // vertical
    internal var jsRightX: Float = 0f  // roll
    internal var jsRightY: Float = 0f  // pitch

    // UI - HRV Duration Spinner
    internal lateinit var hrvDurationSpinner: Spinner

    @Volatile internal var isLogging = false

    // BLE / Polar
    internal lateinit var polar: PolarH10BleClient
    internal var lastHrBpm: Int = -1
    internal var lastConnectionStable: Boolean? = null


    // HRV measurement
    internal var measuring = false
    internal val rrBuffer = mutableListOf<Int>()
    internal val hrSamples = mutableListOf<Int>()
    internal var timer: CountDownTimer? = null
    internal var measureSeconds = 180  // ÄNDERUNG: Default jetzt 180s statt 60s

    // QC-State Tracking für Events
    internal var lastQcState: QcState? = null

    internal var watchdogHandler: Handler? = null
    internal var watchdogRunnable: Runnable? = null

    // Zonen/Speed
    internal var baselineHr: Int? = null
    internal var rmssdPre: Double? = null
    internal var sdnnPre: Double? = null  //
    internal lateinit var zoneManager: ZoneManager
    internal lateinit var speedController: ISpeedController

    //Logging Controller, berechnen vset für jede Speedcontroller Verison parallel
    internal lateinit var speedControllerV0: ISpeedController    // ORIGINAL
    internal lateinit var speedControllerV1: ISpeedController    // TREND
    internal lateinit var speedControllerV2: ISpeedController    // OPTIMAL

    // Altitude Controllers (Prototype 2 - Indoor)
    internal lateinit var altitudeControllerA0: IAltitudeController  // Baseline
    internal lateinit var altitudeControllerA1: IAltitudeController  // Trend
    internal lateinit var altitudeControllerA2: IAltitudeController  // Position+Trend
    internal lateinit var altitudeController: IAltitudeController    // Aktiver Controller
    internal var altitudeDriver: AltitudeDriver? = null

    // Kalibrier-Spaziergang
    internal var isCalibratingWalk: Boolean = false
    internal data class CalibSample(val timestampMs: Long, val hrBpm: Int)
    internal val calibSamples = mutableListOf<CalibSample>()
    internal var walkCalibrationMeanHr: Int? = null

    // CSV + Flug-Logging
    internal lateinit var csvLogger: CsvLogger

    // HR-Verarbeitung (Filter + QC)
    internal lateinit var hrProcessor: HrProcessor

    // Live-Logging-Rate
    @Volatile internal var flightLogging = false  // ÄNDERUNG: @Volatile hinzugefügt
    @Volatile
    internal var lastHrLoggedAtMs: Long = 0L
    internal val logIntervalMs = 1000L

    //: Simulation-Sendefrequenz (unabhängig von CSV-Logging)
    @Volatile
    internal var lastSimSentAtMs: Long = 0L
    internal val simIntervalMs = 200L  // 5 Hz (flüssigere Visualisierung)
    // Optionen: 100L (10 Hz), 150L (6.7 Hz), 250L (4 Hz)


    // HR-Trend Tracking
    internal var lastHrSmooth: Int = 0
    // Flightmode
    internal var flightDriver: FlightDriver? = null
    internal val vSetCap: Float = VSET_CAP

    //: Vibration für haptisches Feedback
    internal lateinit var vibrator: Vibrator

    //: Tracking der letzten Zone für Zonenwechsel-Detection
    internal var lastZone: Zone = Zone.GREEN

    // ========================================================================
    // Profil-Management & Version-Selection
    // ========================================================================
    internal lateinit var profileManager: ProfileManager
    internal var currentProfile: ParticipantProfile? = null

    // UI - Profil-Management
    internal lateinit var profileSpinner: Spinner
    internal lateinit var profileStatusTxt: TextView
    internal lateinit var btnProfileLoad: Button
    internal lateinit var btnProfileSave: Button

    // UI - Version-Selection
    internal lateinit var speedVersionGroup: RadioGroup
    internal lateinit var rbSpeedV0: RadioButton
    internal lateinit var rbSpeedV1: RadioButton
    internal lateinit var rbSpeedV2: RadioButton

    // UI - Prototype Selection
    internal lateinit var prototypeGroup: RadioGroup
    internal lateinit var rbPrototypeOutdoor: RadioButton
    internal lateinit var rbPrototypeAltitude: RadioButton
    internal lateinit var rbPrototypeSimulation: RadioButton

    // UI - Altitude Controller Selection (Prototype 2)
    internal lateinit var altitudeVersionGroup: RadioGroup
    internal lateinit var rbAltitudeA0: RadioButton
    internal lateinit var rbAltitudeA1: RadioButton
    internal lateinit var rbAltitudeA2: RadioButton
    internal lateinit var altitudeStatusTxt: TextView


    //Sound Gen für red alert
    // RED-Audio Policy: erst nach 45s durchgehend RED, dann alle 10s
    internal val redAlertStartAfterMs = 45_000L
    internal val redAlertRepeatMs = 10_000L

    internal var redEnteredAtMs: Long = -1L
    internal var lastRedAlertAtMs: Long = -1L

    // ToneGenerator einmalig, nicht pro Beep neu erzeugen
    internal val redTone: ToneGenerator by lazy {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
    }

    // Accessors für lateinit-Initialisierungsprüfung aus Extension-Dateien
    // (::prop.isInitialized ist nur innerhalb der Klasse zugänglich)
    internal val isVibratorInitialized: Boolean get() = this::vibrator.isInitialized
    internal val isSpeedControllerV0Initialized: Boolean get() = this::speedControllerV0.isInitialized
    internal val isSpeedControllerV1Initialized: Boolean get() = this::speedControllerV1.isInitialized
    internal val isSpeedControllerV2Initialized: Boolean get() = this::speedControllerV2.isInitialized

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // === MediaStore Sync Fix ===
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            syncProfilesWithMediaStore()
        }
        // === End MediaStore Sync ===

        // UI bind - Standard
        streamView = findViewById(R.id.stream_view)
        droneStateTxt = findViewById(R.id.droneStateTxt)
        droneBatteryTxt = findViewById(R.id.droneBatteryTxt)
        rcStateTxt = findViewById(R.id.rcStateTxt)
        rcBatteryTxt = findViewById(R.id.rcBatteryTxt)
        takeOffLandBt = findViewById(R.id.takeOffLandBt)
        takeOffLandBt.setOnClickListener { onTakeOffLandClick() }
        if (noDroneMode) takeOffLandBt.isEnabled = false

        // UI bind - Physiology
        hrValueTxt = findViewById(R.id.hrValueTxt)
        qcStatusTxt = findViewById(R.id.qcStatusTxt)
        hrvTimerTxt = findViewById(R.id.hrvTimerTxt)
        hrvResultTxt = findViewById(R.id.hrvResultTxt)
        hrvModeGroup = findViewById(R.id.hrvModeGroup)
        rbPreFlight = findViewById(R.id.rbPreFlight)
        rbPostFlight = findViewById(R.id.rbPostFlight)

        // UI bind - Bioadaptive Feedback
        zoneIndicatorTxt = findViewById(R.id.zoneIndicatorTxt)
        speedRecommendationTxt = findViewById(R.id.speedRecommendationTxt)

        // UI bind - Controls
        btnHrvToggle = findViewById(R.id.btnHrvToggle)
        btnHrvToggle.setOnClickListener { onToggleHrv() }

        // UI Drone Control
        joystickLeftView = findViewById(R.id.joystickLeftView)
        joystickRightView = findViewById(R.id.joystickRightView)

        // Joystick-Container & Thumb-Views
        joystickLeftContainer = findViewById(R.id.joystickLeftContainer)
        joystickRightContainer = findViewById(R.id.joystickRightContainer)
        joystickLeftThumb = findViewById(R.id.joystickLeftThumb)
        joystickRightThumb = findViewById(R.id.joystickRightThumb)

        // Override-Status
        overrideStatusTxt = findViewById(R.id.overrideStatusTxt)
        updateOverrideStatusUI()   // Initial: AUTO

        // Emergency-Land-Button
        btnEmergencyLand = findViewById(R.id.btnEmergencyLand)
        btnEmergencyLand.setOnClickListener { emergencyLand() }

        // Joystick Touch Listener
        joystickLeftView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                }
            }
            handleLeftJoystick(event, v.width, v.height)
            true
        }
        joystickRightView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                }
            }
            handleRightJoystick(event, v.width, v.height)
            true
        }

        btnLogToggle = findViewById(R.id.btnLogToggle)
        btnLogToggle.text = getString(R.string.btn_start_logging)
        btnLogToggle.setOnClickListener { onToggleLogging() }
        /**  if (!noDroneMode) {
        btnLogToggle.isEnabled = false
        }*/

        btnCalibWalk = findViewById(R.id.btnCalibWalk)
        btnCalibWalk.text = getString(R.string.btn_start_calib_walk)
        btnCalibWalk.setOnClickListener { onToggleCalibWalk() }

        // UI bind - HRV Duration Spinner
        hrvDurationSpinner = findViewById(R.id.hrvDurationSpinner)
        setupHrvDurationSpinner()

        // ====================================
        // UI bind - Profil-Management
        // ====================================
        profileSpinner = findViewById(R.id.profileSpinner)
        profileStatusTxt = findViewById(R.id.profileStatusTxt)
        btnProfileLoad = findViewById(R.id.btnProfileLoad)
        btnProfileSave = findViewById(R.id.btnProfileSave)

        btnProfileLoad.setOnClickListener { onLoadProfile() }
        btnProfileSave.setOnClickListener { onSaveProfile() }

        // =============================================
        // UI bind - Version-Selection
        // =============================================
        speedVersionGroup = findViewById(R.id.speedVersionGroup)
        rbSpeedV0 = findViewById(R.id.rbSpeedV0)
        rbSpeedV1 = findViewById(R.id.rbSpeedV1)
        rbSpeedV2 = findViewById(R.id.rbSpeedV2)

        // ===========================================
        // Simulation UI Binding (Prototype 1) - Checkbox entfernt
        // ==========================================
        editTextSimIp = findViewById(R.id.editTextSimIp)
        buttonSimConnect = findViewById(R.id.buttonSimConnect)
        textViewSimStatus = findViewById(R.id.textViewSimStatus)

        setupSimulation()  // Initialisierung + Callbacks

        // ================================
        // Prototype Selection UI Binding
        // ===============================
        prototypeGroup = findViewById(R.id.prototypeGroup)
        rbPrototypeOutdoor = findViewById(R.id.rbPrototypeOutdoor)
        rbPrototypeAltitude = findViewById(R.id.rbPrototypeAltitude)
        rbPrototypeSimulation = findViewById(R.id.rbPrototypeSimulation)

        // Initial: Aktuellen Prototyp aus PrototypeConfig setzen
        when (PrototypeConfig.currentPrototype) {
            PrototypeType.OUTDOOR -> rbPrototypeOutdoor.isChecked = true
            PrototypeType.INDOOR_ALTITUDE -> rbPrototypeAltitude.isChecked = true
            PrototypeType.SIMULATION -> rbPrototypeSimulation.isChecked = true
        }

        prototypeGroup.setOnCheckedChangeListener { _, checkedId ->
            onPrototypeChanged(checkedId)
        }

        // ========================================================================
        // Altitude Controller UI Binding (Prototype 2)
        // ========================================================================
        altitudeVersionGroup = findViewById(R.id.altitudeVersionGroup)
        rbAltitudeA0 = findViewById(R.id.rbAltitudeA0)
        rbAltitudeA1 = findViewById(R.id.rbAltitudeA1)
        rbAltitudeA2 = findViewById(R.id.rbAltitudeA2)
        altitudeStatusTxt = findViewById(R.id.altitudeStatusTxt)

        // Initial: Version aus altitudeControlVersion-Flag setzen
        when (altitudeControlVersion) {
            AltitudeControlVersion.A0 -> rbAltitudeA0.isChecked = true
            AltitudeControlVersion.A1 -> rbAltitudeA1.isChecked = true
            AltitudeControlVersion.A2 -> rbAltitudeA2.isChecked = true
        }

        altitudeVersionGroup.setOnCheckedChangeListener { _, checkedId ->
            onAltitudeVersionChanged(checkedId)
        }


        // Initial: Version aus speedControlVersion-Flag setzen
        when (speedControlVersion) {
            SpeedControlVersion.ORIGINAL -> rbSpeedV0.isChecked = true
            SpeedControlVersion.TREND -> rbSpeedV1.isChecked = true
            SpeedControlVersion.OPTIMAL -> rbSpeedV2.isChecked = true
        }

        speedVersionGroup.setOnCheckedChangeListener { _, checkedId ->
            onSpeedVersionChanged(checkedId)
        }

        // Initial UI State
        droneStateTxt.text = DeviceState.ConnectionState.DISCONNECTED.toString()
        rcStateTxt.text = DeviceState.ConnectionState.DISCONNECTED.toString()
        hrvTimerTxt.text = getString(R.string.hrv_timer_idle)

        // Initialize systems
        groundSdk = ManagedGroundSdk.obtainSession(this)
        csvLogger = CsvLogger(this)
        hrProcessor = HrProcessor()
        zoneManager = ZoneManager()
        profileManager = ProfileManager(this)  // ProfileManager initialisieren

        // Profil-Spinner mit verfügbaren Profilen füllen
        setupProfileSpinner()

        // alle drei Controller mit gleichem Speed-Profil initialisieren
        speedControllerV0 = SpeedControllerV0(
            vGreen = V_GREEN,
            vYellow = V_YELLOW,
            vRedBase = V_RED_BASE,
            vMax = V_MAX,
            maxAccel = MAX_ACCEL,
            redPauseThresholdMs = RED_PAUSE_THRESHOLD_MS
        )

        speedControllerV1 = SpeedControllerV1(
            vBase = V_YELLOW,      // Zielgeschwindigkeit in stabiler Yellow-Situation
            vMin = V_MIN,
            vMax = V_MAX,
            maxAccel = MAX_ACCEL,
            redPauseThresholdMs = RED_PAUSE_THRESHOLD_MS,
            yellowScalingFactor = 0.4f,
            redScalingFactor = 0.5f
        )

        speedControllerV2 = SpeedControllerV2(
            vBase = V_YELLOW,
            vMin = V_MIN,
            vMax = V_MAX,
            maxAccel = MAX_ACCEL,
            redPauseThresholdMs = RED_PAUSE_THRESHOLD_MS
        )

        // aktive Version auswählen (nur diese steuert Autopilot / Pause-Logik / Override-Logik)
        speedController = when (speedControlVersion) {
            SpeedControlVersion.ORIGINAL -> {
                Log.i("SPEED", "Initialise SpeedController V0 (Original/Baseline)")
                speedControllerV0
            }
            SpeedControlVersion.TREND -> {
                Log.i("SPEED", "Initialise SpeedController V1 (Trend-based)")
                speedControllerV1
            }
            SpeedControlVersion.OPTIMAL -> {
                Log.i("SPEED", "Initialise SpeedController V2 (Position+Trend)")
                speedControllerV2
            }
        }

        // ========================================================================
        // Altitude Controllers initialisieren (Prototype 2)
        // ========================================================================
        altitudeControllerA0 = AltitudeControllerA0(
            altGreen = 1.0f,
            altYellow = 1.4f,
            altRedBase = 1.8f,
            altMin = 0.8f,
            altMax = 1.9f,
            maxAltChangeRate = 0.3f,
            redPauseThresholdMs = RED_PAUSE_THRESHOLD_MS
        )

        altitudeControllerA1 = AltitudeControllerA1(
            altGreen = 1.2f,
            altYellow = 1.6f,
            altRedBase = 1.8f,
            altMin = 0.8f,
            altMax = 1.9f,
            maxAltChangeRate = 0.3f,
            redPauseThresholdMs = RED_PAUSE_THRESHOLD_MS
        )

        altitudeControllerA2 = AltitudeControllerA2(
            altMin = 0.8f,
            altMax = 1.9f,
            altRedBase = 1.8f,
            maxAltChangeRate = 0.3f,
            redPauseThresholdMs = RED_PAUSE_THRESHOLD_MS
        )

        // Aktiven Controller auswählen
        altitudeController = when (altitudeControlVersion) {
            AltitudeControlVersion.A0 -> {
                Log.i("ALTITUDE", "Initialise AltitudeController A0 (Baseline)")
                altitudeControllerA0
            }
            AltitudeControlVersion.A1 -> {
                Log.i("ALTITUDE", "Initialise AltitudeController A1 (Trend)")
                altitudeControllerA1
            }
            AltitudeControlVersion.A2 -> {
                Log.i("ALTITUDE", "Initialise AltitudeController A2 (Position+Trend)")
                altitudeControllerA2
            }
        }


        //vSetCap an Profil anpassen
        //vSetCap = VSET_CAP

        // Vibrator initialisieren (modern + rückwärtskompatibel)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (debugMode) {
            baselineHr = 60
            walkCalibrationMeanHr = 90
            rmssdPre = 40.1  // FIX #1: rmssdPre auch in MainActivity setzen, nicht nur an ZoneManager übergeben
            zoneManager.configureFromCalibration(
                hrRest = 60,
                hrWalk = 90,
                rmssdPre = 40.1
            )
            Log.w("DEBUG", "Debug-Mode active: artificial Baseline set (baselineHr=$baselineHr, walkHr=$walkCalibrationMeanHr, rmssdPre=$rmssdPre)")
        }

        ensureBlePermissions()
        polar = PolarH10BleClient(
            this,
            onHrData = { hr, rrList, tsMs ->
                lastHrBpm = hr
                runOnUiThread {
                    hrValueTxt.text = getString(R.string.hr_value_format, hr)
                }

                // RR während Kalibrierung zählen UND sammeln
                if (isCalibrating) {
                    calibrationRrCounter += rrList.size
                    synchronized(calibRrBuffer) {
                        calibRrBuffer.addAll(rrList)
                    }
                }

                // HRV-Puffer füllen, falls Messung aktiv
                if (measuring && rrList.isNotEmpty()) {
                    synchronized(rrBuffer) { rrBuffer.addAll(rrList) }
                }
                if (measuring && hr > 0) {
                    synchronized(hrSamples) { hrSamples.add(hr) }
                }

                // Kalibrier-Spaziergang: Roh-HR sammeln
                if (isCalibratingWalk && hr > 0) {
                    val nowTs = System.currentTimeMillis()
                    synchronized(calibSamples) {
                        calibSamples.add(CalibSample(nowTs, hr))
                        val cutoff = nowTs - 5 * 60_000L
                        val it = calibSamples.iterator()
                        while (it.hasNext()) {
                            if (it.next().timestampMs < cutoff) it.remove()
                        }
                    }
                }

                // Live-Filter, QC, Zonen, v_set und CSV-Logging
                if (flightLogging && hr > 0) {
                    val now = SystemClock.elapsedRealtime()  // Monotone Clock statt currentTimeMillis

                    // ================================================================
                    // A) COMPUTE ONCE PER CALLBACK (außerhalb der Gates)
                    // ================================================================
                    val hrSmooth = hrProcessor.process(hr, rrList)
                    val qc = hrProcessor.getQcStatus()

                    // Connection-Wechsel Events
                    val cs = qc.connectionStable
                    if (lastConnectionStable == null) {
                        lastConnectionStable = cs
                    } else if (lastConnectionStable != cs) {
                        lastConnectionStable = cs
                        val event = if (cs) "QC_CONNECTION_RESTORED" else "QC_CONNECTION_LOST"
                        csvLogger.logEvent(event)
                    }

                    // Zonen-Berechnung
                    val zoneInfo = zoneManager.getZoneWithPosition(hrSmooth, now)
                    val currentZone = zoneInfo.zone

                    handleRedAudioAlert(currentZone, flightLogging, now)

                    // SpeedController-Updates (EINMAL pro Callback)
                    val vSetV0 = speedControllerV0.update(currentZone, hrSmooth, now)
                    val vSetV1 = speedControllerV1.update(currentZone, hrSmooth, now)
                    val vSetV2 = speedControllerV2.update(zoneInfo, hrSmooth, now)

                    val vSet = when (speedControlVersion) {
                        SpeedControlVersion.ORIGINAL -> vSetV0
                        SpeedControlVersion.TREND    -> vSetV1
                        SpeedControlVersion.OPTIMAL  -> vSetV2
                    }
                    val vCmd = vSet.coerceAtMost(vSetCap)

                    // AltitudeController-Updates (EINMAL pro Callback) - Prototype 2
                    val altTargetA0 = altitudeControllerA0.update(currentZone, hrSmooth, now)
                    val altTargetA1 = altitudeControllerA1.update(currentZone, hrSmooth, now)
                    val altTargetA2 = altitudeControllerA2.update(zoneInfo, hrSmooth, now)

                    val altTarget = when (altitudeControlVersion) {
                        AltitudeControlVersion.A0 -> altTargetA0
                        AltitudeControlVersion.A1 -> altTargetA1
                        AltitudeControlVersion.A2 -> altTargetA2
                    }

                    // HR-Trend berechnen
                    val hrTrend = hrSmooth - lastHrSmooth
                    lastHrSmooth = hrSmooth

                    // ================================================================
                    // B) SIMULATION GATE (5 Hz) - flüssige Visualisierung
                    // ================================================================
                    if (now - lastSimSentAtMs >= simIntervalMs) {
                        // UI-Feedback aktualisieren
                        updateBioadaptiveFeedbackUI(currentZone, vCmd)

                        // Simulation senden
                        sendToSimulationIfEnabled(
                            hrRaw = hr,
                            hrSmooth = hrSmooth,
                            zone = currentZone,
                            vSetCmd = vCmd,
                            qcState = qc.qcState,
                            connectionStable = qc.connectionStable,
                            segmentId = segmentId
                        )
                       // csvLogger.logEvent("LATENCY_SIM: ${System.currentTimeMillis() - tsMs}")

                        // Drohnensteuerung auf 5 Hz für flüssige Regelung
                        if (PrototypeConfig.requiresRealDrone() && !noDroneMode && !safetyLandingActive) {
                            if (hrAutopilotEnabled) {
                                when (PrototypeConfig.currentPrototype) {
                                    PrototypeType.OUTDOOR -> {
                                        // Prototype 3: Horizontale HR-Steuerung nur wenn kein Override
                                        if (!manualOverrideActive) {
                                            //flightDriver?.setForwardSpeed(vCmd)
                                           // csvLogger.logEvent("LATENCY_SIM: ${System.currentTimeMillis() - tsMs}")
                                        } else {
                                            Log.d("JOYSTICK",
                                                "Override active: pitch=$jsRightY, roll=$jsRightX, yaw=$jsLeftX, vert=$jsLeftY")
                                        }
                                    }
                                    PrototypeType.INDOOR_ALTITUDE -> {
                                        // Prototype 2: Höhenregelung läuft auch während manueller Korrekturen weiter
                                        altitudeDriver?.updateAltitude(
                                            targetAltitude = altTarget,
                                            currentAltitude = currentAltitude,
                                            nowMs = System.currentTimeMillis()
                                        )
                                        //csvLogger.logEvent("LATENCY_SIM: ${System.currentTimeMillis() - tsMs}")
                                        if (manualOverrideActive) {
                                            Log.d("JOYSTICK",
                                                "Manual correction active (P2): pitch=$jsRightY, roll=$jsRightX, yaw=$jsLeftX")
                                        }
                                    }
                                    PrototypeType.SIMULATION -> {
                                        // Keine Real-Drohnenkommandos im Simulation-Prototyp
                                    }
                                }
                            }
                        }

                        lastSimSentAtMs = now
                    }

                    // ================================
                    // C) CSV-LOGGING GATE (1 Hz) - Datenerfassung
                    // ================================
                    if (now - lastHrLoggedAtMs >= logIntervalMs) {

                        // Phase/Mode/State für Logging
                        val phase = if (isLogging) "CONTROL_WALK" else if (flightLogging) "DRONE_WALK" else "IDLE"
                        val mode = if (manualOverrideActive) "MANUAL" else "AUTO"
                        val state = if (isLogging) "WALK_TEST" else {
                            if (manualOverrideActive) "MANUAL_OVERRIDE" else "FLIGHT"
                        }

                        // FIX pauseActive von der AKTIVEN Version loggen
                        val pauseActive = if (PrototypeConfig.usesVerticalControl()) {
                            when (altitudeControlVersion) {
                                AltitudeControlVersion.A0 -> altitudeControllerA0.isPauseActive()
                                AltitudeControlVersion.A1 -> altitudeControllerA1.isPauseActive()
                                AltitudeControlVersion.A2 -> altitudeControllerA2.isPauseActive()
                            }
                        } else {
                            when (speedControlVersion) {
                                SpeedControlVersion.ORIGINAL -> speedControllerV0.isPauseActive()
                                SpeedControlVersion.TREND    -> speedControllerV1.isPauseActive()
                                SpeedControlVersion.OPTIMAL  -> speedControllerV2.isPauseActive()
                            }
                        }

                        //val now = SystemClock.elapsedRealtime()
                        val elapsedMs = elapsedSinceSessionStartMs(now)
                        // CSV-Logging
                        csvLogger.logHrSampleExtended(
                            prototypeType = PrototypeConfig.currentPrototype.csvIdentifier,
                            elapsedMs = elapsedMs,
                            relativePosition = zoneInfo.relativePosition,
                            manualOverrideActive = manualOverrideActive,
                            phase = phase,
                            hrRaw = hr,
                            hrSmooth = hrSmooth,
                            rrCount = rrList.size,
                            validRatioOverall = qc.validRatioOverall,
                            rrInPerSec = qc.rrInPerSec,
                            rrValidPerSec = qc.rrValidPerSec,
                            rrValidRatioTick = qc.rrValidRatioTick,
                            msSinceLastBle = qc.msSinceLastBle,
                            connectionStable = qc.connectionStable,
                            qcState = qc.qcState.name,
                            zone = currentZone.name.lowercase(),
                            vSet = vCmd,
                            state = state,
                            baselineHr = baselineHr,
                            hrTrend = hrTrend,
                            pauseActive = pauseActive,
                            mode = mode,
                            segmentId = segmentId,
                            speedControlVersion = getCurrentVersionString(),
                            vSetV0 = vSetV0,
                            vSetV1 = vSetV1,
                            vSetV2 = vSetV2,
                            artifactsFilteredCountTick = qc.artifactsFilteredCountTick,
                            altitudeControlVersion = if (PrototypeConfig.usesVerticalControl())
                                getCurrentAltitudeVersionString() else null,
                            altTargetA0 = if (PrototypeConfig.usesVerticalControl()) altTargetA0 else null,
                            altTargetA1 = if (PrototypeConfig.usesVerticalControl()) altTargetA1 else null,
                            altTargetA2 = if (PrototypeConfig.usesVerticalControl()) altTargetA2 else null,
                            altCurrent = if (PrototypeConfig.usesVerticalControl()) currentAltitude else null
                        )
                        lastHrLoggedAtMs = now
                    }
                }

                // ================================================================
                // SIMULATION ohne flightLogging (für Prototype 1 Tests ohne CSV)
                // ================================================================
                if (!flightLogging && hr > 0) {
                    val now = SystemClock.elapsedRealtime()
                    val hrSmooth = hrProcessor.process(hr, rrList)
                    val qc = hrProcessor.getQcStatus()
                    val zoneInfo = zoneManager.getZoneWithPosition(hrSmooth, now)
                    val currentZone = zoneInfo.zone

                    // SpeedController für vCmd berechnen
                    val vSetV0 = speedControllerV0.update(currentZone, hrSmooth, now)
                    val vSetV1 = speedControllerV1.update(currentZone, hrSmooth, now)
                    val vSetV2 = speedControllerV2.update(zoneInfo, hrSmooth, now)
                    val vSet = when (speedControlVersion) {
                        SpeedControlVersion.ORIGINAL -> vSetV0
                        SpeedControlVersion.TREND    -> vSetV1
                        SpeedControlVersion.OPTIMAL  -> vSetV2
                    }
                    val vCmd = vSet.coerceAtMost(vSetCap)

                    // Simulation + UI (alle 200ms)
                    if (now - lastSimSentAtMs >= simIntervalMs) {
                        updateBioadaptiveFeedbackUI(currentZone, vCmd)
                        sendToSimulationIfEnabled(
                            hrRaw = hr,
                            hrSmooth = hrSmooth,
                            zone = currentZone,
                            vSetCmd = vCmd,
                            qcState = qc.qcState,
                            connectionStable = qc.connectionStable,
                            segmentId = segmentId
                        )
                        lastSimSentAtMs = now
                    }
                }
            },
            onStatus = { msg -> Log.i("BLE", msg)
                if (csvLogger.getCurrentSessionId().isNotEmpty()) {
                    csvLogger.logEvent("BLE_STATUS: $msg")
                }
            }
        )
    }

    @SuppressLint("SetTextI18n")
    internal fun startWatchdog() {
        // Guard: verhindert doppelte Watchdog-Loops (z.B. nach Activity-Restart / UI-Races)
        if (watchdogHandler != null || watchdogRunnable != null) {
            Log.i("WATCHDOG", "Already running - skip start")
            return
        }
        watchdogHandler = Handler(Looper.getMainLooper())
        watchdogRunnable = object : Runnable {
            override fun run() {
                if (flightLogging) {
                    val qc = hrProcessor.getQcStatus()

                    if (lastQcState != qc.qcState) {
                        csvLogger.logEvent("QC_STATE_${qc.qcState.name}")
                        lastQcState = qc.qcState
                        Log.i("WATCHDOG", "QC-State: ${qc.qcState.name}")
                    }

                    runOnUiThread {
                        val stateSymbol = when(qc.qcState) {
                            QcState.OK -> "✓"
                            QcState.DEGRADED -> "⚠"
                            QcState.LOST -> "✗"
                        }
                        qcStatusTxt.text = "$stateSymbol RR: ${qc.rrInPerSec}->${qc.rrValidPerSec}"
                        qcStatusTxt.setTextColor(when(qc.qcState) {
                            QcState.OK -> Color.GREEN
                            QcState.DEGRADED -> android.graphics.Color.rgb(255, 165, 0)
                            QcState.LOST -> Color.RED
                        })
                    }
                }
                watchdogHandler?.postDelayed(this, 1000)
            }
        }
        watchdogHandler?.post(watchdogRunnable!!)
        Log.i("WATCHDOG", "Started")
    }

    internal fun stopWatchdog() {
        watchdogRunnable?.let { watchdogHandler?.removeCallbacks(it) }
        watchdogHandler = null
        watchdogRunnable = null
        Log.i("WATCHDOG", "Stopped")
        lastQcState = null
    }

    // Setup für HRV Duration Spinner
    internal fun setupHrvDurationSpinner() {
        val durations = resources.getIntArray(R.array.hrv_duration_values)

        // Standard: 180s (Index 2)
        hrvDurationSpinner.setSelection(2)

        hrvDurationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                measureSeconds = durations[position]
                Log.i("HRV", "Measurement changed: ${measureSeconds}s")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // Bioadaptives Feedback UI Update
    internal fun updateBioadaptiveFeedbackUI(zone: Zone, vCmd: Float) {
        runOnUiThread {
            // Zone Indicator aktualisieren
            zoneIndicatorTxt.text = zone.name

            val (color, backgroundColor) = when(zone) {
                Zone.GREEN  -> "#00FF00".toColorInt() to "#1A00FF00".toColorInt()
                Zone.YELLOW -> "#FFFF00".toColorInt() to "#1AFFFF00".toColorInt()
                Zone.RED    -> "#FF0000".toColorInt() to "#1AFF0000".toColorInt()
            }

            zoneIndicatorTxt.setTextColor(color)
            zoneIndicatorTxt.setBackgroundColor(backgroundColor)

            // Speed Recommendation (nur im No-Drone-Modus sichtbar)
            // Speed nur im Drohnenflug (DRONE_WALK) anzeigen
            // if (!isLogging && flightLogging) {
            if (flightLogging) {
                speedRecommendationTxt.visibility = View.VISIBLE
                speedRecommendationTxt.text = getString(
                    R.string.speed_recommendation_format,
                    vCmd
                )

                // Farbe basierend auf Zone
                speedRecommendationTxt.setBackgroundColor(backgroundColor)
            }

            // Haptisches Feedback bei Zonenwechsel
            if (zone != lastZone) {
                triggerZoneChangeVibration(zone)
                lastZone = zone
            }
        }
    }

    // Vibration bei Zonenwechsel
    internal fun triggerZoneChangeVibration(newZone: Zone) {
        if (!vibrator.hasVibrator()) return

        val pattern = when(newZone) {
            Zone.YELLOW -> longArrayOf(0, 100)       // 1x kurz
            Zone.RED -> longArrayOf(0, 150, 100, 150) // 2x kurz (Warnung)
            Zone.GREEN -> return // Keine Vibration bei GREEN
        }

        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    override fun onStart() {
        super.onStart()

        // Polar: Auto-Scan einmal starten, damit Sensor verbindet ohne PreFlight/Logging
        if (!polarAutoScanStarted) {
            if (hasBleRuntimePermissions()) {
                polarAutoScanStarted = true
                polar.startScan()
                Log.i("POLAR", "Auto-start scan (onStart)")
            } else {
                Log.i("POLAR", "Auto-start scan skipped: missing BLE permissions")
            }
        }

        groundSdk.getFacility(AutoConnection::class.java) { it ->
            it?.let {
                if (it.status != AutoConnection.Status.STARTED) it.start()

                if (drone?.uid != it.drone?.uid) {
                    if (drone != null) { stopDroneMonitors(); resetDroneUi() }
                    drone = it.drone
                    if (drone != null) startDroneMonitors()
                }

                if (rc?.uid != it.remoteControl?.uid) {
                    if (rc != null) { stopRcMonitors(); resetRcUi() }
                    rc = it.remoteControl
                    if (rc != null) startRcMonitors()
                }
            }
        }
    }

    override fun onDestroy() {
        // Cleanup: verhindert Handler-Leaks und doppelte Background-Loops nach Activity-Restarts
        stopWatchdog()
        timer?.cancel()

        // BLE sauber schließen
        runCatching { if (::polar.isInitialized) polar.disconnect() }

        // WebSocket/OkHttp sauber schließen
        if (::simulationClient.isInitialized) {
            simulationClient.cleanup()
        }

        runCatching { redTone.release() }
        super.onDestroy()
    }

    /** ---------------- HRV: Start/Stop ---------------- */


    /** ---------------- BLE Runtime Permissions ---------------- */

    internal fun hasBleRuntimePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 6-11: BLE-Scan erfordert Location-Permission
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    internal fun ensureBlePermissions() {
        val toRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 31) {
            val list = arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN
            )
            for (p in list) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    toRequest += p
                }
            }
        } else {
            val p = android.Manifest.permission.ACCESS_FINE_LOCATION
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                toRequest += p
            }
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != 1001) return

        val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        if (allGranted) {
            Log.i("BLE", "BLE permissions granted -> starting Polar scan")
            runCatching {
                if (::polar.isInitialized) {
                    polarAutoScanStarted = true
                    polar.startScan()
                }
            }
        } else {
            val denied = permissions.filterIndexed { idx, _ ->
                grantResults.getOrNull(idx) != PackageManager.PERMISSION_GRANTED
            }
            Log.w("BLE", "BLE permissions denied: $denied")
            Toast.makeText(this, "Bluetooth permissions denied - Polar connection disabled.", Toast.LENGTH_LONG).show()
        }
    }



}
