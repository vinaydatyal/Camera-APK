package com.vlogcam.app

import androidx.camera.core.CompositionSettings

enum class DualLayout(val label: String) {
    PIP("Picture-in-picture"),
    SIDE_BY_SIDE("Side by side"),
    STACKED("Top / bottom"),
}

enum class PipCorner { TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT, TOP_LEFT }

/**
 * Builds CameraX composition settings for the two concurrent cameras.
 *
 * CameraX uses normalized device coordinates: (0,0) is the frame centre, x/y range -1..1,
 * +y is up, and a scale of 1 fills the whole frame. Both cameras are rendered into one
 * stream, so the preview shows exactly what gets recorded.
 */
object DualComposer {

    val PIP_SIZES = floatArrayOf(0.25f, 0.33f, 0.42f)
    val PIP_SIZE_LABELS = arrayOf("Small", "Medium", "Large")
    private const val MARGIN = 0.05f

    /** Returns (primary, secondary). Primary is drawn first (underneath). */
    fun settings(
        layout: DualLayout,
        corner: PipCorner,
        pipSize: Float,
    ): Pair<CompositionSettings, CompositionSettings> = when (layout) {
        DualLayout.PIP -> {
            val c = 1f - pipSize - MARGIN
            val x = if (corner == PipCorner.TOP_RIGHT || corner == PipCorner.BOTTOM_RIGHT) c else -c
            val y = if (corner == PipCorner.TOP_RIGHT || corner == PipCorner.TOP_LEFT) c else -c
            build(0f, 0f, 1f) to build(x, y, pipSize)
        }
        DualLayout.SIDE_BY_SIDE -> build(-0.5f, 0f, 0.5f) to build(0.5f, 0f, 0.5f)
        DualLayout.STACKED -> build(0f, 0.5f, 0.5f) to build(0f, -0.5f, 0.5f)
    }

    private fun build(x: Float, y: Float, scale: Float): CompositionSettings =
        CompositionSettings.Builder()
            .setAlpha(1f)
            .setOffset(x, y)
            .setScale(scale, scale)
            .build()
}
