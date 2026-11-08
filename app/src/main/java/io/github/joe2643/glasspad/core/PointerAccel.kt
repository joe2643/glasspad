package io.github.joe2643.glasspad.core

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * Speed-dependent pointer acceleration — libinput's touchpad "adaptive" profile
 * (`filter-touchpad.c`). Slow finger motion is damped for precise positioning, a normal pace is
 * linear, and fast swipes are amplified so one stroke can cross a large monitor.
 *
 * Gain by finger speed v (mm/s): v < 7 → min(0.9, 0.1v + 0.3); 7..130 → 0.9;
 * above → 0.9 + 0.0025·(v/130)·(v − 130), with v capped at 520 (max ≈ 4.8). Output is
 * mm × 11.685 × gain × speedFactor, where speedFactor = (s + 1)^2.38 · 0.95 + 0.05 for s in [-1, 1].
 *
 * Pure — positions are in touch-surface pixels and [pxPerMm] converts them to millimetres.
 */
class PointerAccel(private val pxPerMm: Float, speed: Float = 0f) {

    private class Sample(val t: Long, val x: Float, val y: Float)

    private val speedFactor = (speed.coerceIn(-1f, 1f) + 1f).pow(2.38f) * 0.95f + 0.05f
    private val samples = ArrayDeque<Sample>()
    private var lastGain = NO_GAIN

    /** Starts a new motion at (x, y): forgets the previous position and speed. */
    fun reset(x: Float, y: Float, t: Long) {
        samples.clear()
        samples.addLast(Sample(t, x / pxPerMm, y / pxPerMm))
        lastGain = NO_GAIN
    }

    /** Feeds the finger's new position; returns the cursor delta (dx, dy) in pixels. */
    fun accelerate(x: Float, y: Float, t: Long): Pair<Float, Float> {
        val prev = samples.lastOrNull()
        if (prev == null) {
            reset(x, y, t)
            return 0f to 0f
        }
        val s = Sample(t, x / pxPerMm, y / pxPerMm)
        val dx = s.x - prev.x
        val dy = s.y - prev.y
        samples.addLast(s)
        while (samples.size > 2 && (t - samples.first().t > WINDOW_MS || samples.size > MAX_SAMPLES)) {
            samples.removeFirst()
        }

        val gain = gainFor(velocity(dx, dy))
        // Average with the previous gain (libinput integrates between the two speeds).
        val g = if (lastGain == NO_GAIN) gain else (gain + lastGain) / 2f
        lastGain = gain
        val k = UNITS_PER_MM * g * speedFactor
        return dx * k to dy * k
    }

    /**
     * Speed in mm/s over the recent samples that still head the same way as the newest step:
     * walks back until the window ends or the direction reverses.
     */
    private fun velocity(dx: Float, dy: Float): Float {
        val newest = samples.last()
        var oldest = samples[samples.size - 2] // the step itself always counts
        for (i in samples.size - 3 downTo 0) {
            val a = samples[i]
            val b = samples[i + 1]
            if (newest.t - a.t > WINDOW_MS) break
            if ((b.x - a.x) * dx + (b.y - a.y) * dy < 0f) break
            oldest = a
        }
        val dt = (newest.t - oldest.t).coerceAtLeast(1L)
        return hypot(newest.x - oldest.x, newest.y - oldest.y) / dt * 1000f
    }

    private fun gainFor(v: Float): Float = when {
        v < 7f -> min(0.9f, 0.1f * v + 0.3f)
        v < 130f -> 0.9f
        else -> {
            val vc = min(v, 520f)
            0.9f + 0.0025f * (vc / 130f) * (vc - 130f)
        }
    }

    private companion object {
        const val UNITS_PER_MM = 39.37f * 0.2968f // ≈ 11.685, libinput's normalisation
        const val WINDOW_MS = 100L
        const val MAX_SAMPLES = 32
        const val NO_GAIN = -1f
    }
}
