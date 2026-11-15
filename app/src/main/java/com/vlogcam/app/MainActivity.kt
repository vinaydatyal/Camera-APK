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
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
        if (installAfterPermission && Updater.canInstall(this)) {
            installAfterPermission = false
            downloadedApk?.let { launchInstaller(it) }
        }
        if (System.currentTimeMillis() - lastUpdateCheck > 10 * 60 * 1000L) checkForUpdate()
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
        cancelCountdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
        recording?.stop()
        ioExecutor.shutdown()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_CAMERA
        ) {
            if (event.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
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
        imageCapture?.targetRotation = rotation
        if (recording == null) videoCapture?.targetRotation = rotation
    }

    /** (Re)binds use cases for the current mode. */
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (recording != null) return
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
                    "This phone doesn't allow two cameras at once. Switched to video.",
                    Toast.LENGTH_LONG
                ).show()
                mode = Mode.VIDEO
                bindCamera()
                return
            } catch (e: Exception) {
                Log.e(TAG, "Bind failed (stab=$allowStab)", e)
                if (allowStab && stabilizationActive) continue
                if (mode == Mode.DUAL && dualFullHd) {
                    dualFullHd = false
                    Toast.makeText(this, "Full HD dual not supported, using HD", Toast.LENGTH_SHORT).show()
                    bindCamera()
                    return
                }
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        if (ok) {
            observeCamera(activeCamera())
            updateTargetRotation()
        }
        refreshUi()
    }

    private fun clearBound() {
        camera = null
        dualCameras = emptyList()
        preview = null
        imageCapture = null
        videoCapture = null
        stabilizationActive = false
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
                .setTargetRotation(rotation)
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
                .setTargetRotation(rotation)
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
        val pairs = provider.availableConcurrentCameraInfos
        if (pairs.isEmpty()) throw DualUnsupportedException()
        val pair = pairs.firstOrNull { list ->
            list.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
                list.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
        } ?: pairs.first()
        if (pair.size < 2) throw DualUnsupportedException()
        val back = pair.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK } ?: pair[0]
        val other = pair.first { it !== back }
        val (primaryInfo, secondaryInfo) = if (dualSwapped) other to back else back to other

        b.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        b.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        val rotation = displayRotation()
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()
        val p = Preview.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(rotation)
            .build()
        val quality = if (dualFullHd) Quality.FHD else Quality.HD
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        val caps = runCatching { Recorder.getVideoCapabilities(primaryInfo) }.getOrNull()
        stabilizationSupported = caps?.isStabilizationSupported == true
        stabilizationActive = allowStab && stabilizationOn && stabilizationSupported
        val vcBuilder = VideoCapture.Builder(recorder).setTargetRotation(rotation)
        if (stabilizationActive) vcBuilder.setVideoStabilizationEnabled(true)
        val vc = vcBuilder.build()

        val group = UseCaseGroup.Builder().addUseCase(p).addUseCase(vc).build()
        val (primarySettings, secondarySettings) =
            DualComposer.settings(dualLayout, pipCorner, DualComposer.PIP_SIZES[pipSizeIndex])

        p.setSurfaceProvider(b.previewView.surfaceProvider)
        val owner = DisposableLifecycleOwner(this)
        dualOwner = owner
        val concurrent = provider.bindToLifecycle(
            listOf(
                SingleCameraConfig(primaryInfo.cameraSelector, group, primarySettings, owner),
                SingleCameraConfig(secondaryInfo.cameraSelector, group, secondarySettings, owner),
            )
        )
        dualCameras = concurrent.cameras
        preview = p
        videoCapture = vc
        dualZoomTarget = dualZoomTarget.coerceIn(0, (dualCameras.size - 1).coerceAtLeast(0))
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
                setOnClickListener { activeCamera()?.cameraControl?.setZoomRatio(s) }
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
            if (mode == Mode.VIDEO) bindCamera() else refreshUi()
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
            override fun onStartTrackingTouch(slider: Slider) { zoomSliderDragging = true }
            override fun onStopTrackingTouch(slider: Slider) { zoomSliderDragging = false }
        })

        // Dual controls
        b.btnLayout.setOnClickListener {
            dualLayout = DualLayout.entries[(dualLayout.ordinal + 1) % DualLayout.entries.size]
            bindCamera()
        }
        b.btnCorner.setOnClickListener {
            pipCorner = PipCorner.entries[(pipCorner.ordinal + 1) % PipCorner.entries.size]
            bindCamera()
        }
        b.btnPipSize.setOnClickListener {
            pipSizeIndex = (pipSizeIndex + 1) % DualComposer.PIP_SIZES.size
            bindCamera()
        }
        b.btnSwap.setOnClickListener { switchCamera() }
        b.btnZoomTarget.setOnClickListener {
            if (dualCameras.size < 2) return@setOnClickListener
            dualZoomTarget = (dualZoomTarget + 1) % dualCameras.size
            observeCamera(activeCamera())
            refreshUi()
        }
        b.btnDualQuality.setOnClickListener {
            dualFullHd = !dualFullHd
            bindCamera()
        }
    }

    private fun setMode(newMode: Mode) {
        if (recording != null || mode == newMode) return
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
        if (recording != null) return
        if (mode == Mode.DUAL) {
            dualSwapped = !dualSwapped
            dualZoomTarget = 0
        } else {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            torchOn = false
        }
        b.btnSwitch.animate().rotationBy(180f).setDuration(250).start()
        bindCamera()
    }

    private fun refreshUi() {
        val isRecording = recording != null
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
            if (single && lensFacing == CameraSelector.LENS_FACING_FRONT) View.VISIBLE else View.GONE
        b.btnMirror.text = if (mirrorFront) "⇋ Mirror On" else "⇋ Mirror Off"
        active(b.btnMirror, mirrorFront)

        b.btnExposure.visibility = if (b.btnExposure.tag == true) View.VISIBLE else View.GONE

        // Dual panel
        b.dualPanel.visibility = if (mode == Mode.DUAL) View.VISIBLE else View.GONE
        b.btnLayout.text = "▦ " + dualLayout.label
        val pip = dualLayout == DualLayout.PIP
        b.btnCorner.visibility = if (pip) View.VISIBLE else View.GONE
        b.btnPipSize.visibility = if (pip) View.VISIBLE else View.GONE
        b.btnPipSize.text = "Inset: " + DualComposer.PIP_SIZE_LABELS[pipSizeIndex]
        val targetCam = dualCameras.getOrNull(dualZoomTarget)
        b.btnZoomTarget.text = "🔍 Zoom: " + when (targetCam?.cameraInfo?.lensFacing) {
            CameraSelector.LENS_FACING_FRONT -> "Front"
            CameraSelector.LENS_FACING_BACK -> "Rear"
            else -> "Cam ${dualZoomTarget + 1}"
        }
        b.btnDualQuality.text = if (dualFullHd) "1080p" else "720p"
        for (v in listOf(b.btnLayout, b.btnCorner, b.btnPipSize, b.btnSwap, b.btnDualQuality)) {
            v.isEnabled = !isRecording
            v.alpha = if (isRecording) 0.4f else 1f
        }
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
        b.btnPause.visibility = if (isRecording) View.VISIBLE else View.GONE
        b.btnPause.text = if (recordingPaused) "▶" else "❚❚"
        b.btnSwitch.isEnabled = !isRecording
        b.btnSwitch.alpha = if (isRecording) 0.4f else 1f
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
            Mode.VIDEO, Mode.DUAL -> if (recording == null) withTimer { startRecording() } else stopRecording()
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

    /** Dual photo: grab the composed preview frame (exactly what's on screen). */
    private fun takeDualSnapshot() {
        val bmp = b.previewView.bitmap
        if (bmp == null) {
            Toast.makeText(this, "Preview not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val cropped = cropToContent(bmp)
        flashScreen()
        ioExecutor.execute {
            val uri = MediaSaver.saveBitmap(this, cropped, MediaSaver.fileName("DUAL"))
            runOnUiThread {
                if (uri != null) setLastMedia(uri, false)
                else Toast.makeText(this, "Could not save photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Removes the letterbox bars that FIT_CENTER adds around the stream. */
    private fun cropToContent(bmp: Bitmap): Bitmap {
        val info = preview?.resolutionInfo ?: return bmp
        val rot = info.rotationDegrees
        var w = info.resolution.width.toFloat()
        var h = info.resolution.height.toFloat()
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        val vw = bmp.width.toFloat()
        val vh = bmp.height.toFloat()
        if (w <= 0f || h <= 0f) return bmp
        val srcAspect = w / h
        val (cw, ch) = if (vw / vh > srcAspect) (vh * srcAspect) to vh else vw to (vw / srcAspect)
        val left = ((vw - cw) / 2f).roundToInt().coerceAtLeast(0)
        val top = ((vh - ch) / 2f).roundToInt().coerceAtLeast(0)
        val width = cw.roundToInt().coerceAtMost(bmp.width - left)
        val height = ch.roundToInt().coerceAtMost(bmp.height - top)
        if (width <= 0 || height <= 0) return bmp
        return Bitmap.createBitmap(bmp, left, top, width, height)
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        updateTargetRotation()
        val options = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(MediaSaver.videoValues(MediaSaver.fileName(if (mode == Mode.DUAL) "VLOG" else "VID")))
            .build()
        var pending = vc.output.prepareRecording(this, options)
        if (micOn && hasPermission(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        recordingPaused = false
        recording = pending.start(uiExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> refreshUi()
                is VideoRecordEvent.Status -> {
                    val secs = TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos)
                    val dot = if (recordingPaused) "❚❚" else "●"
                    b.recordTime.text = String.format(Locale.US, "%s %02d:%02d", dot, secs / 60, secs % 60)
                }
                is VideoRecordEvent.Pause -> { recordingPaused = true; refreshUi() }
                is VideoRecordEvent.Resume -> { recordingPaused = false; refreshUi() }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    recordingPaused = false
                    val uri = event.outputResults.outputUri
                    if (!event.hasError() || uri != Uri.EMPTY) {
                        if (uri != Uri.EMPTY) setLastMedia(uri, true)
                        if (event.hasError()) Log.w(TAG, "Recording finished with error ${event.error}")
                    } else {
                        Toast.makeText(this, "Recording failed (code ${event.error})", Toast.LENGTH_LONG).show()
                    }
                    b.recordTime.text = "● 00:00"
                    refreshUi()
                }
            }
        }
        refreshUi()
    }

    private fun stopRecording() {
        recording?.stop()
    }

    private fun togglePause() {
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
        if (recording != null) {
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

    private fun showMessage(msg: String) {
        b.messageText.text = msg
        b.messageText.visibility = View.VISIBLE
    }

    companion object {
        private const val TAG = "VlogCam"
    }
}
