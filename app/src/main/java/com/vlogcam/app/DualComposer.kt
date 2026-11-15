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
