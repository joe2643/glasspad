package com.example.deskpad.core

/**
 * The phone-side "desktop scale" slider: maps slider positions to a density (dpi) for the external
 * display, and reports the workspace that density leaves apps. Lower dpi → smaller UI, more room.
 *
 * Exists because some displays report a tiny physical size — VITURE glasses claim ~305 dpi, so
 * Android picks 416 dpi and a 1920×1200 panel lays out like a 738×462 dp phone. Pure.
 */
object DesktopScale {
    const val MIN_DPI = 120
    const val MAX_DPI = 480
    const val STEP_DPI = 20

    /** Number of slider positions, MIN_DPI..MAX_DPI inclusive. */
    const val positions = (MAX_DPI - MIN_DPI) / STEP_DPI + 1

    fun dpiAt(position: Int): Int = MIN_DPI + position.coerceIn(0, positions - 1) * STEP_DPI

    /** The slider position nearest to [dpi]. */
    fun positionOf(dpi: Int): Int =
        Math.floorDiv(dpi - MIN_DPI + STEP_DPI / 2, STEP_DPI).coerceIn(0, positions - 1)

    /** Workspace in dp at [dpi], rounded the way Android sizes its configuration. */
    fun workspaceDp(widthPx: Int, heightPx: Int, dpi: Int): Pair<Int, Int> =
        dp(widthPx, dpi) to dp(heightPx, dpi)

    private fun dp(px: Int, dpi: Int): Int = (px * 160 + dpi / 2) / dpi
}
