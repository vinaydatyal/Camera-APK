package com.vlogcam.app

enum class DualLayout(val label: String) {
    PIP("Picture-in-picture"),
    SIDE_BY_SIDE("Side by side"),
    STACKED("Top / bottom"),
}

enum class PipCorner { TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT, TOP_LEFT }

object DualComposer {
    /** Inset size as a fraction of the frame's width and height. */
    val PIP_SIZES = floatArrayOf(0.28f, 0.36f, 0.45f)
    val PIP_SIZE_LABELS = arrayOf("Small", "Medium", "Large")
}

/** Shape of the dual-camera frame (applies to preview, recording and snapshots). */
enum class DualFrame(val label: String, val w: Int, val h: Int) {
    AUTO("Auto", 0, 0),   // 9:16 when the screen is portrait, 16:9 in landscape
    TALL("9:16", 9, 16),
    PORTRAIT("4:5", 4, 5),
    SQUARE("1:1", 1, 1),
    WIDE("16:9", 16, 9);

    fun aspect(screenPortrait: Boolean): Float = when {
        this != AUTO -> w.toFloat() / h
        screenPortrait -> 9f / 16f
        else -> 16f / 9f
    }
}
