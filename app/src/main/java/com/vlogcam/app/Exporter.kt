package com.vlogcam.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A target size for a social platform. */
data class ExportPreset(val platform: String, val name: String, val width: Int, val height: Int) {
    val label: String get() = "$platform · $name  (${ratio()}, ${width}×${height})"

    private fun ratio(): String {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val g = gcd(width, height)
        return "${width / g}:${height / g}"
    }

    companion object {
        val ALL = listOf(
            ExportPreset("Instagram", "Reel / Story", 1080, 1920),
            ExportPreset("Instagram", "Post, portrait", 1080, 1350),
            ExportPreset("Instagram", "Post, square", 1080, 1080),
            ExportPreset("YouTube", "Video", 1920, 1080),
            ExportPreset("YouTube", "Shorts", 1080, 1920),
            ExportPreset("Facebook", "Reel / Story", 1080, 1920),
            ExportPreset("Facebook", "Feed, portrait", 1080, 1350),
            ExportPreset("Facebook", "Feed, landscape", 1280, 720),
        )
    }
}

/**
 * Converts a captured photo or video to a platform size, either cropping to fill the frame
 * or fitting the whole picture with black bars. Results go to Pictures|Movies/VlogCam/Exports.
 */
@OptIn(UnstableApi::class)
class Exporter(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var transformer: Transformer? = null
    private var progressPoller: Runnable? = null
    @Volatile private var cancelled = false

    fun isVideo(uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.startsWith("video") == true

    /** Callbacks run on the main thread. [onProgress] gets 0-100 or -1 when unknown. */
    fun export(
        source: Uri,
        preset: ExportPreset,
        crop: Boolean,
        onProgress: (Int) -> Unit,
        onDone: (Uri?, String?) -> Unit,
    ) {
        cancelled = false
        if (isVideo(source)) exportVideo(source, preset, crop, onProgress, onDone)
        else exportPhoto(source, preset, crop, onDone)
    }

    fun cancel() {
        cancelled = true
        transformer?.cancel()
        transformer = null
        progressPoller?.let { main.removeCallbacks(it) }
    }

    private fun fileName(preset: ExportPreset) = fileName("${preset.platform}_${preset.name}")

    private fun fileName(tag: String) =
        MediaSaver.fileName(tag.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_'))

    // ------------------------------------------------------------------ trim & join

    /** Saves the part of [source] between [startMs] and [endMs] as a new video. */
    fun trim(
        source: Uri, startMs: Long, endMs: Long,
        onProgress: (Int) -> Unit, onDone: (Uri?, String?) -> Unit,
    ) {
        cancelled = false
        val item = EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(source)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()
        ).build()
        // Fast path re-encodes only the first moments and copies the rest; fall back to a full re-encode.
        runVideoJob(
            "Trim",
            configure = { it.experimentalSetTrimOptimizationEnabled(true) },
            start = { t, out -> t.start(item, out) },
            retry = {
                runVideoJob("Trim", { it }, { t, out -> t.start(item, out) }, null, onProgress, onDone)
            },
            onProgress = onProgress, onDone = onDone,
        )
    }

    /** Joins [sources] (in order) into one video. */
    fun join(sources: List<Uri>, onProgress: (Int) -> Unit, onDone: (Uri?, String?) -> Unit) {
        cancelled = false
        val items = sources.map { EditedMediaItem.Builder(MediaItem.fromUri(it)).build() }
        val sequence = EditedMediaItemSequence.Builder(items).build()
        // Our own ride parts share one format, so try copying without re-encoding first.
        val fast = Composition.Builder(sequence).setTransmuxAudio(true).setTransmuxVideo(true).build()
        val full = Composition.Builder(sequence).build()
        runVideoJob(
            "Joined",
            configure = { it },
            start = { t, out -> t.start(fast, out) },
            retry = {
                runVideoJob("Joined", { it }, { t, out -> t.start(full, out) }, null, onProgress, onDone)
            },
            onProgress = onProgress, onDone = onDone,
        )
    }

    private fun runVideoJob(
        tag: String,
        configure: (Transformer.Builder) -> Transformer.Builder,
        start: (Transformer, String) -> Unit,
        retry: (() -> Unit)?,
        onProgress: (Int) -> Unit,
        onDone: (Uri?, String?) -> Unit,
    ) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val out = File(dir, "job_${System.currentTimeMillis()}.mp4")
        val builder = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopPolling()
                    transformer = null
                    io.execute {
                        val uri = copyToMediaStore(out, fileName(tag))
                        out.delete()
                        main.post {
                            if (uri != null) onDone(uri, null) else onDone(null, "Could not save the video")
                        }
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    stopPolling()
                    transformer = null
                    out.delete()
                    if (cancelled) return
                    if (retry != null) retry()
                    else onDone(null, exportException.message ?: "Failed")
                }
            })
        val t = configure(builder).build()
        transformer = t
        try {
            start(t, out.absolutePath)
        } catch (e: Exception) {
            transformer = null
            if (retry != null) retry() else onDone(null, e.message ?: "Failed")
            return
        }
        startPolling(onProgress)
    }

    private fun startPolling(onProgress: (Int) -> Unit) {
        stopPolling()
        val holder = ProgressHolder()
        val poller = object : Runnable {
            override fun run() {
                val current = transformer ?: return
                val state = current.getProgress(holder)
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    onProgress(if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1)
                }
                main.postDelayed(this, 300)
            }
        }
        progressPoller = poller
        main.post(poller)
    }

    // ------------------------------------------------------------------ video

    private fun exportVideo(
        source: Uri,
        preset: ExportPreset,
        crop: Boolean,
        onProgress: (Int) -> Unit,
        onDone: (Uri?, String?) -> Unit,
    ) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val out = File(dir, "export_${System.currentTimeMillis()}.mp4")
        val layout = if (crop) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
        val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(
                Effects(
                    /* audioProcessors= */ listOf(),
                    /* videoEffects= */ listOf(
                        Presentation.createForWidthAndHeight(preset.width, preset.height, layout)
                    )
                )
            )
            .build()

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopPolling()
                    transformer = null
                    io.execute {
                        val uri = copyToMediaStore(out, fileName(preset))
                        out.delete()
                        main.post {
                            if (uri != null) onDone(uri, null) else onDone(null, "Could not save the video")
                        }
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    stopPolling()
                    transformer = null
                    out.delete()
                    if (!cancelled) onDone(null, exportException.message ?: "Export failed")
                }
            })
            .build()
        transformer = t
        t.start(item, out.absolutePath)

        val holder = ProgressHolder()
        val poller = object : Runnable {
            override fun run() {
                val current = transformer ?: return
                val state = current.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_NOT_STARTED) return
                onProgress(if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1)
                main.postDelayed(this, 300)
            }
        }
        progressPoller = poller
        main.post(poller)
    }

    private fun stopPolling() {
        progressPoller?.let { main.removeCallbacks(it) }
        progressPoller = null
    }

    private fun copyToMediaStore(file: File, name: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/${MediaSaver.FOLDER}/Exports")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
                ?: error("No output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    // ------------------------------------------------------------------ photo

    private fun exportPhoto(
        source: Uri,
        preset: ExportPreset,
        crop: Boolean,
        onDone: (Uri?, String?) -> Unit,
    ) {
        io.execute {
            val result = runCatching {
                val src = decode(source)
                val out = Bitmap.createBitmap(preset.width, preset.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                canvas.drawColor(Color.BLACK)
                val sx = preset.width.toFloat() / src.width
                val sy = preset.height.toFloat() / src.height
                val scale = if (crop) max(sx, sy) else min(sx, sy)
                val w = src.width * scale
                val h = src.height * scale
                val left = (preset.width - w) / 2f
                val top = (preset.height - h) / 2f
                canvas.drawBitmap(
                    src, null, RectF(left, top, left + w, top + h),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                )
                src.recycle()
                val uri = saveExportBitmap(out, preset)
                out.recycle()
                uri
            }
            main.post {
                val uri = result.getOrNull()
                if (uri != null) onDone(uri, null)
                else onDone(null, result.exceptionOrNull()?.message ?: "Could not save the photo")
            }
        }
    }

    private fun decode(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Keep memory reasonable for very large photos.
                val longest = max(info.size.width, info.size.height)
                if (longest > 4096) {
                    val f = 4096f / longest
                    decoder.setTargetSize(
                        (info.size.width * f).roundToInt(), (info.size.height * f).roundToInt()
                    )
                }
            }
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: error("Could not read the photo")
    }

    private fun saveExportBitmap(bitmap: Bitmap, preset: ExportPreset): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(preset) + ".jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/${MediaSaver.FOLDER}/Exports")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                ?: error("No output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
