package com.vlogcam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.FrameLayout
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import android.content.ContentValues
import android.media.MediaRecorder
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.slider.Slider
import com.vlogcam.app.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO, DUAL }

    private lateinit var b: ActivityMainBinding
    private val uiExecutor by lazy { ContextCompat.getMainExecutor(this) }
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("vlogcam", Context.MODE_PRIVATE) }

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null

    // Bound state
    private var camera: Camera? = null
    private var dualCameras: List<Camera> = emptyList()
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordingPaused = false
    private var dualOwner: DisposableLifecycleOwner? = null
    private var dualRenderer: DualRenderer? = null
    private var dualPreviews: List<Preview> = emptyList()

    // Dual-mode recording (MediaRecorder fed by the GL renderer)
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var dualPfd: android.os.ParcelFileDescriptor? = null
    private var dualUri: Uri? = null
    private var dualStartMs = 0L
    private var dualPausedMs = 0L
    private var dualPauseStartMs = 0L
    private var dualStopping = false
    private val isRecording get() = recording != null || mediaRecorder != null || dualStopping

    // Physical orientation, used for photo/video rotation even when auto-rotate is off.
    private var deviceRotation = Surface.ROTATION_0
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rot = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rot != deviceRotation) {
                    deviceRotation = rot
                    imageCapture?.targetRotation = rot
                    if (recording == null) videoCapture?.targetRotation = rot
                }
            }
        }
    }
    private val dualTicker = object : Runnable {
        override fun run() {
            if (mediaRecorder == null) return
            val now = System.currentTimeMillis()
            val paused = dualPausedMs + if (recordingPaused) now - dualPauseStartMs else 0L
            val secs = (now - dualStartMs - paused) / 1000
            b.recordTime.text = recordLabel(secs)
            handler.postDelayed(this, 500)
        }
    }
    private var observedCamera: Camera? = null
    private var stabilizationSupported = false
    private var stabilizationActive = false

    // User settings
    private var mode = Mode.PHOTO
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var photoFlash = ImageCapture.FLASH_MODE_OFF
    private var torchOn = false
    private var timerSeconds = 0
    private var gridOn = false
    private var stabilizationOn = true
    private var micOn = true
    private var mirrorFront = true
    private var videoQualities: List<Quality> = emptyList()
    private var preferredQuality: Quality = Quality.FHD
    private var extensionModes: List<Int> = listOf(ExtensionMode.NONE)
    private var extensionMode = ExtensionMode.NONE

    // Dual mode settings
    private var dualLayout = DualLayout.PIP
    private var pipCorner = PipCorner.TOP_RIGHT
    private var pipSizeIndex = 1
    private var dualSwapped = false
    private var dualZoomTarget = 0
    private var dualFullHd = false
    private var dualFrame = DualFrame.AUTO
    private var dualFit = false
    private var cutoutAnalysis: androidx.camera.core.ImageAnalysis? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val segmenter by lazy {
        com.google.mlkit.vision.segmentation.Segmentation.getClient(
            com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions.Builder()
                .setDetectorMode(com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions.STREAM_MODE)
                .enableRawSizeMask()
                .build()
        )
    }

    private var lastMediaUri: Uri? = null
    private var lastMediaIsVideo = false
    private var countdownRunnable: Runnable? = null
    private var zoomSliderDragging = false

    // In-app updates
    private var availableUpdate: Updater.Update? = null
    private var downloadedApk: java.io.File? = null
    private var updateBusy = false
    private var lastUpdateCheck = 0L
    private var installAfterPermission = false

    private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermission(Manifest.permission.CAMERA)) {
                b.messageText.visibility = View.GONE
                startCamera()
            } else {
                showMessage("Camera permission is needed.\nGrant it in Settings → Apps → VlogCam.")
            }
        }

    private val exporter by lazy { Exporter(this) }
    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) chooseExportPreset(uri)
        }
    private val pickTrimVideo =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) showTrimDialog(uri)
        }
    private val pickJoinVideos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
            when {
                uris.isEmpty() -> Unit
                uris.size < 2 -> Toast.makeText(this, "Pick at least 2 videos to join", Toast.LENGTH_SHORT).show()
                else -> confirmJoin(uris)
            }
        }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) = updateTargetRotation()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, b.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.controls) { v, insets ->
            val i = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }

        loadPrefs()
        dualRenderer = DualRenderer()
        applyDualSettings()
        b.dualSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                dualRenderer?.setDisplaySurface(holder.surface, width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                dualRenderer?.setDisplaySurface(null, 0, 0)
            }
        })
        b.root.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, or, ob ->
            if (r - l != or - ol || bt - t != ob - ot) b.root.post { layoutDualSurface() }
        }
        setupControls()
        setupGestures()
        refreshUi()

        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, handler)

        if (hasPermission(Manifest.permission.CAMERA)) startCamera()
        else permissionLauncher.launch(permissions)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateTargetRotation()
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
        handler.removeCallbacks(healthMonitor)
        handler.post(healthMonitor)
        handler.post { updateHud() }
        if (installAfterPermission && Updater.canInstall(this)) {
            installAfterPermission = false
            downloadedApk?.let { launchInstaller(it) }
        }
        if (System.currentTimeMillis() - lastUpdateCheck > 10 * 60 * 1000L) checkForUpdate()
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
        handler.removeCallbacks(healthMonitor)
        exitRideMode()
        savePrefs()
        cancelCountdown()
    }

    override fun onStart() {
        super.onStart()
        handler.post { updateHud(); updateHorizonSensor() }
    }

    override fun onStop() {
        super.onStop()
        if (mediaRecorder != null) stopDualRecording()
        handler.post { updateHud(); updateHorizonSensor() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { segmenter.close() }
        runCatching { toneGen?.release() }
        analysisExecutor.shutdown()
        dualRenderer?.release()
        dualRenderer = null
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
        recording?.stop()
        ioExecutor.shutdown()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Only the phone's own volume/camera keys (and Bluetooth selfie shutters, which send
        // volume-up). Headset/intercom media buttons are left alone so they keep controlling music.
        val action = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CAMERA ->
                RemoteAction.SHUTTER
            else -> null
        } ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) onRemote(action, keyCode)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CAMERA -> true
        else -> super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------- remote control & ride mode

    private enum class RemoteAction { SHUTTER, SNAPSHOT, SWITCH }

    private val toneGen by lazy {
        runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90) }.getOrNull()
    }

    /** Short sounds (heard in a Bluetooth helmet) + vibration, so you know it worked without looking. */
    private fun feedback(kind: String) {
        val tone = when (kind) {
            "start" -> android.media.ToneGenerator.TONE_PROP_BEEP
            "stop" -> android.media.ToneGenerator.TONE_PROP_BEEP2
            "snap" -> android.media.ToneGenerator.TONE_PROP_ACK
            "fast_on" -> android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
            "fast_off" -> android.media.ToneGenerator.TONE_PROP_NACK
            else -> android.media.ToneGenerator.TONE_SUP_ERROR
        }
        // Only safety warnings make a sound (it would otherwise play over your music).
        if (kind == "warn") runCatching { toneGen?.startTone(tone, 600) }
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
        val pattern = when (kind) {
            "start" -> longArrayOf(0, 120)
            "stop" -> longArrayOf(0, 90, 120, 90)
            "snap" -> longArrayOf(0, 40)
            "fast_on" -> longArrayOf(0, 60, 80, 60, 80, 60)
            "fast_off" -> longArrayOf(0, 200)
            else -> longArrayOf(0, 300, 150, 300, 150, 300)
        }
        runCatching { vib.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun onRemote(action: RemoteAction, keyCode: Int) {
        val isVolumeOrCamera = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA
        when (action) {
            RemoteAction.SHUTTER -> {
                val wasRecording = isRecording
                onShutter()
                if (!isVolumeOrCamera) {
                    when {
                        mode == Mode.PHOTO -> feedback("snap")
                        wasRecording -> feedback("stop")
                        else -> feedback("start")
                    }
                }
            }
            RemoteAction.SNAPSHOT -> {
                // While recording, "Next" (a double-press on most intercoms) marks fast-forward parts.
                if (isRecording && mode != Mode.PHOTO) {
                    toggleFastForward()
                    return
                }
                if (mode == Mode.DUAL) takeDualSnapshot() else if (mode == Mode.PHOTO) takePhoto() else return
                feedback("snap")
            }
            RemoteAction.SWITCH -> {
                switchCamera()
                feedback("snap")
            }
        }
    }

    // Mic, wind filter, speed overlay
    private var micChoice = "phone"
    private var windLevel = 0
    private var overlayOn = false
    private var overlayCorner = PipCorner.BOTTOM_LEFT
    private var bluetoothMicActive = false
    private var dualHasAudio = false
    private var dualNextUri: Uri? = null
    private var dualNextPfd: android.os.ParcelFileDescriptor? = null
    private val windFilter by lazy {
        WindFilter(this) { _, ok ->
            if (ok) Toast.makeText(this, "🌬 Wind noise reduced", Toast.LENGTH_SHORT).show()
        }
    }
    private var hud: RideHud? = null
    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r.values.any { it }) updateHud()
            else {
                overlayOn = false
                Toast.makeText(this, "Location permission is needed for the speed overlay", Toast.LENGTH_LONG).show()
                refreshUi()
            }
        }

    private fun hasLocationPermission() =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Starts/stops GPS + overlay drawing as needed (dual mode, overlay on, app visible). */
    private fun updateHud() {
        val want = (overlayOn || speedBlurLevel > 0) && mode == Mode.DUAL && hasLocationPermission() &&
            // STARTED, not RESUMED: keep tracking through brief interruptions such as the
            // notification shade, a permission prompt or a heads-up notification.
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        if (want && hud == null) {
            hud = RideHud(this) { bmp ->
                if (overlayOn) dualRenderer?.setOverlay(bmp)
                updateSpeedBlur()
            }.also {
                it.setCounting(mediaRecorder != null)
                it.start()
            }
        } else if (!want && hud != null) {
            // Keep the overlay running during a recording even if briefly paused.
            if (mediaRecorder != null && (overlayOn || speedBlurLevel > 0)) return
            hud?.stop()
            hud = null
        }
        // Never leave a stale (frozen) panel on screen when tracking isn't running.
        if (hud == null || !overlayOn) dualRenderer?.setOverlay(null)
        dualRenderer?.overlayCorner = overlayCorner
        updateSpeedBlur()
    }

    private var speedBlurLevel = 0  // 0 = off, 1 = subtle, 2 = strong
    private var ffMotionBlur = true

    /** Speed blur strength follows GPS speed: none below 15 km/h, full at 90 km/h. */
    private fun updateSpeedBlur() {
        val kmh = hud?.currentKmh() ?: 0f
        val ramp = ((kmh - 15f) / 75f).coerceIn(0f, 1f)
        val max = when (speedBlurLevel) { 1 -> 0.045f; 2 -> 0.085f; else -> 0f }
        dualRenderer?.speedBlur = if (mode == Mode.DUAL) max * ramp else 0f
    }

    // ---------------------------------------------------------------- fast-forward sections

    // ---------------------------------------------------------------- horizon levelling

    private var horizonMode = 0  // 0 = off, 1 = level mount, 2 = lock horizon
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager }
    private var horizonRegistered = false
    private var levelAngle = 0f
    private var levelZoomAngle = 0f
    private var lastHorizonNs = 0L
    private val rotMatrix = FloatArray(9)

    private val horizonListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val upX: Float
            val upY: Float
            if (e.sensor.type == android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR) {
                android.hardware.SensorManager.getRotationMatrixFromVector(rotMatrix, e.values)
                upX = rotMatrix[6]   // world "up" expressed in phone coordinates
                upY = rotMatrix[7]
            } else {
                upX = e.values[0]    // gravity sensor: points up when the phone is still
                upY = e.values[1]
            }
            if (upX * upX + upY * upY < 0.05f) return  // phone lying flat: no horizon to level
            // Phone tilt (clockwise +), relative to the current screen orientation.
            var err = Math.toDegrees(kotlin.math.atan2(-upX, upY).toDouble()).toFloat() +
                (dualRenderer?.displayRotationDeg ?: 0)
            while (err > 180f) err -= 360f
            while (err < -180f) err += 360f
            val maxDeg = if (horizonMode == 2) 15f else 8f
            val target = if (abs(err) > 30f) 0f else err.coerceIn(-maxDeg, maxDeg)

            val now = e.timestamp
            val dt = if (lastHorizonNs == 0L) 0.02f else ((now - lastHorizonNs) / 1e9f).coerceIn(0f, 0.2f)
            lastHorizonNs = now
            val tau = if (horizonMode == 2) 0.25f else 1.2f
            levelAngle += (target - levelAngle) * (1f - kotlin.math.exp(-dt / tau))
            // Zoom grows immediately but relaxes slowly, so the picture doesn't pump in and out.
            val need = abs(levelAngle)
            levelZoomAngle = if (need > levelZoomAngle) need
            else levelZoomAngle + (need - levelZoomAngle) * (1f - kotlin.math.exp(-dt / 3f))
            dualRenderer?.levelAngleDeg = levelAngle
            dualRenderer?.levelZoomAngleDeg = levelZoomAngle
        }

        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    /** Runs the tilt sensor only when levelling is on, in Dual/Vlog mode, with the app visible. */
    private fun updateHorizonSensor() {
        val want = horizonMode > 0 && mode == Mode.DUAL &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        if (want && !horizonRegistered) {
            val sensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
            if (sensor != null) {
                lastHorizonNs = 0L
                horizonRegistered = sensorManager.registerListener(
                    horizonListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME
                )
            }
        } else if (!want && horizonRegistered) {
            sensorManager.unregisterListener(horizonListener)
            horizonRegistered = false
        }
        if (!horizonRegistered) {
            levelAngle = 0f
            levelZoomAngle = 0f
            dualRenderer?.levelAngleDeg = 0f
            dualRenderer?.levelZoomAngleDeg = 0f
        }
    }

    private var sharpOn = false
    private var soloSlot = 0
    private val sharp by lazy { SharpMotion { dualRenderer } }

    /** Cameras that Sharp motion should control right now, with their renderer slot. */
    private fun sharpTargets(): List<Pair<Camera, Int>> = when (mode) {
        Mode.PHOTO -> emptyList()
        Mode.VIDEO -> listOfNotNull(camera?.let { it to -1 })
        Mode.DUAL -> if (dualLayout == DualLayout.SOLO) {
            listOfNotNull(dualCameras.firstOrNull()?.let { it to soloSlot })
        } else dualCameras.mapIndexed { i, c -> c to i }
    }

    private fun applySharp() {
        if (sharpOn) {
            sharp.enabled = true
            sharp.setCameras(sharpTargets())
        } else {
            sharp.setCameras(emptyList())
        }
    }

    private var fastSpeed = 8
    private var fastActive = false
    private var fastStartMs = 0L
    private val fastRanges = mutableListOf<Pair<Long, Long>>()
    private var segmentBaseMs = 0L
    private var cameraxRecordedMs = 0L
    private var cameraxPendingRanges: List<Pair<Long, Long>>? = null
    private val bgExporter by lazy { Exporter(this) }
    private val speedQueue = ArrayDeque<Pair<Uri, List<Pair<Long, Long>>>>()
    private var speedBusy = false

    private fun recordLabel(secs: Long): String {
        val dot = if (recordingPaused) "❚❚" else "●"
        val base = String.format(Locale.US, "%s %02d:%02d", dot, secs / 60, secs % 60)
        return if (fastActive) "$base   ⏩ ${fastSpeed}x" else base
    }

    /** Recorded time (excluding pauses) in the current file, in ms. */
    private fun segmentMs(): Long {
        if (mediaRecorder != null) {
            val now = System.currentTimeMillis()
            val paused = dualPausedMs + if (recordingPaused) now - dualPauseStartMs else 0L
            return now - dualStartMs - paused - segmentBaseMs
        }
        return cameraxRecordedMs
    }

    private fun resetFastForward() {
        fastActive = false
        fastStartMs = 0L
        fastRanges.clear()
        segmentBaseMs = 0L
        cameraxRecordedMs = 0L
    }

    private fun closeFastRange(endMs: Long) {
        if (endMs - fastStartMs >= 1500) fastRanges += fastStartMs to endMs
    }

    /** Ends any open section and returns this file's ranges, leaving the list empty. */
    private fun takeFastRanges(): List<Pair<Long, Long>> {
        if (fastActive) closeFastRange(segmentMs())
        fastActive = false
        return fastRanges.toList().also { fastRanges.clear() }
    }

    private fun toggleFastForward() {
        if (!isRecording || dualStopping) {
            Toast.makeText(this, "Start recording, then tap ⏩ over the parts to speed up", Toast.LENGTH_SHORT).show()
            return
        }
        if (fastActive) {
            closeFastRange(segmentMs())
            fastActive = false
        } else {
            fastStartMs = segmentMs()
            fastActive = true
        }
        feedback(if (fastActive) "fast_on" else "fast_off")
        refreshUi()
    }

    /** After a file is saved: wind filter (in place), then the fast-forward copy if marked. */
    private fun postProcess(uri: Uri, audio: Boolean, ranges: List<Pair<Long, Long>>) {
        val speedJob = {
            if (ranges.isNotEmpty()) {
                speedQueue.add(uri to ranges)
                runSpeedQueue()
            }
        }
        if (audio && windLevel > 0) windFilter.enqueue(uri, windLevel) { speedJob() } else speedJob()
    }

    private fun runSpeedQueue() {
        if (speedBusy) return
        val (uri, ranges) = speedQueue.removeFirstOrNull() ?: return
        speedBusy = true
        bgExporter.speedUp(uri, ranges, fastSpeed.toFloat(), {}, motionBlur = ffMotionBlur) { out, error ->
            speedBusy = false
            if (out != null) {
                Toast.makeText(this, "⏩ Fast-forward version saved (Movies/VlogCam/Exports)", Toast.LENGTH_LONG).show()
                setLastMedia(out, true)
            } else {
                Toast.makeText(this, "Fast-forward failed: ${error ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
            runSpeedQueue()
        }
    }

    private var rideAuto = false
    private var rideActive = false
    private val rideEnter = Runnable { if (rideAuto && isRecording) enterRideMode() }
    private val rideTicker = object : Runnable {
        override fun run() {
            if (!rideActive) return
            b.rideText.text = b.recordTime.text
            handler.postDelayed(this, 1000)
        }
    }

    /** Restarts the "go dark after a few seconds without touches" timer while recording. */
    private fun scheduleRideMode() {
        handler.removeCallbacks(rideEnter)
        if (rideAuto && isRecording && !rideActive) handler.postDelayed(rideEnter, RIDE_DELAY_MS)
    }

    private fun enterRideMode() {
        if (rideActive) return
        rideActive = true
        b.rideOverlay.visibility = View.VISIBLE
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        dualRenderer?.displayPaused = true
        handler.post(rideTicker)
    }

    private fun exitRideMode() {
        handler.removeCallbacks(rideEnter)
        if (!rideActive) return
        rideActive = false
        b.rideOverlay.visibility = View.GONE
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        dualRenderer?.displayPaused = false
        handler.removeCallbacks(rideTicker)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (rideActive) {
                // First tap only wakes the screen.
                exitRideMode()
                scheduleRideMode()
                return true
            }
            scheduleRideMode()
        }
        if (rideActive) return true
        return super.dispatchTouchEvent(ev)
    }

    /** Called whenever recording starts or stops. */
    private fun onRecordingStateChanged() {
        if (isRecording) scheduleRideMode() else exitRideMode()
    }

    // ---------------------------------------------------------------- heat, battery & storage

    private var lastWarning = ""
    private val healthMonitor = object : Runnable {
        override fun run() {
            checkHealth()
            handler.postDelayed(this, if (isRecording) 5_000 else 15_000)
        }
    }

    private fun thermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus
        } else 0

    private fun freeStorageBytes(): Long = runCatching {
        android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    private data class Health(val warnings: List<String>, val stopReason: String?)

    private fun readHealth(): Health {
        val warnings = mutableListOf<String>()
        var stop: String? = null

        val thermal = thermalStatus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                thermal >= android.os.PowerManager.THERMAL_STATUS_CRITICAL ->
                    stop = "Phone is overheating"
                thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE ->
                    warnings += "🔥 Phone very hot – shade it or use 720p"
                thermal >= android.os.PowerManager.THERMAL_STATUS_MODERATE ->
                    warnings += "🌡 Phone getting warm"
            }
        }

        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            val status = battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
            if (!charging) {
                if (pct <= 4) stop = stop ?: "Battery almost empty"
                else if (pct <= 15) warnings += "🔋 Battery $pct%"
            }
        }

        val free = freeStorageBytes()
        if (free < MIN_FREE_BYTES) stop = stop ?: "Storage almost full"
        else if (free < LOW_FREE_BYTES) {
            warnings += String.format(Locale.US, "💾 %.1f GB free", free / 1e9)
        }
        return Health(warnings, stop)
    }

    private fun checkHealth() {
        val health = readHealth()
        val text = (health.warnings + listOfNotNull(health.stopReason)).joinToString("  ·  ")
        b.warningBanner.text = text
        b.warningBanner.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        if (text.isNotEmpty() && text != lastWarning && isRecording) feedback("warn")
        lastWarning = text

        val reason = health.stopReason
        if (reason != null && isRecording) {
            // Stop cleanly so the video is saved, rather than the phone killing the camera.
            exitRideMode()
            if (mediaRecorder != null) stopDualRecording() else stopRecording()
            feedback("warn")
            Toast.makeText(this, "$reason – recording stopped and saved", Toast.LENGTH_LONG).show()
        }
    }

    /** Returns false (with a message) if it isn't safe to start recording. */
    private fun canStartRecording(): Boolean {
        val reason = readHealth().stopReason ?: return true
        feedback("warn")
        Toast.makeText(this, "$reason – can't start recording", Toast.LENGTH_LONG).show()
        return false
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- prefs

    private fun loadPrefs() {
        mode = runCatching { Mode.valueOf(prefs.getString("mode", Mode.PHOTO.name)!!) }
            .getOrDefault(Mode.PHOTO)
        gridOn = prefs.getBoolean("grid", false)
        micOn = prefs.getBoolean("mic", true)
        mirrorFront = prefs.getBoolean("mirror", true)
        stabilizationOn = prefs.getBoolean("stab", true)
        timerSeconds = prefs.getInt("timer", 0)
        dualFullHd = prefs.getBoolean("dualFhd", false)
        dualFit = prefs.getBoolean("dualFit", false)
        rideAuto = prefs.getBoolean("rideAuto", true)
        fastSpeed = prefs.getInt("fastSpeed", 8)
        sharpOn = prefs.getBoolean("sharp", false)
        horizonMode = prefs.getInt("horizon", 0).coerceIn(0, 2)
        speedBlurLevel = prefs.getInt("speedBlur", 0).coerceIn(0, 2)
        ffMotionBlur = prefs.getBoolean("ffBlur", true)
        micChoice = prefs.getString("micChoice", "phone")!!
        windLevel = prefs.getInt("wind", 0)
        overlayOn = prefs.getBoolean("overlay", false)
        overlayCorner = runCatching { PipCorner.valueOf(prefs.getString("overlayCorner", "BOTTOM_LEFT")!!) }
            .getOrDefault(PipCorner.BOTTOM_LEFT)
        dualFrame = runCatching { DualFrame.valueOf(prefs.getString("dualFrame", "AUTO")!!) }
            .getOrDefault(DualFrame.AUTO)
        dualLayout = runCatching { DualLayout.valueOf(prefs.getString("dualLayout", "PIP")!!) }
            .getOrDefault(DualLayout.PIP)
        pipCorner = runCatching { PipCorner.valueOf(prefs.getString("pipCorner", "TOP_RIGHT")!!) }
            .getOrDefault(PipCorner.TOP_RIGHT)
        pipSizeIndex = prefs.getInt("pipSize", 1).coerceIn(0, DualComposer.PIP_SIZES.size - 1)
        preferredQuality = qualityFromName(prefs.getString("quality", "FHD")!!)
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("mode", mode.name)
            .putBoolean("grid", gridOn)
            .putBoolean("mic", micOn)
            .putBoolean("mirror", mirrorFront)
            .putBoolean("stab", stabilizationOn)
            .putInt("timer", timerSeconds)
            .putBoolean("dualFhd", dualFullHd)
            .putBoolean("dualFit", dualFit)
            .putBoolean("rideAuto", rideAuto)
            .putInt("fastSpeed", fastSpeed)
            .putBoolean("sharp", sharpOn)
            .putInt("horizon", horizonMode)
            .putInt("speedBlur", speedBlurLevel)
            .putBoolean("ffBlur", ffMotionBlur)
            .putString("micChoice", micChoice)
            .putInt("wind", windLevel)
            .putBoolean("overlay", overlayOn)
            .putString("overlayCorner", overlayCorner.name)
            .putString("dualFrame", dualFrame.name)
            .putString("dualLayout", dualLayout.name)
            .putString("pipCorner", pipCorner.name)
            .putInt("pipSize", pipSizeIndex)
            .putString("quality", qualityName(preferredQuality))
            .apply()
    }

    // ---------------------------------------------------------------- camera setup

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                showMessage("Could not open camera: ${e.message}")
                return@addListener
            }
            cameraProvider = provider
            val extFuture = ExtensionsManager.getInstanceAsync(this, provider)
            extFuture.addListener({
                extensionsManager = runCatching { extFuture.get() }.getOrNull()
                bindCamera()
            }, uiExecutor)
        }, uiExecutor)
    }

    private fun displayRotation(): Int =
        b.previewView.display?.rotation ?: ContextCompat.getDisplayOrDefault(this).rotation

    private fun updateTargetRotation() {
        val rotation = displayRotation()
        preview?.targetRotation = rotation
        imageCapture?.targetRotation = deviceRotation
        if (recording == null) videoCapture?.targetRotation = deviceRotation
        if (mediaRecorder == null) {
            dualPreviews.forEach { it.targetRotation = rotation }
            cutoutAnalysis?.targetRotation = rotation
        }
        dualRenderer?.displayRotationDeg = rotation * 90
        layoutDualSurface()
    }

    /** Sizes the dual SurfaceView to the recording aspect (9:16 or 16:9), as large as fits. */
    private fun layoutDualSurface() {
        if (mode != Mode.DUAL || mediaRecorder != null) return
        val rw = b.root.width
        val rh = b.root.height
        if (rw == 0 || rh == 0) return
        val aspect = dualFrame.aspect(screenPortrait = rw <= rh)
        val (w, h) = if (rw.toFloat() / rh > aspect) (rh * aspect).roundToInt() to rh
        else rw to (rw / aspect).roundToInt()
        val lp = b.dualSurface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h || lp.gravity != Gravity.CENTER) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            b.dualSurface.layoutParams = lp
        }
    }

    /** Output size for dual recordings and snapshots, matching the on-screen frame. */
    private fun dualCanvasSize(fullHd: Boolean = dualFullHd): Pair<Int, Int> {
        val short = if (fullHd) 1080 else 720
        val aspect = dualFrame.aspect(screenPortrait = b.root.width <= b.root.height)
        fun even(v: Float) = (v / 2f).roundToInt() * 2
        return if (aspect <= 1f) short to even(short / aspect) else even(short * aspect) to short
    }

    private fun applyDualSettings() {
        dualRenderer?.apply {
            layout = dualLayout
            corner = pipCorner
            pipSize = DualComposer.PIP_SIZES[pipSizeIndex]
            swapped = dualSwapped
            mirrorFront = this@MainActivity.mirrorFront
            fit = dualFit
            cutoutScale = DualComposer.CUTOUT_SCALES[pipSizeIndex]
        }
    }

    /** (Re)binds use cases for the current mode. */
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (isRecording) return
        observedCamera?.cameraInfo?.zoomState?.removeObservers(this)
        observedCamera = null

        var ok = false
        // First try with stabilization, then without, so unsupported combos still work.
        for (allowStab in listOf(true, false)) {
            if (!allowStab && !stabilizationActive) break
            try {
                provider.unbindAll()
                clearBound()
                if (mode == Mode.DUAL) bindDual(provider, allowStab) else bindSingle(provider, allowStab)
                ok = true
                break
            } catch (e: DualUnsupportedException) {
                provider.unbindAll()
                clearBound()
                Toast.makeText(
                    this,
                    "This phone doesn't allow two cameras at once. Using rear camera only.",
                    Toast.LENGTH_LONG
                ).show()
                dualLayout = DualLayout.SOLO
                dualSwapped = false
                bindCamera()
                return
            } catch (e: Exception) {
                Log.e(TAG, "Bind failed (stab=$allowStab)", e)
                if (allowStab && stabilizationActive) continue
                if (mode == Mode.DUAL && dualLayout == DualLayout.CUTOUT) {
                    dualLayout = DualLayout.PIP
                    applyDualSettings()
                    Toast.makeText(
                        this, "Cutout isn't supported on this phone, using picture-in-picture",
                        Toast.LENGTH_LONG
                    ).show()
                    bindCamera()
                    return
                }
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        if (ok) {
            observeCamera(activeCamera())
            updateTargetRotation()
            applySharp()
        }
        updateHud()
        updateHorizonSensor()
        refreshUi()
    }

    private fun clearBound() {
        camera = null
        dualCameras = emptyList()
        preview = null
        imageCapture = null
        videoCapture = null
        stabilizationActive = false
        sharp.setCameras(emptyList())
        dualPreviews = emptyList()
        cutoutAnalysis = null
        // Drop the cached dual session so the next bind uses the current layout.
        dualOwner?.destroy()
        dualOwner = null
    }

    private fun bindSingle(provider: ProcessCameraProvider, allowStab: Boolean) {
        var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!provider.hasCamera(selector)) {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        }
        val info = selector.filter(provider.availableCameraInfos).first()
        val rotation = displayRotation()
        val ratio = if (mode == Mode.VIDEO) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val resolution = ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()
        val previewStabSupported =
            runCatching { Preview.getPreviewCapabilities(info).isStabilizationSupported }.getOrDefault(false)

        b.dualSurface.visibility = View.GONE
        b.previewView.visibility = View.VISIBLE
        b.previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        b.previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(rotation)

        if (mode == Mode.PHOTO) {
            refreshExtensionModes(selector)
            stabilizationSupported = previewStabSupported
            stabilizationActive = allowStab && stabilizationOn && previewStabSupported
            if (stabilizationActive) previewBuilder.setPreviewStabilizationEnabled(true)

            val p = previewBuilder.build()
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(photoFlash)
                .setResolutionSelector(resolution)
                .setTargetRotation(deviceRotation)
                .build()
            val em = extensionsManager
            val bindSelector = if (em != null && extensionMode != ExtensionMode.NONE &&
                em.isExtensionAvailable(selector, extensionMode)
            ) em.getExtensionEnabledCameraSelector(selector, extensionMode) else selector

            p.setSurfaceProvider(b.previewView.surfaceProvider)
            camera = provider.bindToLifecycle(this, bindSelector, p, ic)
            preview = p
            imageCapture = ic
        } else {
            val caps = Recorder.getVideoCapabilities(info)
            videoQualities = caps.getSupportedQualities(DynamicRange.SDR)
            if (videoQualities.isNotEmpty() && preferredQuality !in videoQualities) {
                preferredQuality = videoQualities.first()
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        preferredQuality,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val videoStabSupported = caps.isStabilizationSupported
            stabilizationSupported = videoStabSupported || previewStabSupported
            stabilizationActive = allowStab && stabilizationOn && stabilizationSupported

            val vcBuilder = VideoCapture.Builder(recorder)
                .setTargetRotation(deviceRotation)
                .setMirrorMode(
                    if (mirrorFront) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF
                )
            if (stabilizationActive) {
                // Preview stabilization also stabilizes the recording and is WYSIWYG.
                if (previewStabSupported) previewBuilder.setPreviewStabilizationEnabled(true)
                else vcBuilder.setVideoStabilizationEnabled(true)
            }
            val p = previewBuilder.build()
            val vc = vcBuilder.build()
            p.setSurfaceProvider(b.previewView.surfaceProvider)
            val cam = provider.bindToLifecycle(this, selector, p, vc)
            camera = cam
            preview = p
            videoCapture = vc
            if (torchOn && cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(true)
        }
    }

    private class DualUnsupportedException : Exception()

    private fun bindDual(provider: ProcessCameraProvider, allowStab: Boolean) {
        val renderer = dualRenderer ?: throw IllegalStateException("Renderer not ready")
        if (dualLayout == DualLayout.SOLO) {
            bindSolo(provider, allowStab, renderer)
            return
        }
        val pairs = provider.availableConcurrentCameraInfos
        if (pairs.isEmpty()) throw DualUnsupportedException()
        val pair = pairs.firstOrNull { list ->
            list.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
                list.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
        } ?: pairs.first()
        if (pair.size < 2) throw DualUnsupportedException()
        val back = pair.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK } ?: pair[0]
        val other = pair.first { it !== back }
        val infos = listOf(back, other)

        b.previewView.visibility = View.GONE
        b.dualSurface.visibility = View.VISIBLE
        layoutDualSurface()

        val rotation = displayRotation()
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()
        val stabSupported = infos.all {
            runCatching { Preview.getPreviewCapabilities(it).isStabilizationSupported }.getOrDefault(false)
        }
        stabilizationSupported = stabSupported
        stabilizationActive = allowStab && stabilizationOn && stabSupported

        val previews = infos.mapIndexed { slot, info ->
            renderer.setFront(slot, info.lensFacing == CameraSelector.LENS_FACING_FRONT)
            Preview.Builder()
                .setResolutionSelector(resolution)
                .setTargetRotation(rotation)
                .apply { if (stabilizationActive) setPreviewStabilizationEnabled(true) }
                .also { sharp.attach(it, slot) }
                .build()
                .also { it.setSurfaceProvider(renderer.surfaceProvider(slot)) }
        }
        renderer.displayRotationDeg = rotation * 90
        applyDualSettings()

        // Cutout: also analyse the front camera to find the person.
        val frontSlot = infos.indexOfFirst { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
        val analysis = if (dualLayout == DualLayout.CUTOUT && frontSlot >= 0) {
            androidx.camera.core.ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            androidx.camera.core.resolutionselector.ResolutionStrategy(
                                Size(640, 360),
                                androidx.camera.core.resolutionselector.ResolutionStrategy
                                    .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { proxy -> analyzeForCutout(proxy) } }
        } else null
        if (analysis == null) renderer.clearMask()

        val owner = DisposableLifecycleOwner(this)
        dualOwner = owner
        val concurrent = provider.bindToLifecycle(
            infos.mapIndexed { slot, info ->
                val group = UseCaseGroup.Builder().addUseCase(previews[slot])
                if (slot == frontSlot && analysis != null) group.addUseCase(analysis)
                SingleCameraConfig(info.cameraSelector, group.build(), owner)
            }
        )
        cutoutAnalysis = analysis
        dualCameras = concurrent.cameras
        dualPreviews = previews
        // Start on the widest field of view (e.g. 0.5x ultra-wide) — best for travel/vlogs.
        val bound = dualCameras
        handler.postDelayed({
            if (dualCameras !== bound) return@postDelayed
            for (cam in bound) {
                cam.cameraInfo.zoomState.value?.let { z ->
                    if (z.minZoomRatio < 1f) cam.cameraControl.setZoomRatio(z.minZoomRatio)
                }
            }
        }, 500)
        dualZoomTarget = dualZoomTarget.coerceIn(0, (dualCameras.size - 1).coerceAtLeast(0))
    }

    /** Vlog pipeline with just one camera (rear by default; Swap picks the front). */
    private fun bindSolo(provider: ProcessCameraProvider, allowStab: Boolean, renderer: DualRenderer) {
        val facing = if (dualSwapped) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val info = provider.availableCameraInfos.firstOrNull { it.lensFacing == facing }
            ?: provider.availableCameraInfos.first()
        val front = info.lensFacing == CameraSelector.LENS_FACING_FRONT
        val slot = if (front) 1 else 0
        renderer.setFront(0, false)
        renderer.setFront(1, true)

        b.previewView.visibility = View.GONE
        b.dualSurface.visibility = View.VISIBLE
        layoutDualSurface()

        val rotation = displayRotation()
        stabilizationSupported =
            runCatching { Preview.getPreviewCapabilities(info).isStabilizationSupported }.getOrDefault(false)
        stabilizationActive = allowStab && stabilizationOn && stabilizationSupported
        val p = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .setTargetRotation(rotation)
            .apply { if (stabilizationActive) setPreviewStabilizationEnabled(true) }
            .also { sharp.attach(it, slot) }
            .build()
        p.setSurfaceProvider(renderer.surfaceProvider(slot))
        soloSlot = slot
        renderer.displayRotationDeg = rotation * 90
        renderer.clearMask()
        applyDualSettings()
        renderer.swapped = slot == 1

        val owner = DisposableLifecycleOwner(this)
        dualOwner = owner
        val cam = provider.bindToLifecycle(owner, info.cameraSelector, p)
        dualCameras = listOf(cam)
        dualPreviews = listOf(p)
        dualZoomTarget = 0
        handler.postDelayed({
            if (dualCameras.firstOrNull() !== cam) return@postDelayed
            cam.cameraInfo.zoomState.value?.let { z ->
                if (z.minZoomRatio < 1f) cam.cameraControl.setZoomRatio(z.minZoomRatio)
            }
        }, 500)
    }

    private fun refreshExtensionModes(selector: CameraSelector) {
        val em = extensionsManager
        val all = listOf(
            ExtensionMode.NONE, ExtensionMode.AUTO, ExtensionMode.HDR, ExtensionMode.NIGHT,
            ExtensionMode.BOKEH, ExtensionMode.FACE_RETOUCH
        )
        extensionModes = if (em == null) listOf(ExtensionMode.NONE) else all.filter {
            it == ExtensionMode.NONE || runCatching { em.isExtensionAvailable(selector, it) }.getOrDefault(false)
        }
        if (extensionMode !in extensionModes) extensionMode = ExtensionMode.NONE
    }

    private fun activeCamera(): Camera? =
        if (mode == Mode.DUAL) dualCameras.getOrNull(dualZoomTarget) else camera

    // ---------------------------------------------------------------- zoom & exposure

    private fun observeCamera(cam: Camera?) {
        observedCamera?.cameraInfo?.zoomState?.removeObservers(this)
        observedCamera = cam
        if (cam == null) return
        buildZoomChips(cam.cameraInfo)
        cam.cameraInfo.zoomState.observe(this) { updateZoomUi(it) }
        setupExposure(cam.cameraInfo)
    }

    private fun buildZoomChips(info: CameraInfo) {
        b.zoomChips.removeAllViews()
        val z = info.zoomState.value ?: return
        val stops = mutableListOf<Float>()
        if (z.minZoomRatio < 0.95f) stops += z.minZoomRatio
        stops += 1f
        for (s in listOf(2f, 3f, 5f, 10f)) if (s <= z.maxZoomRatio + 0.01f) stops += s
        val density = resources.displayMetrics.density
        for (s in stops) {
            val tv = TextView(this).apply {
                text = formatZoom(s)
                tag = s
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.circle_button)
                val size = (40 * density).roundToInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                    marginStart = (4 * density).roundToInt()
                    marginEnd = (4 * density).roundToInt()
                }
                setOnClickListener {
                    activeCamera()?.cameraControl?.setZoomRatio(s)
                    showZoomSlider()
                }
            }
            b.zoomChips.addView(tv)
        }
    }

    private fun updateZoomUi(z: ZoomState) {
        b.zoomLabel.text = formatZoom(z.zoomRatio)
        if (!zoomSliderDragging) b.zoomSlider.value = z.linearZoom.coerceIn(0f, 1f)
        var closest: View? = null
        var best = Float.MAX_VALUE
        for (i in 0 until b.zoomChips.childCount) {
            val v = b.zoomChips.getChildAt(i)
            val d = abs((v.tag as Float) - z.zoomRatio)
            if (d < best) { best = d; closest = v }
        }
        for (i in 0 until b.zoomChips.childCount) {
            val v = b.zoomChips.getChildAt(i) as TextView
            val active = v === closest && best < 0.15f
            v.setTextColor(if (active) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    private fun formatZoom(r: Float): String =
        if (r < 10f) String.format(Locale.US, "%.1fx", r).replace(".0x", "x")
        else "${r.roundToInt()}x"

    private fun setupExposure(info: CameraInfo) {
        val state = info.exposureState
        val range = state.exposureCompensationRange
        val supported = state.isExposureCompensationSupported && range.lower < range.upper
        b.btnExposure.tag = supported
        if (!supported) {
            b.exposurePanel.visibility = View.GONE
            return
        }
        val idx = state.exposureCompensationIndex.coerceIn(range.lower, range.upper)
        b.exposureSlider.valueFrom = range.lower.toFloat()
        b.exposureSlider.valueTo = range.upper.toFloat()
        b.exposureSlider.value = idx.toFloat()
        updateExposureLabel(idx, state.exposureCompensationStep.toFloat())
    }

    private fun updateExposureLabel(index: Int, step: Float) {
        val ev = index * step
        b.exposureLabel.text = String.format(Locale.US, "EV %+.1f", ev).replace("+0.0", "0")
    }

    // ---------------------------------------------------------------- gestures

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = activeCamera() ?: return false
                    val z = cam.cameraInfo.zoomState.value ?: return false
                    val ratio = (z.zoomRatio * detector.scaleFactor)
                        .coerceIn(z.minZoomRatio, z.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(ratio)
                    showZoomSlider()
                    return true
                }
            })
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                switchCamera()
                return true
            }
        })
        b.previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(event)
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        if (mode == Mode.DUAL) return  // composed stream: tap coords don't map to a single camera
        val cam = camera ?: return
        val point = b.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
        b.focusRing.show(x, y)
    }

    // ---------------------------------------------------------------- controls

    private fun setupControls() {
        b.shutterButton.setOnClickListener { onShutter() }
        b.btnSwitch.setOnClickListener { switchCamera() }
        b.btnSnapshot.setOnClickListener { takeDualSnapshot() }
        b.btnPause.setOnClickListener { togglePause() }
        b.thumbnail.setOnClickListener { openLastMedia() }
        b.thumbnail.setOnLongClickListener { startExportFlow(); true }
        b.btnExport.setOnClickListener { startExportFlow() }
        b.updateBanner.setOnClickListener { onUpdateBannerTapped() }

        b.tabPhoto.setOnClickListener { setMode(Mode.PHOTO) }
        b.tabVideo.setOnClickListener { setMode(Mode.VIDEO) }
        b.tabDual.setOnClickListener { setMode(Mode.DUAL) }

        b.btnFlash.setOnClickListener {
            if (mode == Mode.PHOTO) {
                photoFlash = when (photoFlash) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
                imageCapture?.flashMode = photoFlash
            } else {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
            }
            refreshUi()
        }
        b.btnTimer.setOnClickListener {
            timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
            refreshUi()
        }
        b.btnGrid.setOnClickListener { gridOn = !gridOn; refreshUi() }
        b.btnMic.setOnClickListener {
            if (!micOn && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            micOn = !micOn
            refreshUi()
        }
        b.btnStab.setOnClickListener {
            if (!stabilizationSupported) {
                Toast.makeText(this, "Stabilization not supported for this camera/mode", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            stabilizationOn = !stabilizationOn
            bindCamera()
        }
        b.btnQuality.setOnClickListener {
            if (videoQualities.isEmpty()) return@setOnClickListener
            val i = videoQualities.indexOf(preferredQuality)
            preferredQuality = videoQualities[(i + 1) % videoQualities.size]
            bindCamera()
        }
        b.btnEffect.setOnClickListener {
            val i = extensionModes.indexOf(extensionMode)
            extensionMode = extensionModes[(i + 1) % extensionModes.size]
            bindCamera()
        }
        b.btnMirror.setOnClickListener {
            mirrorFront = !mirrorFront
            applyDualSettings()
            if (mode == Mode.VIDEO) bindCamera() else refreshUi()
        }
        b.btnRide.setOnClickListener {
            rideAuto = !rideAuto
            Toast.makeText(
                this,
                if (rideAuto) "Ride mode on: screen goes dark ${RIDE_DELAY_MS / 1000}s after recording starts. Tap to wake."
                else "Ride mode off",
                Toast.LENGTH_SHORT
            ).show()
            scheduleRideMode()
            refreshUi()
        }
        b.btnWind.setOnClickListener {
            windLevel = (windLevel + 1) % 3
            if (windLevel > 0) {
                Toast.makeText(
                    this, "Wind filter cleans the audio right after each recording stops",
                    Toast.LENGTH_SHORT
                ).show()
            }
            refreshUi()
        }
        b.btnOverlay.setOnClickListener {
            overlayOn = !overlayOn
            if (overlayOn && !hasLocationPermission()) {
                locationLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
            updateHud()
            refreshUi()
        }
        b.btnOverlayPos.setOnClickListener {
            overlayCorner = PipCorner.entries[(overlayCorner.ordinal + 1) % PipCorner.entries.size]
            dualRenderer?.overlayCorner = overlayCorner
            refreshUi()
        }
        b.btnMicSource.setOnClickListener {
            val mics = Mics.list(this)
            if (mics.size == 1) {
                micChoice = "phone"
                Toast.makeText(
                    this, "No external mic found. Connect a USB-C, wired or Bluetooth mic.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val i = mics.indexOfFirst { it.key == micChoice }
                val next = mics[(i + 1) % mics.size]
                micChoice = next.key
                Toast.makeText(this, "Recording audio from: ${next.label}", Toast.LENGTH_SHORT).show()
            }
            refreshUi()
        }
        b.btnSettings.setOnClickListener { showSettingsSheet() }
        b.btnSpeedBlur.setOnClickListener {
            speedBlurLevel = (speedBlurLevel + 1) % 3
            if (speedBlurLevel > 0 && !hasLocationPermission()) {
                locationLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
            updateHud()
            refreshUi()
        }
        b.btnFfBlur.setOnClickListener {
            ffMotionBlur = !ffMotionBlur
            refreshUi()
        }
        b.btnHorizon.setOnClickListener {
            horizonMode = (horizonMode + 1) % 3
            updateHorizonSensor()
            refreshUi()
        }
        b.btnSharp.setOnClickListener {
            if (sharpOn) {
                sharpOn = false
                sharp.disable(sharpTargets())
            } else {
                sharpOn = true
                applySharp()
                Toast.makeText(
                    this, "Sharp motion on: fast shutter against vibration blur (night video gets grainier)",
                    Toast.LENGTH_LONG
                ).show()
            }
            refreshUi()
        }
        b.btnFast.setOnClickListener { toggleFastForward() }
        b.btnFastSpeed.setOnClickListener {
            fastSpeed = when (fastSpeed) { 4 -> 8; 8 -> 16; 16 -> 32; else -> 4 }
            refreshUi()
        }
        b.btnExposure.setOnClickListener {
            b.exposurePanel.visibility =
                if (b.exposurePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        b.exposureSlider.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (!fromUser) return@OnChangeListener
            val cam = activeCamera() ?: return@OnChangeListener
            cam.cameraControl.setExposureCompensationIndex(value.roundToInt())
            updateExposureLabel(
                value.roundToInt(),
                cam.cameraInfo.exposureState.exposureCompensationStep.toFloat()
            )
        })
        b.zoomSlider.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (fromUser) activeCamera()?.cameraControl?.setLinearZoom(value)
        })
        b.zoomSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                zoomSliderDragging = true
                handler.removeCallbacks(hideZoomRow)
            }
            override fun onStopTrackingTouch(slider: Slider) {
                zoomSliderDragging = false
                showZoomSlider()
            }
        })

        // Dual controls
        b.btnLayout.setOnClickListener { showLayoutPicker() }
        b.btnCorner.setOnClickListener {
            pipCorner = PipCorner.entries[(pipCorner.ordinal + 1) % PipCorner.entries.size]
            applyDualSettings()
            refreshUi()
        }
        b.btnPipSize.setOnClickListener {
            pipSizeIndex = (pipSizeIndex + 1) % DualComposer.PIP_SIZES.size
            applyDualSettings()
            refreshUi()
        }
        b.btnSwap.setOnClickListener { switchCamera() }
        b.btnZoomTarget.setOnClickListener {
            if (dualCameras.size < 2) return@setOnClickListener
            dualZoomTarget = (dualZoomTarget + 1) % dualCameras.size
            observeCamera(activeCamera())
            refreshUi()
        }
        b.btnDualFrame.setOnClickListener {
            dualFrame = DualFrame.entries[(dualFrame.ordinal + 1) % DualFrame.entries.size]
            layoutDualSurface()
            refreshUi()
        }
        b.btnDualFit.setOnClickListener {
            dualFit = !dualFit
            applyDualSettings()
            refreshUi()
        }
        b.btnDualQuality.setOnClickListener {
            dualFullHd = !dualFullHd
            refreshUi()
        }
    }

    private fun setMode(newMode: Mode) {
        if (isRecording || mode == newMode) return
        cancelCountdown()
        mode = newMode
        torchOn = false
        b.exposurePanel.visibility = View.GONE
        if (newMode == Mode.DUAL && !hasPermission(Manifest.permission.RECORD_AUDIO) && micOn) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        bindCamera()
    }

    private fun switchCamera() {
        if (isRecording && mode != Mode.DUAL) return
        if (mode == Mode.DUAL && dualLayout == DualLayout.SOLO) {
            if (isRecording) return
            dualSwapped = !dualSwapped
            b.btnSwitch.animate().rotationBy(180f).setDuration(250).start()
            bindCamera()
            return
        }
        if (mode == Mode.DUAL) {
            dualSwapped = !dualSwapped
            dualZoomTarget = if (dualSwapped) 1 else 0
            applyDualSettings()
            observeCamera(activeCamera())
            b.btnSwitch.animate().rotationBy(180f).setDuration(250).start()
            refreshUi()
            return
        } else {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            torchOn = false
        }
        b.btnSwitch.animate().rotationBy(180f).setDuration(250).start()
        bindCamera()
    }

    private fun refreshUi() {
        val single = mode != Mode.DUAL

        fun tab(v: TextView, selected: Boolean) {
            v.setTextColor(if (selected) 0xFFFFD54F.toInt() else 0xBBFFFFFF.toInt())
            v.alpha = if (isRecording && !selected) 0.3f else 1f
        }
        tab(b.tabPhoto, mode == Mode.PHOTO)
        tab(b.tabVideo, mode == Mode.VIDEO)
        tab(b.tabDual, mode == Mode.DUAL)

        val cam = camera
        val hasFlash = cam?.cameraInfo?.hasFlashUnit() == true
        b.btnFlash.visibility = if (single && hasFlash) View.VISIBLE else View.GONE
        b.btnFlash.text = if (mode == Mode.PHOTO) when (photoFlash) {
            ImageCapture.FLASH_MODE_AUTO -> "⚡ Auto"
            ImageCapture.FLASH_MODE_ON -> "⚡ On"
            else -> "⚡ Off"
        } else if (torchOn) "🔦 On" else "🔦 Off"
        active(b.btnFlash, if (mode == Mode.PHOTO) photoFlash != ImageCapture.FLASH_MODE_OFF else torchOn)

        b.btnTimer.text = if (timerSeconds == 0) "⏱ Off" else "⏱ ${timerSeconds}s"
        active(b.btnTimer, timerSeconds > 0)

        b.btnWind.visibility = if (mode == Mode.PHOTO) View.GONE else View.VISIBLE
        b.btnWind.text = "🌬 Wind filter " + arrayOf("Off", "Light", "Strong")[windLevel]
        active(b.btnWind, windLevel > 0)
        b.btnOverlay.text = if (overlayOn) "📍 Speed overlay On" else "📍 Speed overlay Off"
        active(b.btnOverlay, overlayOn)
        b.btnOverlayPos.visibility = if (overlayOn) View.VISIBLE else View.GONE
        b.btnOverlayPos.text = "Overlay " + when (overlayCorner) {
            PipCorner.TOP_LEFT -> "↖"
            PipCorner.TOP_RIGHT -> "↗"
            PipCorner.BOTTOM_RIGHT -> "↘"
            PipCorner.BOTTOM_LEFT -> "↙"
        }
        b.btnMicSource.text = "🎙 " + Mics.find(this, micChoice).label
        active(b.btnMicSource, micChoice != "phone")
        b.btnMicSource.isEnabled = !isRecording
        b.btnOverlay.isEnabled = !isRecording
        b.btnRide.text = if (rideAuto) "🏍 Ride mode On" else "🏍 Ride mode Off"
        active(b.btnRide, rideAuto)
        b.btnRide.visibility = if (mode == Mode.PHOTO) View.GONE else View.VISIBLE
        b.btnGrid.text = "# Grid"
        active(b.btnGrid, gridOn)
        b.gridOverlay.visibility = if (gridOn) View.VISIBLE else View.GONE

        b.btnStab.text = when {
            !stabilizationSupported -> "Stabilize N/A"
            stabilizationActive -> "✋ Stabilize On"
            else -> "✋ Stabilize Off"
        }
        active(b.btnStab, stabilizationActive)
        b.btnStab.alpha = if (stabilizationSupported) 1f else 0.5f

        b.btnQuality.visibility = if (mode == Mode.VIDEO && videoQualities.isNotEmpty()) View.VISIBLE else View.GONE
        b.btnQuality.text = qualityLabel(preferredQuality)

        b.btnEffect.visibility = if (mode == Mode.PHOTO && extensionModes.size > 1) View.VISIBLE else View.GONE
        b.btnEffect.text = "✨ " + extensionLabel(extensionMode)
        active(b.btnEffect, extensionMode != ExtensionMode.NONE)

        b.btnMic.visibility = if (mode == Mode.PHOTO) View.GONE else View.VISIBLE
        b.btnMic.text = if (micOn) "🎤 On" else "🎤 Off"
        active(b.btnMic, micOn)

        b.btnMirror.visibility =
            if (!single || lensFacing == CameraSelector.LENS_FACING_FRONT) View.VISIBLE else View.GONE
        b.btnMirror.text = if (mirrorFront) "⇋ Mirror On" else "⇋ Mirror Off"
        active(b.btnMirror, mirrorFront)

        b.btnExposure.visibility = if (b.btnExposure.tag == true) View.VISIBLE else View.GONE

        // Dual panel
        b.dualPanel.visibility = if (mode == Mode.DUAL) View.VISIBLE else View.GONE
        b.btnLayout.text = "▦ " + if (dualLayout == DualLayout.SOLO) {
            if (dualSwapped) "Front camera only" else "Rear camera only"
        } else dualLayout.label
        b.btnZoomTarget.visibility = if (dualLayout == DualLayout.SOLO) View.GONE else View.VISIBLE
        val pip = dualLayout == DualLayout.PIP
        val cut = dualLayout == DualLayout.CUTOUT
        b.btnCorner.visibility = if (pip || cut) View.VISIBLE else View.GONE
        b.btnPipSize.visibility = if (pip || cut) View.VISIBLE else View.GONE
        b.btnCorner.text = if (cut) "Move person" else "Move inset"
        b.btnPipSize.text = (if (cut) "Person: " else "Inset: ") + DualComposer.PIP_SIZE_LABELS[pipSizeIndex]
        b.btnSwap.visibility = if (cut) View.GONE else View.VISIBLE
        b.btnDualFit.visibility = if (dualLayout == DualLayout.SPLIT_CARD) View.GONE else View.VISIBLE
        val targetCam = dualCameras.getOrNull(dualZoomTarget)
        b.btnZoomTarget.text = "🔍 Zoom: " + when (targetCam?.cameraInfo?.lensFacing) {
            CameraSelector.LENS_FACING_FRONT -> "Front"
            CameraSelector.LENS_FACING_BACK -> "Rear"
            else -> "Cam ${dualZoomTarget + 1}"
        }
        b.btnDualQuality.text = if (dualFullHd) "1080p" else "720p"
        b.btnDualFrame.text = "▭ Frame " + dualFrame.label
        b.btnDualFit.text = if (dualFit) "⤢ Fit (no crop)" else "⤢ Fill (crop)"
        active(b.btnDualFit, dualFit)
        b.btnDualFrame.isEnabled = !isRecording
        b.btnDualFrame.alpha = if (isRecording) 0.4f else 1f
        // Layout, inset and swap can change live, even while recording.
        b.btnDualQuality.isEnabled = !isRecording
        b.btnDualQuality.alpha = if (isRecording) 0.4f else 1f
        for (v in listOf(b.btnStab, b.btnQuality, b.btnEffect, b.btnMic, b.btnMirror)) {
            v.isEnabled = !isRecording
        }

        b.shutterButton.setImageResource(
            when {
                isRecording -> R.drawable.shutter_recording
                mode == Mode.PHOTO -> R.drawable.shutter_photo
                else -> R.drawable.shutter_video
            }
        )
        b.btnSnapshot.visibility = if (mode == Mode.DUAL) View.VISIBLE else View.GONE
        // While recording, the left side holds Pause + Fast-forward instead of gallery/export.
        val recControls = isRecording && mode != Mode.PHOTO
        b.thumbnail.visibility = if (recControls) View.GONE else View.VISIBLE
        b.btnExport.visibility = if (recControls) View.GONE else View.VISIBLE
        b.btnFast.visibility = if (recControls) View.VISIBLE else View.GONE
        b.btnFast.setBackgroundResource(if (fastActive) R.drawable.circle_button_active else R.drawable.circle_button)
        b.btnFast.setTextColor(if (fastActive) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        b.btnFastSpeed.text = "⏩ Fast-forward speed ${fastSpeed}x"
        b.btnSharp.visibility = if (mode == Mode.PHOTO) View.GONE else View.VISIBLE
        b.btnHorizon.visibility = if (mode == Mode.DUAL) View.VISIBLE else View.GONE
        b.btnSpeedBlur.visibility = if (mode == Mode.DUAL) View.VISIBLE else View.GONE
        b.btnFfBlur.visibility = if (mode == Mode.PHOTO) View.GONE else View.VISIBLE
        b.btnHorizon.text = "Horizon " + arrayOf("Off", "Level mount", "Lock horizon")[horizonMode]
        b.btnSharp.text = if (sharpOn) "Sharp motion On" else "Sharp motion Off"
        b.btnSettings.visibility = if (isRecording) View.GONE else View.VISIBLE
        b.btnPause.visibility = if (isRecording) View.VISIBLE else View.GONE
        b.btnPause.text = if (recordingPaused) "▶" else "❚❚"
        val switchLocked = isRecording && mode != Mode.DUAL
        b.btnSwitch.isEnabled = !switchLocked
        b.btnSwitch.alpha = if (switchLocked) 0.4f else 1f
        b.recordTime.visibility = if (isRecording) View.VISIBLE else View.GONE
    }

    private fun active(v: TextView, on: Boolean) {
        v.setBackgroundResource(if (on) R.drawable.pill_bg_active else R.drawable.pill_bg)
        v.setTextColor(if (on) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun extensionLabel(m: Int) = when (m) {
        ExtensionMode.AUTO -> "Auto"
        ExtensionMode.HDR -> "HDR"
        ExtensionMode.NIGHT -> "Night"
        ExtensionMode.BOKEH -> "Portrait"
        ExtensionMode.FACE_RETOUCH -> "Retouch"
        else -> "Standard"
    }

    private fun qualityLabel(q: Quality) = when (q) {
        Quality.UHD -> "4K"
        Quality.FHD -> "1080p"
        Quality.HD -> "720p"
        Quality.SD -> "480p"
        else -> "Auto"
    }

    private fun qualityName(q: Quality) = when (q) {
        Quality.UHD -> "UHD"; Quality.FHD -> "FHD"; Quality.HD -> "HD"; Quality.SD -> "SD"; else -> "FHD"
    }

    private fun qualityFromName(n: String): Quality = when (n) {
        "UHD" -> Quality.UHD; "HD" -> Quality.HD; "SD" -> Quality.SD; else -> Quality.FHD
    }

    // ---------------------------------------------------------------- capture

    private fun onShutter() {
        if (countdownRunnable != null) { cancelCountdown(); return }
        when (mode) {
            Mode.PHOTO -> withTimer { takePhoto() }
            Mode.VIDEO -> if (!isRecording) withTimer { startRecording() } else stopRecording()
            Mode.DUAL -> if (!isRecording) withTimer { startDualRecording() } else stopDualRecording()
        }
    }

    private fun withTimer(action: () -> Unit) {
        if (timerSeconds == 0) { action(); return }
        var remaining = timerSeconds
        b.countdownText.visibility = View.VISIBLE
        val r = object : Runnable {
            override fun run() {
                if (remaining == 0) {
                    cancelCountdown()
                    action()
                    return
                }
                b.countdownText.text = remaining.toString()
                remaining--
                handler.postDelayed(this, 1000)
            }
        }
        countdownRunnable = r
        r.run()
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        b.countdownText.visibility = View.GONE
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT && mirrorFront
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaSaver.imageValues(MediaSaver.fileName("IMG"))
        ).setMetadata(metadata).build()
        flashScreen()
        ic.takePicture(options, uiExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                output.savedUri?.let { setLastMedia(it, false) }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Photo failed", exception)
                Toast.makeText(this@MainActivity, "Photo failed: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    /** Dual photo: render the combined frame offscreen at full output size. */
    private fun takeDualSnapshot() {
        val renderer = dualRenderer ?: return
        val (w, h) = dualCanvasSize()
        flashScreen()
        renderer.snapshot(w, h) { bmp ->
            if (bmp == null) {
                runOnUiThread { Toast.makeText(this, "Snapshot failed", Toast.LENGTH_SHORT).show() }
                return@snapshot
            }
            ioExecutor.execute {
                val uri = MediaSaver.saveBitmap(this, bmp, MediaSaver.fileName("DUAL"))
                runOnUiThread {
                    if (uri != null) setLastMedia(uri, false)
                    else Toast.makeText(this, "Could not save photo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------------------------------------------------------------- dual recording

    private fun startDualRecording() {
        val renderer = dualRenderer ?: return
        if (dualCameras.isEmpty()) return
        if (!canStartRecording()) return
        // When the phone is already hot, record at 720p to reduce load.
        val hot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            thermalStatus() >= android.os.PowerManager.THERMAL_STATUS_SEVERE
        val fullHd = dualFullHd && !hot
        if (dualFullHd && hot) Toast.makeText(this, "Phone is hot – recording at 720p", Toast.LENGTH_LONG).show()
        val (w, h) = dualCanvasSize(fullHd)
        val (uri, pfd) = newVideoEntry() ?: run {
            Toast.makeText(this, "Could not create video file", Toast.LENGTH_LONG).show()
            return
        }
        val audio = micOn && hasPermission(Manifest.permission.RECORD_AUDIO)
        val mic = if (audio) Mics.find(this, micChoice) else null
        if (mic?.bluetooth == true) {
            Mics.startBluetooth(this)
            bluetoothMicActive = true
        }
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            if (audio) {
                mr.setAudioSource(
                    when {
                        mic?.bluetooth == true -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                        mic?.device != null -> MediaRecorder.AudioSource.MIC
                        else -> MediaRecorder.AudioSource.CAMCORDER
                    }
                )
            }
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(pfd.fileDescriptor)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setVideoSize(w, h)
            mr.setVideoFrameRate(30)
            val videoBitrate = if (fullHd) 16_000_000 else 8_000_000
            mr.setVideoEncodingBitRate(videoBitrate)
            // Long rides: continue seamlessly into a new file about every 10 minutes
            // (also avoids the 4 GB MP4 limit, and limits what a crash could lose).
            mr.setMaxFileSize((videoBitrate + 128_000L) / 8L * SEGMENT_SECONDS)
            mr.setOnInfoListener { _, what, _ ->
                runOnUiThread {
                    when (what) {
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> prepareNextSegment(mr)
                        MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> onNextSegmentStarted()
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ->
                            if (mediaRecorder === mr) stopDualRecording()
                    }
                }
            }
            if (audio) {
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioSamplingRate(if (mic?.bluetooth == true) 16_000 else 48_000)
                if (mic?.device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    mr.setPreferredDevice(mic.device)
                }
                mr.setAudioEncodingBitRate(128_000)
                mr.setAudioChannels(1)
            }
            mr.prepare()
            val surface = mr.surface
            mr.start()
            renderer.startEncoder(surface, w, h, displayRotation() * 90)
        } catch (e: Exception) {
            Log.e(TAG, "Dual recording failed to start", e)
            runCatching { renderer.stopEncoder() }
            mr.release()
            runCatching { pfd.close() }
            contentResolver.delete(uri, null, null)
            if (bluetoothMicActive) { Mics.stopBluetooth(this); bluetoothMicActive = false }
            Toast.makeText(this, "Could not start recording: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        mediaRecorder = mr
        dualPfd = pfd
        dualUri = uri
        recordingPaused = false
        dualStartMs = System.currentTimeMillis()
        dualPausedMs = 0L
        b.recordTime.text = "● 00:00"
        handler.post(dualTicker)
        hud?.setCounting(true)
        dualHasAudio = audio
        resetFastForward()
        refreshUi()
        onRecordingStateChanged()
    }

    /** Creates a pending MediaStore video entry and opens it for writing. */
    private fun newVideoEntry(): Pair<Uri, android.os.ParcelFileDescriptor>? {
        val values = MediaSaver.videoValues(MediaSaver.fileName("VLOG"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val pfd = runCatching { contentResolver.openFileDescriptor(uri, "w") }.getOrNull()
        if (pfd == null) {
            contentResolver.delete(uri, null, null)
            return null
        }
        return uri to pfd
    }

    /** Closes a finished entry: publishes it if [ok], deletes it otherwise. Call off the main thread. */
    private fun finalizeEntry(uri: Uri?, pfd: android.os.ParcelFileDescriptor?, ok: Boolean) {
        runCatching { pfd?.close() }
        if (uri == null) return
        if (ok) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }
        } else {
            contentResolver.delete(uri, null, null)
        }
    }

    private fun prepareNextSegment(mr: MediaRecorder) {
        if (mediaRecorder !== mr || dualNextUri != null) return
        val (uri, pfd) = newVideoEntry() ?: return
        try {
            mr.setNextOutputFile(pfd.fileDescriptor)
            dualNextUri = uri
            dualNextPfd = pfd
        } catch (e: Exception) {
            Log.e(TAG, "setNextOutputFile failed", e)
            ioExecutor.execute { finalizeEntry(uri, pfd, false) }
        }
    }

    private fun onNextSegmentStarted() {
        // Fast-forward sections belong to the file they were marked in.
        val wasFast = fastActive
        val oldRanges = takeFastRanges()
        segmentBaseMs += segmentMs()
        if (wasFast) {
            fastActive = true
            fastStartMs = 0L
        }
        val oldUri = dualUri
        val oldPfd = dualPfd
        dualUri = dualNextUri
        dualPfd = dualNextPfd
        dualNextUri = null
        dualNextPfd = null
        val audio = dualHasAudio
        ioExecutor.execute {
            finalizeEntry(oldUri, oldPfd, true)
            runOnUiThread {
                if (oldUri != null) {
                    setLastMedia(oldUri, true)
                    postProcess(oldUri, audio, oldRanges)
                }
            }
        }
    }

    private fun stopDualRecording() {
        val mr = mediaRecorder ?: return
        val pfd = dualPfd
        val uri = dualUri
        val nextUri = dualNextUri
        val nextPfd = dualNextPfd
        dualNextUri = null
        dualNextPfd = null
        val audio = dualHasAudio
        val ranges = takeFastRanges()
        dualStopping = true
        mediaRecorder = null
        exitRideMode()
        dualPfd = null
        dualUri = null
        handler.removeCallbacks(dualTicker)
        refreshUi()
        // Stop feeding frames first, then finalize the file off the main thread.
        dualRenderer?.stopEncoder()
        ioExecutor.execute {
            val ok = try {
                mr.stop()
                true
            } catch (e: RuntimeException) {
                Log.e(TAG, "MediaRecorder stop failed", e)
                false
            }
            mr.release()
            finalizeEntry(uri, pfd, ok)
            finalizeEntry(nextUri, nextPfd, false)  // prepared but never started
            runOnUiThread {
                dualStopping = false
                recordingPaused = false
                if (bluetoothMicActive) { Mics.stopBluetooth(this); bluetoothMicActive = false }
                hud?.setCounting(false)
                if (ok && uri != null) postProcess(uri, audio, ranges)
                b.recordTime.text = "● 00:00"
                if (ok && uri != null) setLastMedia(uri, true)
                else Toast.makeText(this, "Recording too short or failed", Toast.LENGTH_SHORT).show()
                updateTargetRotation()
                refreshUi()
                onRecordingStateChanged()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        if (!canStartRecording()) return
        updateTargetRotation()
        val options = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(MediaSaver.videoValues(MediaSaver.fileName(if (mode == Mode.DUAL) "VLOG" else "VID")))
            .build()
        var pending = vc.output.prepareRecording(this, options)
        if (micOn && hasPermission(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        recordingPaused = false
        resetFastForward()
        cameraxPendingRanges = null
        recording = pending.start(uiExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> { refreshUi(); onRecordingStateChanged() }
                is VideoRecordEvent.Status -> {
                    cameraxRecordedMs = event.recordingStats.recordedDurationNanos / 1_000_000
                    val secs = TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos)
                    b.recordTime.text = recordLabel(secs)
                }
                is VideoRecordEvent.Pause -> { recordingPaused = true; refreshUi() }
                is VideoRecordEvent.Resume -> { recordingPaused = false; refreshUi() }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    recordingPaused = false
                    val uri = event.outputResults.outputUri
                    if (!event.hasError() || uri != Uri.EMPTY) {
                        if (uri != Uri.EMPTY) setLastMedia(uri, true)
                        val ranges = cameraxPendingRanges ?: takeFastRanges()
                        cameraxPendingRanges = null
                        if (uri != Uri.EMPTY) postProcess(uri, micOn, ranges)
                        if (event.hasError()) Log.w(TAG, "Recording finished with error ${event.error}")
                    } else {
                        Toast.makeText(this, "Recording failed (code ${event.error})", Toast.LENGTH_LONG).show()
                    }
                    b.recordTime.text = "● 00:00"
                    refreshUi()
                    onRecordingStateChanged()
                }
            }
        }
        refreshUi()
    }

    private fun stopRecording() {
        if (recording != null && cameraxPendingRanges == null) cameraxPendingRanges = takeFastRanges()
        recording?.stop()
    }

    private fun togglePause() {
        mediaRecorder?.let { mr ->
            runCatching {
                if (recordingPaused) {
                    mr.resume()
                    dualPausedMs += System.currentTimeMillis() - dualPauseStartMs
                } else {
                    mr.pause()
                    dualPauseStartMs = System.currentTimeMillis()
                }
                recordingPaused = !recordingPaused
            }
            refreshUi()
            return
        }
        val r = recording ?: return
        if (recordingPaused) r.resume() else r.pause()
    }

    private fun flashScreen() {
        b.flashOverlay.animate().cancel()
        b.flashOverlay.alpha = 0.7f
        b.flashOverlay.animate().alpha(0f).setDuration(250).start()
    }

    // ---------------------------------------------------------------- gallery

    private fun setLastMedia(uri: Uri, isVideo: Boolean) {
        lastMediaUri = uri
        lastMediaIsVideo = isVideo
        ioExecutor.execute {
            val thumb = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(uri, Size(256, 256), null)
                } else null
            }.getOrNull()
            runOnUiThread {
                if (thumb != null) b.thumbnail.setImageBitmap(thumb)
                else b.thumbnail.setImageResource(R.drawable.shutter_photo)
            }
        }
    }

    private fun openLastMedia() {
        val uri = lastMediaUri
        val intent = if (uri != null) {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (lastMediaIsVideo) "video/*" else "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- updates

    private fun checkForUpdate() {
        if (updateBusy) return
        lastUpdateCheck = System.currentTimeMillis()
        ioExecutor.execute {
            val update = Updater.check()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                availableUpdate = update
                if (update != null && !updateBusy) {
                    b.updateBanner.text = "⬆ New version ${update.versionName} available · Tap to update"
                    b.updateBanner.visibility = View.VISIBLE
                } else if (update == null) {
                    b.updateBanner.visibility = View.GONE
                }
            }
        }
    }

    private fun onUpdateBannerTapped() {
        if (updateBusy) return
        if (isRecording) {
            Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
            return
        }
        downloadedApk?.takeIf { it.exists() }?.let { launchInstaller(it); return }
        updateBusy = true
        b.updateBanner.text = "Downloading update…"
        ioExecutor.execute {
            val result = runCatching {
                Updater.download(this) { pct ->
                    runOnUiThread {
                        b.updateBanner.text =
                            if (pct >= 0) "Downloading update… $pct%" else "Downloading update…"
                    }
                }
            }
            runOnUiThread {
                updateBusy = false
                result.onSuccess { apk ->
                    downloadedApk = apk
                    b.updateBanner.text = "⬆ Update downloaded · Tap to install"
                    launchInstaller(apk)
                }.onFailure { e ->
                    b.updateBanner.text = "⬆ Update failed · Tap to retry"
                    Toast.makeText(this, "Update download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchInstaller(apk: java.io.File) {
        if (!Updater.canInstall(this)) {
            installAfterPermission = true
            Toast.makeText(this, "Allow VlogCam to install updates, then come back", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Updater.permissionIntent(this)) }
            return
        }
        try {
            startActivity(Updater.installIntent(this, apk))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- settings sheet & pickers

    private val hideZoomRow = Runnable { b.zoomRow.visibility = View.GONE }

    private fun showZoomSlider() {
        b.zoomRow.visibility = View.VISIBLE
        handler.removeCallbacks(hideZoomRow)
        handler.postDelayed(hideZoomRow, 3000)
    }

    private class SettingRow(
        val section: String,
        val title: String,
        val source: TextView,
        val value: () -> String,
        val closesSheet: Boolean = false,
    )

    /** Settings grouped into sections; each row mirrors (and clicks) a hidden control. */
    private fun showSettingsSheet() {
        refreshUi()
        val rows = listOf(
            SettingRow("Camera", "Grid lines", b.btnGrid, { if (gridOn) "On" else "Off" }),
            SettingRow("Camera", "Video quality", b.btnQuality, { qualityLabel(preferredQuality) }),
            SettingRow("Camera", "Photo style", b.btnEffect, { extensionLabel(extensionMode) }),
            SettingRow("Camera", "Exposure (brightness)", b.btnExposure, { "Adjust" }, closesSheet = true),
            SettingRow("Camera", "Sharp motion (less vibration blur)", b.btnSharp, { if (sharpOn) "On" else "Off" }),
            SettingRow("Camera", "Mirror front camera", b.btnMirror, { if (mirrorFront) "On" else "Off" }),
            SettingRow("Dual / Vlog", "Recording quality", b.btnDualQuality, { if (dualFullHd) "1080p" else "720p" }),
            SettingRow("Sound", "Microphone", b.btnMicSource, { Mics.find(this, micChoice).label }),
            SettingRow("Sound", "Wind filter", b.btnWind, { arrayOf("Off", "Light", "Strong")[windLevel] }),
            SettingRow("Ride", "Ride mode (dark screen)", b.btnRide, { if (rideAuto) "On" else "Off" }),
            SettingRow("Ride", "Speed overlay", b.btnOverlay, { if (overlayOn) "On" else "Off" }),
            SettingRow("Ride", "Overlay position", b.btnOverlayPos, {
                when (overlayCorner) {
                    PipCorner.TOP_LEFT -> "Top left"
                    PipCorner.TOP_RIGHT -> "Top right"
                    PipCorner.BOTTOM_RIGHT -> "Bottom right"
                    PipCorner.BOTTOM_LEFT -> "Bottom left"
                }
            }),
            SettingRow("Ride", "Fast-forward speed", b.btnFastSpeed, { "${fastSpeed}x" }),
            SettingRow("Ride", "Fast-forward motion blur", b.btnFfBlur, { if (ffMotionBlur) "On" else "Off" }),
            SettingRow("Ride", "Speed blur effect", b.btnSpeedBlur, {
                arrayOf("Off", "Subtle", "Strong")[speedBlurLevel]
            }),
            SettingRow("Ride", "Horizon (road camera)", b.btnHorizon, {
                arrayOf("Off", "Level mount", "Lock horizon")[horizonMode]
            }),
        )
        val dp = resources.displayMetrics.density
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
        }
        list.addView(TextView(this).apply {
            text = "Settings"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        })
        var lastSection = ""
        for (row in rows) {
            if (row.source.visibility != View.VISIBLE) continue
            if (row.section != lastSection) {
                lastSection = row.section
                list.addView(TextView(this).apply {
                    text = row.section.uppercase()
                    textSize = 12f
                    letterSpacing = 0.12f
                    setTextColor(0xFFFFD54F.toInt())
                    setPadding(0, (18 * dp).toInt(), 0, (6 * dp).toInt())
                })
            }
            val value = TextView(this).apply {
                text = row.value()
                textSize = 15f
                setTextColor(0xFFFFD54F.toInt())
            }
            val line = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (52 * dp).toInt()
                isClickable = true
                isFocusable = true
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0)
                ta.recycle()
                addView(TextView(this@MainActivity).apply {
                    text = row.title
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(value)
                setOnClickListener {
                    if (!row.source.isEnabled) {
                        Toast.makeText(this@MainActivity, "Not available while recording", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    row.source.performClick()
                    if (row.closesSheet) sheet.dismiss() else value.text = row.value()
                }
            }
            list.addView(line)
        }
        list.addView(TextView(this).apply {
            text = "❓  Buttons & gestures"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, (22 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener { showHelp() }
        })
        sheet.setContentView(android.widget.ScrollView(this).apply { addView(list) })
        sheet.show()
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Buttons & gestures")
            .setMessage(
                "Buttons\n" +
                    "• Phone volume buttons (or a Bluetooth selfie shutter): shutter / start-stop\n" +
                    "• Headset and intercom buttons are not used, so they keep controlling your music\n\n" +
                    "Vibration: triple buzz = fast-forward on, long buzz = fast-forward off. " +
                    "Warning tones only for heat, battery or storage problems.\n\n" +
                    "Screen\n" +
                    "• Pinch to zoom, tap to focus, double-tap to switch camera\n" +
                    "• ⏩ while recording marks parts to speed up; a sped-up copy is saved " +
                    "automatically when you stop (original kept)\n" +
                    "• ⇪ next to the gallery: resize, trim and join videos"
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    /** Visual layout chooser for Dual / Vlog mode. */
    private fun showLayoutPicker() {
        val dp = resources.displayMetrics.density
        val options = listOf(
            DualLayout.PIP to "◳\nPicture in\npicture",
            DualLayout.SIDE_BY_SIDE to "◫\nSide by\nside",
            DualLayout.STACKED to "⊟\nTop /\nbottom",
            DualLayout.SPLIT_CARD to "▣\nSplit\ncard",
            DualLayout.CUTOUT to "👤\nCutout\n(you over the view)",
            DualLayout.SOLO to "▢\nOne camera\nonly",
        )
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Choose a layout").create()
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 3
            setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        }
        for ((layout, label) in options) {
            val locked = isRecording && (layout == DualLayout.CUTOUT || layout == DualLayout.SOLO ||
                dualLayout == DualLayout.CUTOUT || dualLayout == DualLayout.SOLO) && layout != dualLayout
            val tile = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(if (layout == dualLayout) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                setBackgroundResource(if (layout == dualLayout) R.drawable.pill_bg_active else R.drawable.pill_bg)
                alpha = if (locked) 0.35f else 1f
                setPadding((6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt())
                setOnClickListener {
                    if (locked) {
                        Toast.makeText(this@MainActivity, "Stop recording to switch to/from this layout", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    setDualLayout(layout)
                }
            }
            grid.addView(tile, android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            })
        }
        dialog.setView(grid)
        dialog.show()
    }

    private fun setDualLayout(next: DualLayout) {
        val old = dualLayout
        if (old == next) return
        dualLayout = next
        applyDualSettings()
        val needsRebind = { l: DualLayout -> l == DualLayout.CUTOUT || l == DualLayout.SOLO }
        if (needsRebind(old) || needsRebind(next)) bindCamera() else refreshUi()
    }

    // ---------------------------------------------------------------- cutout segmentation

    /** Finds the person in a front-camera frame and hands the mask to the renderer. */
    @androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
    private fun analyzeForCutout(proxy: androidx.camera.core.ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val input = com.google.mlkit.vision.common.InputImage.fromMediaImage(
            media, proxy.imageInfo.rotationDegrees
        )
        segmenter.process(input)
            .addOnSuccessListener(analysisExecutor) { mask ->
                val buf = mask.buffer
                buf.rewind()
                val n = mask.width * mask.height
                val bytes = ByteArray(n)
                for (i in 0 until n) {
                    bytes[i] = (buf.float.coerceIn(0f, 1f) * 255f).toInt().toByte()
                }
                dualRenderer?.updateMask(bytes, mask.width, mask.height)
            }
            .addOnCompleteListener(analysisExecutor) { proxy.close() }
    }

    // ---------------------------------------------------------------- export for social media

    /** The ⇪ button: resize for social media, trim, or join ride parts. */
    private fun startExportFlow() {
        if (isRecording) {
            Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Edit & export")
            .setItems(
                arrayOf(
                    "📐  Resize for Instagram / YouTube / Facebook",
                    "✂️  Trim a video",
                    "🔗  Join ride parts into one video",
                )
            ) { _, which ->
                when (which) {
                    0 -> startResizeFlow()
                    1 -> startTrimFlow()
                    else -> pickJoinVideos.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startTrimFlow() {
        val pick = {
            pickTrimVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        val last = lastMediaUri
        if (last == null || !lastMediaIsVideo) { pick(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Trim which video?")
            .setItems(arrayOf("Last video", "Choose from gallery…")) { _, which ->
                if (which == 0) showTrimDialog(last) else pick()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatMs(ms: Long): String {
        val totalTenths = ms / 100
        val m = totalTenths / 600
        val sec = (totalTenths / 10) % 60
        val t = totalTenths % 10
        return String.format(Locale.US, "%d:%02d.%d", m, sec, t)
    }

    /** Preview + range slider to pick the part to keep. Saves a new file; the original is kept. */
    private fun showTrimDialog(uri: Uri) {
        val durationMs = runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(this, uri)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            } finally {
                r.release()
            }
        }.getOrNull()
        if (durationMs == null || durationMs < 1000) {
            Toast.makeText(this, "Can't read this video", Toast.LENGTH_SHORT).show()
            return
        }
        val dp = resources.displayMetrics.density
        val video = android.widget.VideoView(this).apply {
            setVideoURI(uri)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (260 * dp).toInt()
            )
            setOnPreparedListener { seekTo(1) }
        }
        val label = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        val slider = com.google.android.material.slider.RangeSlider(this).apply {
            valueFrom = 0f
            valueTo = durationMs / 1000f
            values = listOf(0f, durationMs / 1000f)
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
        }
        val play = TextView(this).apply {
            text = "▶  Preview selection"
            setTextColor(0xFF000000.toInt())
            setBackgroundResource(R.drawable.pill_bg_active)
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }
        fun startMs() = (slider.values[0] * 1000).toLong()
        fun endMs() = (slider.values[1] * 1000).toLong()
        fun updateLabel() {
            label.text = "Keep ${formatMs(startMs())} → ${formatMs(endMs())}   (${formatMs(endMs() - startMs())})"
        }
        updateLabel()
        val stopAtEnd = object : Runnable {
            override fun run() {
                if (!video.isPlaying) return
                if (video.currentPosition >= endMs()) video.pause() else handler.postDelayed(this, 100)
            }
        }
        slider.addOnChangeListener(com.google.android.material.slider.RangeSlider.OnChangeListener { s, _, fromUser ->
            updateLabel()
            if (fromUser) {
                video.pause()
                val thumb = s.focusedThumbIndex.coerceIn(0, 1)
                video.seekTo((s.values[thumb] * 1000).toInt())
            }
        })
        play.setOnClickListener {
            video.seekTo(startMs().toInt())
            video.start()
            handler.postDelayed(stopAtEnd, 100)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(video)
            addView(label)
            addView(slider)
            addView(play, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Trim video")
            .setView(box)
            .setPositiveButton("Save trimmed copy") { _, _ ->
                val start = startMs()
                val end = endMs()
                if (end - start < 500) {
                    Toast.makeText(this, "Selection is too short", Toast.LENGTH_SHORT).show()
                } else {
                    runVideoJob("Trimming") { progress, done -> exporter.trim(uri, start, end, progress, done) }
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                handler.removeCallbacks(stopAtEnd)
                video.stopPlayback()
            }
            .show()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Orders the picked parts by file name (our files are named by date/time) and confirms. */
    private fun confirmJoin(picked: List<Uri>) {
        val named = picked.map { it to (displayName(it) ?: "") }
        val ordered = if (named.all { it.second.isNotEmpty() }) named.sortedBy { it.second } else named
        val list = ordered.mapIndexed { i, (_, name) -> "${i + 1}. ${name.ifEmpty { "Video ${i + 1}" }}" }
            .joinToString("\n")
        MaterialAlertDialogBuilder(this)
            .setTitle("Join ${ordered.size} videos in this order?")
            .setMessage(list)
            .setPositiveButton("Join") { _, _ ->
                val uris = ordered.map { it.first }
                runVideoJob("Joining") { progress, done -> exporter.join(uris, progress, done) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Shows a progress dialog for a trim/join job, then the result options. */
    private fun runVideoJob(
        title: String,
        start: (onProgress: (Int) -> Unit, onDone: (Uri?, String?) -> Unit) -> Unit,
    ) {
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage("Starting…")
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> exporter.cancel() }
            .create()
        progress.show()
        start({ p -> progress.setMessage(if (p >= 0) "$title… $p%" else "$title…") }) { out, error ->
            if (progress.isShowing) progress.dismiss()
            if (isFinishing || isDestroyed) return@start
            if (out == null) {
                if (error != null) Toast.makeText(this, "$title failed: $error", Toast.LENGTH_LONG).show()
                return@start
            }
            lastMediaUri = out
            lastMediaIsVideo = true
            setLastMedia(out, true)
            MaterialAlertDialogBuilder(this)
                .setTitle("Done")
                .setMessage("Saved to Movies/VlogCam/Exports. The original files are unchanged.")
                .setPositiveButton("Share") { _, _ -> shareMedia(out, true) }
                .setNeutralButton("Resize for…") { _, _ -> chooseExportPreset(out) }
                .setNegativeButton("Done", null)
                .show()
        }
    }

    private fun startResizeFlow() {
        val pick = {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        val last = lastMediaUri ?: run { pick(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Export which file?")
            .setItems(
                arrayOf(if (lastMediaIsVideo) "Last video" else "Last photo", "Choose from gallery…")
            ) { _, which -> if (which == 0) chooseExportPreset(last) else pick() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseExportPreset(uri: Uri) {
        val presets = ExportPreset.ALL
        MaterialAlertDialogBuilder(this)
            .setTitle("Export for…")
            .setItems(presets.map { it.label }.toTypedArray()) { _, i -> chooseExportFit(uri, presets[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseExportFit(uri: Uri, preset: ExportPreset) {
        MaterialAlertDialogBuilder(this)
            .setTitle("${preset.platform} · ${preset.name}")
            .setItems(
                arrayOf("Crop to fill the frame (no bars)", "Fit the whole picture (black bars)")
            ) { _, which -> runExport(uri, preset, crop = which == 0) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runExport(uri: Uri, preset: ExportPreset, crop: Boolean) {
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle("Exporting for ${preset.platform}")
            .setMessage("Starting…")
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> exporter.cancel() }
            .create()
        progress.show()
        exporter.export(
            uri, preset, crop,
            onProgress = { p -> progress.setMessage(if (p >= 0) "Converting… $p%" else "Converting…") },
        ) { out, error ->
            if (progress.isShowing) progress.dismiss()
            if (isFinishing || isDestroyed) return@export
            if (out == null) {
                if (error != null) {
                    Toast.makeText(this, "Export failed: $error", Toast.LENGTH_LONG).show()
                }
                return@export
            }
            val video = exporter.isVideo(out)
            MaterialAlertDialogBuilder(this)
                .setTitle("Export ready")
                .setMessage(
                    "Saved ${preset.width}×${preset.height} to " +
                        (if (video) "Movies" else "Pictures") + "/VlogCam/Exports."
                )
                .setPositiveButton("Share") { _, _ -> shareMedia(out, video) }
                .setNeutralButton("View") { _, _ ->
                    lastMediaUri = out
                    lastMediaIsVideo = video
                    openLastMedia()
                }
                .setNegativeButton("Done", null)
                .show()
        }
    }

    private fun shareMedia(uri: Uri, video: Boolean) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (video) "video/mp4" else "image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(send, "Share to…"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app to share with", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMessage(msg: String) {
        b.messageText.text = msg
        b.messageText.visibility = View.VISIBLE
    }

    companion object {
        private const val TAG = "VlogCam"
        private const val RIDE_DELAY_MS = 8_000L
        private const val SEGMENT_SECONDS = 600L
        private const val MIN_FREE_BYTES = 300L * 1024 * 1024
        private const val LOW_FREE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
