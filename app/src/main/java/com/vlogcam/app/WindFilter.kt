package com.vlogcam.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reduces wind rumble in a finished recording: a steep high-pass filter on the audio track
 * (wind energy is mostly low-frequency) while the video track is copied unchanged.
 * Jobs run one at a time in the background; the original file is replaced in place.
 */
@OptIn(UnstableApi::class)
class WindFilter(private val context: Context, private val onDone: (Uri, Boolean) -> Unit) {

    private data class Job(val uri: Uri, val level: Int)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val queue = ArrayDeque<Job>()
    private var running = false

    val busy: Boolean get() = running || queue.isNotEmpty()

    /** [level]: 1 = light (removes rumble below ~100 Hz), 2 = strong (below ~200 Hz, steeper). */
    fun enqueue(uri: Uri, level: Int) {
        if (level <= 0) return
        queue.add(Job(uri, level))
        if (!running) next()
    }

    private fun next() {
        val job = queue.poll() ?: run { running = false; return }
        running = true
        val dir = File(context.cacheDir, "wind").apply { mkdirs() }
        val out = File(dir, "wind_${System.currentTimeMillis()}.mp4")
        val processor = if (job.level >= 2) HighPassProcessor(200f, 2) else HighPassProcessor(100f, 1)
        val item = EditedMediaItem.Builder(MediaItem.fromUri(job.uri))
            .setEffects(Effects(listOf<AudioProcessor>(processor), listOf()))
            .build()
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    io.execute {
                        val ok = runCatching {
                            context.contentResolver.openOutputStream(job.uri, "wt")?.use { o ->
                                out.inputStream().use { it.copyTo(o) }
                            } ?: error("Cannot write original")
                        }.onFailure { Log.e(TAG, "Replace failed", it) }.isSuccess
                        out.delete()
                        main.post {
                            onDone(job.uri, ok)
                            next()
                        }
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    Log.e(TAG, "Wind filter failed", exportException)
                    out.delete()
                    onDone(job.uri, false)
                    next()
                }
            })
            .build()
        try {
            transformer.start(item, out.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Wind filter start failed", e)
            onDone(job.uri, false)
            next()
        }
    }

    /** Cascaded RBJ biquad high-pass filters on 16-bit PCM. */
    private class HighPassProcessor(private val cutoffHz: Float, private val stages: Int) :
        BaseAudioProcessor() {

        private var channels = 1
        private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        // State per [stage][channel]: x1, x2, y1, y2
        private var state = Array(0) { FloatArray(0) }

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
            channels = inputAudioFormat.channelCount
            val w0 = 2.0 * Math.PI * cutoffHz / inputAudioFormat.sampleRate
            val alpha = sin(w0) / (2.0 * 0.7071)
            val c = cos(w0)
            val a0 = 1.0 + alpha
            b0 = ((1.0 + c) / 2.0 / a0).toFloat()
            b1 = (-(1.0 + c) / a0).toFloat()
            b2 = b0
            a1 = (-2.0 * c / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
            state = Array(stages * channels) { FloatArray(4) }
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            val size = input.remaining()
            val out = replaceOutputBuffer(size).order(ByteOrder.LITTLE_ENDIAN)
            var ch = 0
            while (input.remaining() >= 2) {
                var x = input.short.toFloat()
                for (s in 0 until stages) {
                    val st = state[s * channels + ch]
                    val y = b0 * x + b1 * st[0] + b2 * st[1] - a1 * st[2] - a2 * st[3]
                    st[1] = st[0]; st[0] = x
                    st[3] = st[2]; st[2] = y
                    x = y
                }
                out.putShort(x.coerceIn(-32768f, 32767f).toInt().toShort())
                ch = (ch + 1) % channels
            }
            out.flip()
        }

        override fun onReset() {
            state = Array(0) { FloatArray(0) }
        }

        override fun onFlush() {
            for (st in state) st.fill(0f)
        }
    }

    companion object {
        private const val TAG = "WindFilter"
    }
}
