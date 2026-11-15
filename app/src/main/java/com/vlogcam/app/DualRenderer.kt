package com.vlogcam.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the two concurrent camera streams into one frame with OpenGL.
 *
 * The same scene is drawn to the on-screen SurfaceView, to the video encoder while
 * recording, and to an offscreen buffer for snapshots, so what you see is what you get.
 *
 * Slot 0 is the rear camera, slot 1 the other camera. [swapped] decides which is "main".
 */
class DualRenderer {

    private class Stream(val texId: Int, val st: SurfaceTexture, val bufW: Int, val bufH: Int) {
        val matrix = FloatArray(16)
        @Volatile var info: SurfaceRequest.TransformationInfo? = null
        var hasFrame = false
    }

    private class Mask(val bytes: ByteArray, val width: Int, val height: Int)

    private val thread = HandlerThread("DualRenderer").apply { start() }
    private val handler = Handler(thread.looper)
    private val glExecutor = Executor { handler.post(it) }

    // Settings: written on the main thread, read on the GL thread.
    @Volatile var layout = DualLayout.PIP
    @Volatile var corner = PipCorner.TOP_RIGHT
    @Volatile var pipSize = DualComposer.PIP_SIZES[1]
    @Volatile var cutoutScale = 1f
    @Volatile var swapped = false
    @Volatile var mirrorFront = true
    @Volatile var displayRotationDeg = 0
    /** Fit: show each camera's whole picture over a blurred background instead of cropping. */
    @Volatile var fit = false
    /** Skip drawing to the screen (ride mode) while still feeding the encoder. */
    @Volatile var displayPaused = false
    /** Corner for the speed/location overlay. */
    @Volatile var overlayCorner = PipCorner.BOTTOM_LEFT
    private val isFront = BooleanArray(2)

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var dummySurface = EGL14.EGL_NO_SURFACE

    private var displaySurface = EGL14.EGL_NO_SURFACE
    private var displaySurfaceObj: Surface? = null
    private var displayW = 0
    private var displayH = 0

    private var encoderSurface = EGL14.EGL_NO_SURFACE
    private var encW = 0
    private var encH = 0
    private var encRotationDeg = 0

    // Camera (external texture) program
    private var oesProg = 0
    private var oesPos = 0
    private var oesTex = 0
    private var oesSampler = 0

    // Cutout program: camera texture + person mask -> alpha
    private var cutProg = 0
    private var cutPos = 0
    private var cutTex = 0
    private var cutMaskCoord = 0
    private var cutSampler = 0
    private var cutMaskSampler = 0

    // Gaussian blur program on a regular 2D texture
    private var blurProg = 0
    private var blurPos = 0
    private var blurTex = 0
    private var blurSampler = 0
    private var blurStep = 0
    private var blurDim = 0

    private val posBuf = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTexBuf = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private val texBuf = floatBuffer(FloatArray(8))
    private val maskBuf = floatBuffer(FloatArray(8))
    private val texCoords = FloatArray(8)
    private val maskCoords = FloatArray(8)

    // Small offscreen buffers for the background blur.
    private val fboTex = IntArray(2)
    private val fbo = IntArray(2)

    // Person mask for the cutout layout.
    private var maskTex = 0
    private var maskReady = false
    private val pendingMask = AtomicReference<Mask?>(null)

    // Speed/location overlay (premultiplied RGBA bitmap texture).
    private var overlayTex = 0
    private var overlayReady = false
    private var overlayAspect = 1f
    private val pendingOverlay = AtomicReference<Bitmap?>(null)
    private var texProg = 0
    private var texPos = 0
    private var texTex = 0
    private var texSampler = 0
    private val flippedTexBuf = floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

    private val streams = arrayOfNulls<Stream>(2)
    private var released = false

    init {
        handler.post { initGl() }
    }

    // ------------------------------------------------------------------ public API

    fun setFront(slot: Int, front: Boolean) {
        isFront[slot] = front
    }

