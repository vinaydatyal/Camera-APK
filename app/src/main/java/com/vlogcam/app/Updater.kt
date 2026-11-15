package com.vlogcam.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub "latest-build" release for a newer APK, downloads it and hands it to
 * Android's installer. Network calls must run off the main thread.
 */
object Updater {

    data class Update(val versionCode: Int, val versionName: String, val notes: String)

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }

    /** Returns the available update, or null if this build is current (or the check failed). */
    fun check(): Update? = runCatching {
        val conn = open(BuildConfig.UPDATE_BASE_URL + "version.json?t=" + System.currentTimeMillis())
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val update = Update(
                json.getInt("versionCode"),
                json.optString("versionName"),
                json.optString("notes")
            )
            if (update.versionCode > BuildConfig.VERSION_CODE) update else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Downloads the APK, reporting progress 0-100 (or -1 if size unknown). */
    fun download(context: Context, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "VlogCam.apk")
        val tmp = File(dir, "VlogCam.apk.part")
        val conn = open(BuildConfig.UPDATE_BASE_URL + "VlogCam.apk?t=" + System.currentTimeMillis())
        try {
            if (conn.responseCode != 200) error("Download failed (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -2
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
            }
            if (total > 0 && done != total) error("Download incomplete")
        } finally {
            conn.disconnect()
        }
        target.delete()
        if (!tmp.renameTo(target)) error("Could not save update")
        return target
    }

    /** True if Android lets this app open the installer; otherwise ask via [permissionIntent]. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
        @Suppress("DEPRECATION")
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
    }
}
