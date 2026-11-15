package com.vlogcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Rule-of-thirds grid drawn over the preview. */
class GridOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        strokeWidth = resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            val x = w * i / 3f
            val y = h * i / 3f
            canvas.drawLine(x, 0f, x, h, paint)
            canvas.drawLine(0f, y, w, y, paint)
        }
    }
}