    /** SurfaceProvider for the Preview use case of camera [slot]. */
    fun surfaceProvider(slot: Int): Preview.SurfaceProvider = Preview.SurfaceProvider { request ->
        handler.post { provideSurface(slot, request) }
    }

    /**
     * New person mask (0-255 per pixel, rows top to bottom, upright and un-mirrored).
     * Only the latest mask is kept if the GL thread is busy.
     */
    fun updateMask(bytes: ByteArray, width: Int, height: Int) {
        if (pendingMask.getAndSet(Mask(bytes, width, height)) == null) {
            handler.post { uploadMask() }
        }
    }

    /** Sets the overlay image (drawn on every output), or null to hide it. */
    fun setOverlay(bitmap: Bitmap?) {
        if (bitmap == null) {
            pendingOverlay.set(null)
            handler.post { overlayReady = false }
            return
        }
        if (pendingOverlay.getAndSet(bitmap) == null) handler.post { uploadOverlay() }
    }

    private fun uploadOverlay() {
        val bmp = pendingOverlay.getAndSet(null) ?: return
        if (released || eglDisplay == EGL14.EGL_NO_DISPLAY) return
        makeCurrent(dummySurface)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        overlayAspect = bmp.width.toFloat() / bmp.height
        overlayReady = true
    }

    fun clearMask() {
        handler.post { maskReady = false }
    }

    /** Sets (or clears, with null) the on-screen surface. Blocks until applied. */
    fun setDisplaySurface(surface: Surface?, width: Int, height: Int) {
        runBlocking {
            if (surface !== displaySurfaceObj || surface == null) {
                destroySurface(displaySurface)
                displaySurface = EGL14.EGL_NO_SURFACE
                displaySurfaceObj = null
                if (surface != null && surface.isValid) {
                    displaySurface = createWindowSurface(surface)
                    displaySurfaceObj = surface
                }
            }
            displayW = width
            displayH = height
        }
    }

    /** Starts feeding frames to [surface] (the encoder input). Blocks until applied. */
    fun startEncoder(surface: Surface, width: Int, height: Int, rotationDeg: Int) {
        runBlocking {
            destroySurface(encoderSurface)
            encoderSurface = createWindowSurface(surface)
            encW = width
            encH = height
            encRotationDeg = rotationDeg
        }
    }

    /** Stops feeding the encoder. Blocks until no more frames will be sent. */
    fun stopEncoder() {
        runBlocking {
            destroySurface(encoderSurface)
            encoderSurface = EGL14.EGL_NO_SURFACE
        }
    }

    /** Renders one frame at [width]x[height] offscreen and returns it (on the GL thread). */
    fun snapshot(width: Int, height: Int, callback: (Bitmap?) -> Unit) {
        handler.post {
            val bmp = runCatching { renderToBitmap(width, height) }
                .onFailure { Log.e(TAG, "Snapshot failed", it) }
                .getOrNull()
            callback(bmp)
        }
    }

