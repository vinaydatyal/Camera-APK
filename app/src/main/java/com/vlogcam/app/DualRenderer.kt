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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the two concurrent camera streams into one frame with OpenGL.
 *
 * Each camera is centre-cropped to fill its region, so split layouts use the whole frame.
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

    private val thread = HandlerThread("DualRenderer").apply { start() }
    private val handler = Handler(thread.looper)
    private val glExecutor = Executor { handler.post(it) }

    // Settings: written on the main thread, read on the GL thread.
    @Volatile var layout = DualLayout.PIP
    @Volatile var corner = PipCorner.TOP_RIGHT
    @Volatile var pipSize = DualComposer.PIP_SIZES[1]
    @Volatile var swapped = false
    @Volatile var mirrorFront = true
    @Volatile var displayRotationDeg = 0
    /** Fit: show each camera's whole picture over a blurred copy instead of cropping. */
    @Volatile var fit = false
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

    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTex = 0
    private var blurProgram = 0
    private var bPos = 0
    private var bTex = 0
    private var bSampler = 0
    private var bStep = 0
    private val posBuf = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texBuf = floatBuffer(FloatArray(8))
    private val texCoords = FloatArray(8)

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

    /** Starts feeding frames to [surface] (the encoder input). [onReady] runs on the GL thread. */
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

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTex = GLES20.glGetUniformLocation(program, "sTex")
        blurProgram = buildProgram(VERTEX_SHADER, BLUR_FRAGMENT_SHADER)
        bPos = GLES20.glGetAttribLocation(blurProgram, "aPos")
        bTex = GLES20.glGetAttribLocation(blurProgram, "aTex")
        bSampler = GLES20.glGetUniformLocation(blurProgram, "sTex")
        bStep = GLES20.glGetUniformLocation(blurProgram, "uStep")
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
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

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

    // ------------------------------------------------------------------ rendering

    private fun onFrame(slot: Int, stream: Stream) {
        if (released || streams[slot] !== stream) return
        try {
            makeCurrent(dummySurface)
            stream.st.updateTexImage()
            stream.st.getTransformMatrix(stream.matrix)
            stream.hasFrame = true

            if (displaySurface != EGL14.EGL_NO_SURFACE && displayW > 0 && displayH > 0) {
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

    private fun mainSlot(): Int {
        val wanted = if (swapped) 1 else 0
        return if (streams[wanted]?.hasFrame == true) wanted else 1 - wanted
    }

    private fun drawScene(w: Int, h: Int, rotationDeg: Int) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val mainIdx = if (swapped) 1 else 0
        val main = streams[mainIdx]
        val second = streams[1 - mainIdx]
        val gap = max(2, (min(w, h) * 0.004f).roundToInt())

        when (layout) {
            DualLayout.PIP -> {
                drawSlot(main, mainIdx, 0, 0, w, h, rotationDeg)
                if (second?.hasFrame == true) {
                    val pw = (w * pipSize).roundToInt()
                    val ph = (h * pipSize).roundToInt()
                    val m = (min(w, h) * 0.04f).roundToInt()
                    val right = corner == PipCorner.TOP_RIGHT || corner == PipCorner.BOTTOM_RIGHT
                    val top = corner == PipCorner.TOP_RIGHT || corner == PipCorner.TOP_LEFT
                    val x = if (right) w - m - pw else m
                    val y = if (top) h - m - ph else m  // GL origin is bottom-left
                    fillRect(x - gap, y - gap, pw + 2 * gap, ph + 2 * gap, 1f)
                    drawStream(second, 1 - mainIdx, x, y, pw, ph, rotationDeg)
                }
            }
            DualLayout.SIDE_BY_SIDE -> {
                val half = w / 2
                drawSlot(main, mainIdx, 0, 0, half, h, rotationDeg)
                drawSlot(second, 1 - mainIdx, half, 0, w - half, h, rotationDeg)
                fillRect(half - gap / 2, 0, gap, h, 0f)
            }
            DualLayout.STACKED -> {
                val half = h / 2
                drawSlot(main, mainIdx, 0, h - half, w, half, rotationDeg)
                drawSlot(second, 1 - mainIdx, 0, 0, w, h - half, rotationDeg)
                fillRect(0, h - half - gap / 2, w, gap, 0f)
            }
        }
    }

    /** Upright aspect (width / height) of a stream for the given display rotation. */
    private fun uprightAspect(stream: Stream, rotationDeg: Int): Float {
        val m = stream.matrix
        val camTransform = stream.info?.hasCameraTransform() ?: true
        val r = if (camTransform) (360 - rotationDeg) % 360 else ((stream.info?.rotationDegrees ?: 0) % 360)
        val stRotated = abs(m[0]) < abs(m[1])
        var w = if (stRotated) stream.bufH else stream.bufW
        var h = if (stRotated) stream.bufW else stream.bufH
        if (r == 90 || r == 270) { val t = w; w = h; h = t }
        return w.toFloat() / h
    }

    /** Draws a stream into a layout slot, cropped to fill or (in fit mode) whole over a blur. */
    private fun drawSlot(stream: Stream?, slot: Int, x: Int, y: Int, w: Int, h: Int, rotationDeg: Int) {
        if (stream == null || !stream.hasFrame || w <= 0 || h <= 0) return
        if (!fit) {
            drawStream(stream, slot, x, y, w, h, rotationDeg)
            return
        }
        drawStream(stream, slot, x, y, w, h, rotationDeg, blur = true)
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

    private fun fillRect(x: Int, y: Int, w: Int, h: Int, gray: Float) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(x, y, w, h)
        GLES20.glClearColor(gray, gray, gray, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    /**
     * Draws [stream] centre-cropped into the viewport rect, rotated upright for a display
     * rotated by [rotationDeg].
     */
    private fun drawStream(
        stream: Stream?, slot: Int, x: Int, y: Int, w: Int, h: Int, rotationDeg: Int,
        blur: Boolean = false,
    ) {
        if (stream == null || !stream.hasFrame || w <= 0 || h <= 0) return
        val m = stream.matrix
        val info = stream.info
        val camTransform = info?.hasCameraTransform() ?: true

        // When the camera writes its orientation into the surface (the usual case), the
        // SurfaceTexture matrix already makes the image upright for the phone's natural
        // orientation, and front cameras come out mirrored. We only undo the display rotation.
        val r = if (camTransform) (360 - rotationDeg) % 360 else ((info?.rotationDegrees ?: 0) % 360)
        val stRotated = abs(m[0]) < abs(m[1])
        val w1 = if (stRotated) stream.bufH else stream.bufW
        val h1 = if (stRotated) stream.bufW else stream.bufH
        val (w2, h2) = if (r == 90 || r == 270) h1 to w1 else w1 to h1

        val src = w2.toFloat() / h2
        val dst = w.toFloat() / h
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
        val mirror = isFront[slot] && (if (camTransform) !mirrorFront else mirrorFront)

        for (i in 0 until 4) {
            val cu = (i % 2).toFloat()
            val cv = (i / 2).toFloat()
            var u = u0 + cu * (u1 - u0)
            val v = v0 + cv * (v1 - v0)
            if (mirror) u = 1f - u
            // Map upright coords back to the (matrix-space) source, undoing a clockwise rotation r.
            val sx: Float
            val sy: Float
            when (r) {
                90 -> { sx = 1f - v; sy = u }
                180 -> { sx = 1f - u; sy = 1f - v }
                270 -> { sx = v; sy = 1f - u }
                else -> { sx = u; sy = v }
            }
            texCoords[i * 2] = m[0] * sx + m[4] * sy + m[12]
            texCoords[i * 2 + 1] = m[1] * sx + m[5] * sy + m[13]
        }
        texBuf.position(0)
        texBuf.put(texCoords)
        texBuf.position(0)

        val prog = if (blur) blurProgram else program
        val posLoc = if (blur) bPos else aPos
        val texLoc = if (blur) bTex else aTex
        GLES20.glViewport(x, y, w, h)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stream.texId)
        if (blur) {
            GLES20.glUniform1i(bSampler, 0)
            // Blur radius proportional to the slot, in texture units.
            GLES20.glUniform2f(bStep, 0.012f, 0.012f)
        } else {
            GLES20.glUniform1i(uTex, 0)
        }
        posBuf.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
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

        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = aTex;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """

        // Cheap single-pass blur (7x7 taps, wide spacing) and darkening for fit-mode backgrounds.
        private const val BLUR_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            uniform vec2 uStep;
            void main() {
                vec3 c = vec3(0.0);
                for (int i = -3; i <= 3; i++) {
                    for (int j = -3; j <= 3; j++) {
                        c += texture2D(sTex, vTex + vec2(float(i), float(j)) * uStep).rgb;
                    }
                }
                gl_FragColor = vec4(c / 49.0 * 0.55, 1.0);
            }
        """

        private fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(data); position(0) }
    }
}
