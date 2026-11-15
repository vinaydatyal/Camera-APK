package com.vlogcam.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Animated ring shown where the user tapped to focus. */
class FocusRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFFFFD54F.toInt()
    }
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var animator: ValueAnimator? = null

    fun show(x: Float, y: Float) {
        cx = x
        cy = y
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            addUpdateListener {
                val t = it.animatedValue as Float
                radius = (48f - 14f * minOf(t * 3f, 1f)) * density
                paint.alpha = if (t < 0.7f) 255 else ((1f - t) / 0.3f * 255).toInt()
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (animator?.isRunning == true) canvas.drawCircle(cx, cy, radius, paint)
    }
}
