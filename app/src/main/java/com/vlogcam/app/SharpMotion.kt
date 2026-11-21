package com.vlogcam.app

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.Preview
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * "Sharp motion": keeps the shutter fast (1/250 s) so vibration doesn't smear each frame,
 * and locks 30 fps so the camera can't slow to 15 fps in the dark.
 *
 * - Android 16+ phones with shutter-priority auto exposure: the camera does it natively.
 * - Otherwise (Vlog pipeline): manual exposure with a fast shutter; ISO is steered by the
 *   measured picture brightness. In bright light normal auto exposure is used (it's fast anyway).
 * - Everything else: only the 30 fps lock.
 */
@OptIn(ExperimentalCamera2Interop::class)
class SharpMotion(private val renderer: () -> DualRenderer?) {

    class AeResult {
        @Volatile var expNs = 0L
        @Volatile var iso = 0
    }

    private val results = Array(2) { AeResult() }

    private class Cam(
        val slot: Int,
        val ctrl: Camera2CameraControl,
        val isoRange: Range<Int>?,
        val expRange: Range<Long>?,
        val fps: Range<Int>?,
        val manualOk: Boolean,
        val priorityOk: Boolean,
    ) {
        var manual = false
        var iso = 0
        var expNs = TARGET_NS
        var targetLuma = 0.4f
    }

    private val cams = mutableListOf<Cam>()
    private val handler = Handler(Looper.getMainLooper())
    var enabled = false

    private val loop = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, 250)
        }
    }

    /** Lets the controller read the auto-exposure result of camera [slot]. Call before binding. */
    fun attach(builder: Preview.Builder, slot: Int) {
        val res = results[slot]
        Camera2Interop.Extender(builder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: android.hardware.camera2.CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)?.let { res.expNs = it }
                    result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let { res.iso = it }
                }
            }
        )
    }

    /** (camera, slot) pairs currently bound; slot -1 = no brightness measurement available. */
    fun setCameras(list: List<Pair<Camera, Int>>) {
        handler.removeCallbacks(loop)
        cams.clear()
        renderer()?.measureLuma = false
        if (!enabled) return
        var needLoop = false
        for ((camera, slot) in list) {
            val cam = runCatching { describe(camera, slot) }.getOrNull() ?: continue
            cams += cam
            when {
                cam.priorityOk -> applyPriority(cam)
                else -> applyAuto(cam)
            }
            if (!cam.priorityOk && cam.manualOk && slot >= 0) needLoop = true
        }
        if (needLoop) {
            renderer()?.measureLuma = true
            handler.postDelayed(loop, 500)
        }
    }

    /** Turns it off and gives exposure control back to the camera. */
    fun disable(list: List<Pair<Camera, Int>>) {
        enabled = false
        handler.removeCallbacks(loop)
        cams.clear()
        renderer()?.measureLuma = false
        for ((camera, _) in list) runCatching { Camera2CameraControl.from(camera.cameraControl).clearCaptureRequestOptions() }
    }

    private fun describe(camera: Camera, slot: Int): Cam {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manualOk = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val fpsRanges = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val fps = fpsRanges?.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: fpsRanges?.filter { it.upper == 30 }?.maxByOrNull { it.lower }
        var priorityOk = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val modes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)
            priorityOk = modes?.contains(CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY) == true
        }
        return Cam(
            slot = slot,
            ctrl = Camera2CameraControl.from(camera.cameraControl),
            isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            expRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            fps = fps,
            manualOk = manualOk,
            priorityOk = priorityOk,
        )
    }

    private fun base(c: Cam) = CaptureRequestOptions.Builder().apply {
        c.fps?.let { setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
    }

    private fun applyAuto(c: Cam) {
        val o = base(c).setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        c.ctrl.setCaptureRequestOptions(o.build())
    }

    private fun applyPriority(c: Cam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return applyAuto(c)
        val o = base(c)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_PRIORITY_MODE,
                CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY
            )
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExp(c, TARGET_NS))
        c.ctrl.setCaptureRequestOptions(o.build())
    }

    private fun applyManual(c: Cam) {
        val o = base(c)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExp(c, c.expNs))
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, c.iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, FRAME_NS)
        c.ctrl.setCaptureRequestOptions(o.build())
    }

    private fun clampExp(c: Cam, ns: Long): Long =
        c.expRange?.let { ns.coerceIn(it.lower, it.upper) } ?: ns

    private fun step() {
        val r = renderer() ?: return
        for (c in cams) {
            if (c.priorityOk || !c.manualOk || c.slot < 0) continue
            try {
                val minIso = c.isoRange?.lower ?: 50
                val maxIso = c.isoRange?.upper ?: 3200
                if (!c.manual) {
                    val res = results[c.slot]
                    val exp = res.expNs
                    val iso = res.iso
                    if (exp <= 0 || iso <= 0) continue
                    // Auto exposure wants a slow shutter (dim light): take over with a fast one.
                    if (exp > TARGET_NS * 1.15) {
                        val l = r.luma(c.slot)
                        c.targetLuma = if (l > 0.05f) l else 0.4f
                        setExposure(c, iso.toDouble() * exp, maxIso)
                        c.manual = true
                        applyManual(c)
                    }
                } else {
                    val l = r.luma(c.slot)
                    if (l < 0.01f) continue
                    val ratio = c.targetLuma / l
                    if (abs(ratio - 1f) < 0.07f) continue
                    // Picture brightness is gamma-encoded, so exposure must change more than brightness.
                    val gain = ratio.coerceIn(0.6f, 1.6f).toDouble().pow(1.6)
                    val total = c.iso.toDouble() * c.expNs * gain
                    if (total / TARGET_NS < minIso * 1.3) {
                        // Bright enough: normal auto exposure already uses a fast shutter.
                        c.manual = false
                        applyAuto(c)
                        continue
                    }
                    setExposure(c, total, maxIso)
                    applyManual(c)
                }
            } catch (e: Exception) {
                Log.w("SharpMotion", "Exposure step failed", e)
            }
        }
    }

    /** Splits a total exposure (ISO × ns) into a fast shutter + ISO, lengthening only at max ISO. */
    private fun setExposure(c: Cam, total: Double, maxIso: Int) {
        var exp = TARGET_NS.toDouble()
        var iso = total / exp
        if (iso > maxIso) {
            iso = maxIso.toDouble()
            exp = min(MAX_NS.toDouble(), total / iso)
        }
        c.iso = iso.toInt()
        c.expNs = exp.toLong()
    }

    companion object {
        const val TARGET_NS = 4_000_000L       // 1/250 s
        private const val MAX_NS = 10_000_000L  // never slower than 1/100 s
        private const val FRAME_NS = 33_333_333L
    }
}
