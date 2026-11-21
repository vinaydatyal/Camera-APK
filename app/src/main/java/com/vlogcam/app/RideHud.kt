package com.vlogcam.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * GPS speed / trip distance / heading / altitude / time / place, drawn twice a second as
 * a translucent dashboard (round speed gauge + info column). The bitmap is burned into
 * dual-mode video by the renderer.
 */
class RideHud(private val context: Context, private val onFrame: (Bitmap) -> Unit) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var last: Location? = null
    private var lastFixMs = 0L
    private var targetKmh = 0f
    private var shownKmh = 0f
    private var bearing = -1f
    private var altitude: Double? = null
    private var distanceM = 0.0
    private var counting = false
    private var gaugeMax = 120f
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
            // Ease the needle toward the latest reading so it moves smoothly, not in jumps.
            shownKmh += (targetKmh - shownKmh) * 0.6f
            if (kotlin.math.abs(targetKmh - shownKmh) < 0.5f) shownKmh = targetKmh
            onFrame(render())
            main.postDelayed(this, 500)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        for (p in providers) {
            runCatching {
                if (lm.isProviderEnabled(p)) {
                    val interval = if (p == LocationManager.NETWORK_PROVIDER) 10_000L else 1_000L
                    lm.requestLocationUpdates(p, interval, 0f, listener, Looper.getMainLooper())
                }
            }
        }
        // Show the place name straight away from the last known position.
        runCatching {
            providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                ?.let { maybeGeocode(it, System.currentTimeMillis()) }
        }
        main.post(ticker)
    }

    fun stop() {
        running = false
        runCatching { lm.removeUpdates(listener) }
        main.removeCallbacks(ticker)
    }

    /** Smoothed speed in km/h, or 0 when there's no recent GPS fix. */
    fun currentKmh(): Float =
        if (System.currentTimeMillis() - lastFixMs < 6_000) shownKmh else 0f

    /** Starts (true) or stops counting trip distance. Starting resets it to zero. */
    fun setCounting(on: Boolean) {
        if (on) distanceM = 0.0
        counting = on
    }

    private fun onLocation(loc: Location) {
        val now = System.currentTimeMillis()
        val precise = loc.provider != LocationManager.NETWORK_PROVIDER && loc.accuracy <= 50f
        if (precise) {
            val prev = last
            // Ignore an older fix arriving late from the other provider.
            if (prev != null && loc.time <= prev.time) return
            targetKmh = when {
                loc.hasSpeed() -> loc.speed * 3.6f
                prev != null -> prev.distanceTo(loc) / ((loc.time - prev.time) / 1000f) * 3.6f
                else -> targetKmh
            }
            if (targetKmh < 2.5f) targetKmh = 0f  // GPS jitter while standing still
            if (targetKmh > gaugeMax * 0.95f) gaugeMax = if (gaugeMax < 180f) 180f else 240f
            if (loc.hasBearing() && targetKmh > 5f) bearing = loc.bearing
            if (loc.hasAltitude()) altitude = loc.altitude
            if (counting && prev != null) {
                val d = prev.distanceTo(loc)
                if (d > 2f && targetKmh > 2.5f && d < 500f) distanceM += d
            }
            last = loc
            lastFixMs = now
        }
        maybeGeocode(loc, now)
    }

    private fun maybeGeocode(loc: Location, now: Long) {
        if (!Geocoder.isPresent()) return
        val prev = lastGeo
        if (prev != null && now - lastGeoMs < 45_000 && prev.distanceTo(loc) < 800f) return
        lastGeo = loc
        lastGeoMs = now
        val geocoder = Geocoder(context, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(loc.latitude, loc.longitude, 1) { list ->
                list.firstOrNull()?.let { describe(it)?.let { p -> place = p } }
            }
        } else {
            io.execute {
                runCatching {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                }.getOrNull()?.let { a -> describe(a)?.let { place = it } }
            }
        }
    }

    private fun describe(a: android.location.Address): String? =
        listOfNotNull(a.subLocality ?: a.locality ?: a.subAdminArea, a.adminArea)
            .distinct().joinToString(", ").ifBlank { null }

    // ------------------------------------------------------------------ drawing

    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val regular = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 10, 12, 16) }
    private val panelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(50, 255, 255, 255)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 18f; strokeCap = Paint.Cap.ROUND
        color = Color.argb(60, 255, 255, 255)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 18f; strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f; color = Color.argb(150, 255, 255, 255)
    }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 92f; typeface = bold; textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 213, 79); textSize = 26f; typeface = bold
        textAlign = Paint.Align.CENTER; letterSpacing = 0.1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 213, 79); textSize = 22f; typeface = bold; letterSpacing = 0.15f
    }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 50f; typeface = bold; setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255); textSize = 30f; typeface = regular
        setShadowLayer(3f, 0f, 2f, Color.BLACK)
    }
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun compass(deg: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[(((deg % 360f) + 360f) % 360f / 45f).roundToInt() % 8]
    }

    fun render(): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = RectF(2f, 2f, WIDTH - 2f, HEIGHT - 2f)
        c.drawRoundRect(r, 44f, 44f, panel)
        c.drawRoundRect(r, 44f, 44f, panelEdge)

        // --- round gauge (240° sweep, bottom open)
        val fresh = System.currentTimeMillis() - lastFixMs < 6_000
        val cx = 135f
        val cy = 136f
        val rad = 100f
        val oval = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
        val start = 150f
        val sweep = 240f
        c.drawArc(oval, start, sweep, false, trackPaint)
        // ticks every 20 km/h
        var v = 0f
        while (v <= gaugeMax + 0.1f) {
            val a = Math.toRadians((start + sweep * v / gaugeMax).toDouble())
            val inner = rad - 24f
            val outer = rad - 14f
            c.drawLine(
                cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat(),
                cx + (outer * cos(a)).toFloat(), cy + (outer * sin(a)).toFloat(), tickPaint
            )
            v += 20f
        }
        if (fresh && shownKmh > 0.5f) {
            val frac = (shownKmh / gaugeMax).coerceIn(0f, 1f)
            // green -> yellow -> red along the dial
            val shader = SweepGradient(
                cx, cy,
                intArrayOf(0xFF4CAF50.toInt(), 0xFFFFD54F.toInt(), 0xFFF44336.toInt(), 0xFF4CAF50.toInt()),
                floatArrayOf(0f, 0.33f, 0.66f, 1f)
            )
            shader.setLocalMatrix(android.graphics.Matrix().apply { postRotate(start, cx, cy) })
            arcPaint.shader = shader
            c.drawArc(oval, start, sweep * frac, false, arcPaint)
        }
        val speedText = if (fresh) shownKmh.roundToInt().toString() else "--"
        c.drawText(speedText, cx, cy + 30f, speedPaint)
        c.drawText("KM/H", cx, cy + 72f, unitPaint)

        // --- info column
        val x = 268f
        val maxW = WIDTH - x - 30f
        c.drawText("TRIP", x, 62f, labelPaint)
        c.drawText(String.format(Locale.US, "%.1f km", distanceM / 1000.0), x, 112f, bigPaint)
        val details = buildList {
            if (bearing >= 0f) add("🧭 " + compass(bearing))
            altitude?.let { add("⛰ ${it.roundToInt()} m") }
            add("🕑 " + clock.format(Date()))
        }.joinToString("   ")
        c.drawText(ellipsize(details, infoPaint, maxW), x, 162f, infoPaint)
        val where = place ?: if (fresh) "" else "Searching for GPS…"
        if (where.isNotEmpty()) c.drawText(ellipsize("📍 $where", infoPaint, maxW), x, 208f, infoPaint)
        return bmp
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && paint.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    companion object {
        const val WIDTH = 780
        const val HEIGHT = 260
    }
}
