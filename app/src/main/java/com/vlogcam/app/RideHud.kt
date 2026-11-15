package com.vlogcam.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * GPS speed / trip distance / time / place, drawn as a translucent panel ("HUD") bitmap
 * once a second. The bitmap is burned into dual-mode video by the renderer.
 */
class RideHud(private val context: Context, private val onFrame: (Bitmap) -> Unit) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var last: Location? = null
    private var lastFixMs = 0L
    private var speedKmh = 0f
    private var distanceM = 0.0
    private var counting = false
    @Volatile private var place: String? = null
    private var lastGeo: Location? = null
    private var lastGeoMs = 0L
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            onFrame(render())
            main.postDelayed(this, 1000)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        runCatching {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 0f, listener, Looper.getMainLooper())
            }
        }
        main.post(ticker)
    }

    fun stop() {
        running = false
        runCatching { lm.removeUpdates(listener) }
        main.removeCallbacks(ticker)
    }

    /** Starts (true) or stops counting trip distance. Starting resets it to zero. */
    fun setCounting(on: Boolean) {
        if (on) distanceM = 0.0
        counting = on
    }

    private fun onLocation(loc: Location) {
        val now = System.currentTimeMillis()
        val gps = loc.provider == LocationManager.GPS_PROVIDER
        val prev = last
        if (gps) {
            speedKmh = when {
                loc.hasSpeed() -> loc.speed * 3.6f
                prev != null && loc.time > prev.time ->
                    prev.distanceTo(loc) / ((loc.time - prev.time) / 1000f) * 3.6f
                else -> speedKmh
            }
            if (speedKmh < 2f) speedKmh = 0f  // ignore GPS jitter when stopped
            if (counting && prev != null && loc.accuracy < 30f) {
                val d = prev.distanceTo(loc)
                if (d > 3f && speedKmh > 2f) distanceM += d
            }
            last = loc
            lastFixMs = now
        } else if (last == null) {
            last = loc
        }
        maybeGeocode(loc, now)
    }

    private fun maybeGeocode(loc: Location, now: Long) {
        if (!Geocoder.isPresent()) return
        val prev = lastGeo
        if (prev != null && now - lastGeoMs < 60_000 && prev.distanceTo(loc) < 1000f) return
        lastGeo = loc
        lastGeoMs = now
        val geocoder = Geocoder(context, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(loc.latitude, loc.longitude, 1) { list ->
                list.firstOrNull()?.let { place = describe(it) }
            }
        } else {
            io.execute {
                runCatching {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                }.getOrNull()?.let { place = describe(it) }
            }
        }
    }

    private fun describe(a: android.location.Address): String? =
        listOfNotNull(a.subLocality ?: a.locality ?: a.subAdminArea, a.adminArea)
            .distinct().joinToString(", ").ifBlank { null }

    // ------------------------------------------------------------------ drawing

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(115, 0, 0, 0) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 118f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }
    private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 213, 79); textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 40f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(3f, 0f, 2f, Color.BLACK)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255); textSize = 34f
        setShadowLayer(3f, 0f, 2f, Color.BLACK)
    }
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun render(): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRoundRect(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), 40f, 40f, panel)

        val fresh = System.currentTimeMillis() - lastFixMs < 5_000
        val speedText = if (fresh) speedKmh.toInt().toString() else "--"
        val speedW = big.measureText(speedText)
        val colW = maxOf(speedW, 150f)
        val cx = 32f + colW / 2f
        c.drawText(speedText, cx - speedW / 2f, 132f, big)
        c.drawText("km/h", cx, 182f, unit)

        val x = 32f + colW + 36f
        val maxW = WIDTH - x - 28f
        val dist = String.format(Locale.US, "%.1f km", distanceM / 1000.0)
        c.drawText(ellipsize("$dist  ·  ${clock.format(Date())}", line, maxW), x, 92f, line)
        val where = place ?: if (fresh) "" else "Waiting for GPS…"
        if (where.isNotEmpty()) c.drawText(ellipsize("📍 $where", small, maxW), x, 152f, small)
        return bmp
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && paint.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    companion object {
        const val WIDTH = 760
        const val HEIGHT = 220
    }
}