    fun release() {
        handler.post {
            released = true
            destroySurface(encoderSurface)
            destroySurface(displaySurface)
            encoderSurface = EGL14.EGL_NO_SURFACE
            displaySurface = EGL14.EGL_NO_SURFACE
            for (i in streams.indices) {
                streams[i]?.st?.setOnFrameAvailableListener(null)
                streams[i] = null
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                destroySurface(dummySurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ GL setup

    private fun runBlocking(block: () -> Unit) {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                if (!released) block()
            } catch (e: Exception) {
                Log.e(TAG, "GL op failed", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        for (alpha in intArrayOf(8, 0)) {
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, alpha,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
                eglConfig = configs[0]
                break
            }
        }
        checkNotNull(eglConfig) { "No suitable EGL config" }

        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        dummySurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        makeCurrent(dummySurface)

        oesProg = buildProgram(VERTEX_SHADER, OES_FRAGMENT_SHADER)
        oesPos = GLES20.glGetAttribLocation(oesProg, "aPos")
        oesTex = GLES20.glGetAttribLocation(oesProg, "aTex")
        oesSampler = GLES20.glGetUniformLocation(oesProg, "sTex")

        cutProg = buildProgram(CUTOUT_VERTEX_SHADER, CUTOUT_FRAGMENT_SHADER)
        cutPos = GLES20.glGetAttribLocation(cutProg, "aPos")
        cutTex = GLES20.glGetAttribLocation(cutProg, "aTex")
        cutMaskCoord = GLES20.glGetAttribLocation(cutProg, "aMask")
        cutSampler = GLES20.glGetUniformLocation(cutProg, "sTex")
        cutMaskSampler = GLES20.glGetUniformLocation(cutProg, "sMask")

        blurProg = buildProgram(VERTEX_SHADER, BLUR_FRAGMENT_SHADER)
        blurPos = GLES20.glGetAttribLocation(blurProg, "aPos")
        blurTex = GLES20.glGetAttribLocation(blurProg, "aTex")
        blurSampler = GLES20.glGetUniformLocation(blurProg, "sTex")
        blurStep = GLES20.glGetUniformLocation(blurProg, "uStep")
        blurDim = GLES20.glGetUniformLocation(blurProg, "uDim")

        // Two small render targets for the separable background blur.
        GLES20.glGenTextures(2, fboTex, 0)
        GLES20.glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, BLUR_W, BLUR_H, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            setTexParams(GLES20.GL_TEXTURE_2D)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0
            )
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val mt = IntArray(2)
        GLES20.glGenTextures(2, mt, 0)
        maskTex = mt[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        setTexParams(GLES20.GL_TEXTURE_2D)
        overlayTex = mt[1]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        setTexParams(GLES20.GL_TEXTURE_2D)

        texProg = buildProgram(VERTEX_SHADER, TEX_FRAGMENT_SHADER)
        texPos = GLES20.glGetAttribLocation(texProg, "aPos")
        texTex = GLES20.glGetAttribLocation(texProg, "aTex")
        texSampler = GLES20.glGetUniformLocation(texProg, "sTex")
    }

    private fun setTexParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun makeCurrent(surface: EGLSurface) {
        EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
    }

    private fun createWindowSurface(surface: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)

    private fun destroySurface(surface: EGLSurface) {
        if (surface == EGL14.EGL_NO_SURFACE || eglDisplay == EGL14.EGL_NO_DISPLAY) return
        makeCurrent(dummySurface)
        EGL14.eglDestroySurface(eglDisplay, surface)
    }

    private fun provideSurface(slot: Int, request: SurfaceRequest) {
        if (released || eglDisplay == EGL14.EGL_NO_DISPLAY) {
            request.willNotProvideSurface()
            return
        }
        makeCurrent(dummySurface)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        setTexParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

        val size = request.resolution
        val st = SurfaceTexture(tex[0])
        st.setDefaultBufferSize(size.width, size.height)
        val stream = Stream(tex[0], st, size.width, size.height)
        st.setOnFrameAvailableListener({ onFrame(slot, stream) }, handler)
        val surface = Surface(st)
        request.setTransformationInfoListener(glExecutor) { stream.info = it }
        streams[slot] = stream

        request.provideSurface(surface, glExecutor) {
            st.setOnFrameAvailableListener(null)
            surface.release()
            st.release()
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                makeCurrent(dummySurface)
                GLES20.glDeleteTextures(1, intArrayOf(stream.texId), 0)
            }
            if (streams[slot] === stream) streams[slot] = null
        }
    }

    private fun uploadMask() {
        val mask = pendingMask.getAndSet(null) ?: return
        if (released || eglDisplay == EGL14.EGL_NO_DISPLAY) return
        makeCurrent(dummySurface)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, mask.width, mask.height, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(mask.bytes)
        )
        maskReady = true
    }

    // ------------------------------------------------------------------ rendering

    private fun onFrame(slot: Int, stream: Stream) {
        if (released || streams[slot] !== stream) return
        try {
            makeCurrent(dummySurface)
            stream.st.updateTexImage()
            stream.st.getTransformMatrix(stream.matrix)
            stream.hasFrame = true

            if (!displayPaused && displaySurface != EGL14.EGL_NO_SURFACE && displayW > 0 && displayH > 0) {
                makeCurrent(displaySurface)
                drawScene(displayW, displayH, displayRotationDeg)
                EGL14.eglSwapBuffers(eglDisplay, displaySurface)
            }
            // Pace the video by the main camera so it gets a steady frame rate.
            if (encoderSurface != EGL14.EGL_NO_SURFACE && slot == mainSlot()) {
                makeCurrent(encoderSurface)
                drawScene(encW, encH, encRotationDeg)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderSurface, System.nanoTime())
                EGL14.eglSwapBuffers(eglDisplay, encoderSurface)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame render failed", e)
        }
    }

    private fun backSlot(): Int = if (isFront[0] && !isFront[1]) 1 else 0

    private fun preferredMainSlot(): Int =
        if (layout == DualLayout.CUTOUT) backSlot() else if (swapped) 1 else 0

    private fun mainSlot(): Int {
        val wanted = preferredMainSlot()
        return if (streams[wanted]?.hasFrame == true) wanted else 1 - wanted
    }

    private fun drawScene(w: Int, h: Int, rotationDeg: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val lay = layout
        val mainIdx = preferredMainSlot()
        val secondIdx = 1 - mainIdx
        val main = streams[mainIdx]?.takeIf { it.hasFrame }
        val second = streams[secondIdx]?.takeIf { it.hasFrame }
        val useFit = fit || lay == DualLayout.SPLIT_CARD
        val gap = max(2, (min(w, h) * 0.004f).roundToInt())

        if (useFit) {
            when {
                main != null -> drawBlurredBackground(main, mainIdx, w, h, rotationDeg)
                second != null -> drawBlurredBackground(second, secondIdx, w, h, rotationDeg)
            }
        }

        when (lay) {
            DualLayout.PIP -> {
                drawSlot(main, mainIdx, 0, 0, w, h, rotationDeg, useFit)
                if (second != null) {
                    val pw = (w * pipSize).roundToInt()
                    val ph = (h * pipSize).roundToInt()
                    val m = (min(w, h) * 0.04f).roundToInt()
                    val right = corner == PipCorner.TOP_RIGHT || corner == PipCorner.BOTTOM_RIGHT
                    val top = corner == PipCorner.TOP_RIGHT || corner == PipCorner.TOP_LEFT
                    val x = if (right) w - m - pw else m
                    val y = if (top) h - m - ph else m  // GL origin is bottom-left
                    fillRect(x - gap, y - gap, pw + 2 * gap, ph + 2 * gap, 1f)
                    drawStream(second, secondIdx, x, y, pw, ph, rotationDeg)
                }
            }
            DualLayout.SOLO -> drawSlot(main ?: second, if (main != null) mainIdx else secondIdx, 0, 0, w, h, rotationDeg, useFit)
            DualLayout.SIDE_BY_SIDE, DualLayout.SPLIT_CARD -> {
                val half = w / 2
                drawSlot(main, mainIdx, 0, 0, half, h, rotationDeg, useFit)
                drawSlot(second, secondIdx, half, 0, w - half, h, rotationDeg, useFit)
                if (!useFit) fillRect(half - gap / 2, 0, gap, h, 0f)
            }
            DualLayout.STACKED -> {
                val half = h / 2
                drawSlot(main, mainIdx, 0, h - half, w, half, rotationDeg, useFit)
                drawSlot(second, secondIdx, 0, 0, w, h - half, rotationDeg, useFit)
                if (!useFit) fillRect(0, h - half - gap / 2, w, gap, 0f)
            }
            DualLayout.CUTOUT -> {
                drawSlot(main, mainIdx, 0, 0, w, h, rotationDeg, useFit)
                if (second != null && maskReady) {
                    val s = cutoutScale.coerceIn(0.3f, 1f)
                    val cw = (w * s).roundToInt()
                    val ch = (h * s).roundToInt()
                    val x = when (corner) {
                        PipCorner.BOTTOM_RIGHT -> w - cw
                        PipCorner.BOTTOM_LEFT -> 0
                        else -> (w - cw) / 2
                    }
                    drawStream(second, secondIdx, x, 0, cw, ch, rotationDeg, cutout = true)
                }
            }
        }
        if (overlayReady) drawOverlay(w, h)
    }

    private fun drawOverlay(w: Int, h: Int) {
        val ow = (w * (if (w <= h) 0.62f else 0.36f)).roundToInt()
        val oh = (ow / overlayAspect).roundToInt()
        val m = (min(w, h) * 0.035f).roundToInt()
        val right = overlayCorner == PipCorner.TOP_RIGHT || overlayCorner == PipCorner.BOTTOM_RIGHT
        val top = overlayCorner == PipCorner.TOP_RIGHT || overlayCorner == PipCorner.TOP_LEFT
        val x = if (right) w - m - ow else m
        val y = if (top) h - m - oh else m
        GLES20.glViewport(x, y, ow, oh)
        GLES20.glUseProgram(texProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        GLES20.glUniform1i(texSampler, 0)
        posBuf.position(0)
        flippedTexBuf.position(0)
        GLES20.glVertexAttribPointer(texPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(texPos)
        GLES20.glVertexAttribPointer(texTex, 2, GLES20.GL_FLOAT, false, 0, flippedTexBuf)
        GLES20.glEnableVertexAttribArray(texTex)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)  // premultiplied
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(texPos)
        GLES20.glDisableVertexAttribArray(texTex)
    }

    private fun fillRect(x: Int, y: Int, w: Int, h: Int, gray: Float) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(x, y, w, h)
        GLES20.glClearColor(gray, gray, gray, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    /**
     * Smooth, darkened blur of [stream] filling the whole frame: render small, run a
     * separable Gaussian twice, then scale back up (bilinear upscaling adds more softness).
     */
    private fun drawBlurredBackground(stream: Stream, slot: Int, w: Int, h: Int, rotationDeg: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        drawStream(stream, slot, 0, 0, BLUR_W, BLUR_H, rotationDeg, aspectOverride = w.toFloat() / h)
        val sx = 1f / BLUR_W
        val sy = 1f / BLUR_H
        blurPass(fboTex[0], fbo[1], sx, 0f)
        blurPass(fboTex[1], fbo[0], 0f, sy)
        blurPass(fboTex[0], fbo[1], 2f * sx, 0f)
        blurPass(fboTex[1], fbo[0], 0f, 2f * sy)
        blurPass(fboTex[0], fbo[1], 4f * sx, 0f)
        blurPass(fboTex[1], fbo[0], 0f, 4f * sy)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        drawTexture2D(fboTex[0], 0f, 0f, 0.6f)
    }

    private fun blurPass(srcTex: Int, dstFbo: Int, stepX: Float, stepY: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
        GLES20.glViewport(0, 0, BLUR_W, BLUR_H)
        drawTexture2D(srcTex, stepX, stepY, 1f)
    }

    private fun drawTexture2D(tex: Int, stepX: Float, stepY: Float, dim: Float) {
        GLES20.glUseProgram(blurProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(blurSampler, 0)
        GLES20.glUniform2f(blurStep, stepX, stepY)
        GLES20.glUniform1f(blurDim, dim)
        posBuf.position(0)
        quadTexBuf.position(0)
        GLES20.glVertexAttribPointer(blurPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(blurPos)
        GLES20.glVertexAttribPointer(blurTex, 2, GLES20.GL_FLOAT, false, 0, quadTexBuf)
        GLES20.glEnableVertexAttribArray(blurTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(blurPos)
        GLES20.glDisableVertexAttribArray(blurTex)
    }

    /** Upright aspect (width / height) of a stream for the given display rotation. */
    private fun uprightAspect(stream: Stream, rotationDeg: Int): Float {
        val m = stream.matrix
        val info = stream.info
        val camTransform = info?.hasCameraTransform() ?: true
        val r = if (camTransform) (360 - rotationDeg) % 360 else ((info?.rotationDegrees ?: 0) % 360)
        val stRotated = abs(m[0]) < abs(m[1])
        var sw = if (stRotated) stream.bufH else stream.bufW
        var sh = if (stRotated) stream.bufW else stream.bufH
        if (r == 90 || r == 270) { val t = sw; sw = sh; sh = t }
        return sw.toFloat() / sh
    }

    /** Draws a stream into a layout slot, cropped to fill or (in fit mode) whole. */
    private fun drawSlot(
        stream: Stream?, slot: Int, x: Int, y: Int, w: Int, h: Int, rotationDeg: Int, useFit: Boolean,
    ) {
        if (stream == null || w <= 0 || h <= 0) return
        if (!useFit) {
            drawStream(stream, slot, x, y, w, h, rotationDeg)
            return
        }
        val src = uprightAspect(stream, rotationDeg)
        val dst = w.toFloat() / h
        if (src > dst) {
            val fh = (w / src).roundToInt()
            drawStream(stream, slot, x, y + (h - fh) / 2, w, fh, rotationDeg)
        } else {
            val fw = (h * src).roundToInt()
            drawStream(stream, slot, x + (w - fw) / 2, y, fw, h, rotationDeg)
        }
    }

    /**
     * Draws [stream] centre-cropped into the viewport rect, rotated upright for a display
     * rotated by [rotationDeg]. [aspectOverride] crops for a different aspect than the
     * viewport (used when rendering into the small blur buffer). With [cutout], only the
     * person (per the segmentation mask) is drawn.
     */
    private fun drawStream(
        stream: Stream, slot: Int, x: Int, y: Int, w: Int, h: Int, rotationDeg: Int,
        aspectOverride: Float = 0f,
        cutout: Boolean = false,
    ) {
        if (!stream.hasFrame || w <= 0 || h <= 0) return
        val m = stream.matrix
        val info = stream.info
        val camTransform = info?.hasCameraTransform() ?: true

        // When the camera writes its orientation into the surface (the usual case), the
        // SurfaceTexture matrix already makes the image upright for the phone's natural
        // orientation, and front cameras come out mirrored. We only undo the display rotation.
        val r = if (camTransform) (360 - rotationDeg) % 360 else ((info?.rotationDegrees ?: 0) % 360)
        val src = uprightAspect(stream, rotationDeg)
        val dst = if (aspectOverride > 0f) aspectOverride else w.toFloat() / h
        var u0 = 0f
        var u1 = 1f
        var v0 = 0f
        var v1 = 1f
        if (src > dst) {
            val k = dst / src
            u0 = (1f - k) / 2f; u1 = 1f - u0
        } else {
            val k = src / dst
            v0 = (1f - k) / 2f; v1 = 1f - v0
        }
        val front = isFront[slot]
        val flip = front && (if (camTransform) !mirrorFront else mirrorFront)

        for (i in 0 until 4) {
            val cu = (i % 2).toFloat()
            val cv = (i / 2).toFloat()
            val du = u0 + cu * (u1 - u0)  // displayed (upright) coords
            val dv = v0 + cv * (v1 - v0)
            val u = if (flip) 1f - du else du
            // Map upright coords back to the (matrix-space) source, undoing a clockwise rotation r.
            val sx: Float
            val sy: Float
            when (r) {
                90 -> { sx = 1f - dv; sy = u }
                180 -> { sx = 1f - u; sy = 1f - dv }
                270 -> { sx = dv; sy = 1f - u }
                else -> { sx = u; sy = dv }
            }
            texCoords[i * 2] = m[0] * sx + m[4] * sy + m[12]
            texCoords[i * 2 + 1] = m[1] * sx + m[5] * sy + m[13]
            // The mask is upright and un-mirrored, with its first row at the top.
            val displayedMirrored = front && mirrorFront
            maskCoords[i * 2] = if (displayedMirrored) 1f - du else du
            maskCoords[i * 2 + 1] = 1f - dv
        }
        texBuf.position(0)
        texBuf.put(texCoords)
        texBuf.position(0)

        GLES20.glViewport(x, y, w, h)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stream.texId)
        posBuf.position(0)

        if (cutout) {
            maskBuf.position(0)
            maskBuf.put(maskCoords)
            maskBuf.position(0)
            GLES20.glUseProgram(cutProg)
            GLES20.glUniform1i(cutSampler, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
            GLES20.glUniform1i(cutMaskSampler, 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glVertexAttribPointer(cutPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
            GLES20.glEnableVertexAttribArray(cutPos)
            GLES20.glVertexAttribPointer(cutTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
            GLES20.glEnableVertexAttribArray(cutTex)
            GLES20.glVertexAttribPointer(cutMaskCoord, 2, GLES20.GL_FLOAT, false, 0, maskBuf)
            GLES20.glEnableVertexAttribArray(cutMaskCoord)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisableVertexAttribArray(cutPos)
            GLES20.glDisableVertexAttribArray(cutTex)
            GLES20.glDisableVertexAttribArray(cutMaskCoord)
        } else {
            GLES20.glUseProgram(oesProg)
            GLES20.glUniform1i(oesSampler, 0)
            GLES20.glVertexAttribPointer(oesPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
            GLES20.glEnableVertexAttribArray(oesPos)
            GLES20.glVertexAttribPointer(oesTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
            GLES20.glEnableVertexAttribArray(oesTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(oesPos)
            GLES20.glDisableVertexAttribArray(oesTex)
        }
    }

    private fun renderToBitmap(w: Int, h: Int): Bitmap? {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return null
        val pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0
        )
        if (pbuffer == EGL14.EGL_NO_SURFACE) return null
        try {
            makeCurrent(pbuffer)
            drawScene(w, h, displayRotationDeg)
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            buf.rewind()
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buf)
            // GL rows start at the bottom; flip to normal image orientation.
            val flipped = Bitmap.createBitmap(raw, 0, 0, w, h, Matrix().apply { preScale(1f, -1f) }, false)
            raw.recycle()
            return flipped
        } finally {
            destroySurface(pbuffer)
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private const val TAG = "DualRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val BLUR_W = 120
        private const val BLUR_H = 120

        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = aTex;
            }
        """

        private const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """

        private const val CUTOUT_VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            attribute vec2 aMask;
            varying vec2 vTex;
            varying vec2 vMask;
            void main() {
                gl_Position = aPos;
                vTex = aTex;
                vMask = aMask;
            }
        """

        // Soft edge: fully transparent below ~0.35 confidence, opaque above ~0.75.
        private const val CUTOUT_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            varying vec2 vMask;
            uniform samplerExternalOES sTex;
            uniform sampler2D sMask;
            void main() {
                float a = smoothstep(0.35, 0.75, texture2D(sMask, vMask).r);
                gl_FragColor = vec4(texture2D(sTex, vTex).rgb, a);
            }
        """

        private const val TEX_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """

        // 9-tap Gaussian (linear-sampling weights); uStep = 0 gives a plain copy.
        private const val BLUR_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
            uniform vec2 uStep;
            uniform float uDim;
            void main() {
                vec3 c = texture2D(sTex, vTex).rgb * 0.2270270270;
                c += texture2D(sTex, vTex + uStep * 1.3846153846).rgb * 0.3162162162;
                c += texture2D(sTex, vTex - uStep * 1.3846153846).rgb * 0.3162162162;
                c += texture2D(sTex, vTex + uStep * 3.2307692308).rgb * 0.0702702703;
                c += texture2D(sTex, vTex - uStep * 3.2307692308).rgb * 0.0702702703;
                gl_FragColor = vec4(c * uDim, 1.0);
            }
        """

        private fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(data); position(0) }
    }
}
