package com.example.deskpad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** libinput's touchpad "adaptive" acceleration curve, at 10 px per mm. */
class PointerAccelTest {

    /** Moves right in steps of [stepPx] every 10 ms; returns the cursor px of the last step. */
    private fun steady(accel: PointerAccel, stepPx: Float, steps: Int = 8): Float {
        accel.reset(0f, 0f, 0)
        var dx = 0f
        for (i in 1..steps) dx = accel.accelerate(stepPx * i, 0f, 10L * i).first
        return dx
    }

    @Test fun mediumSpeedUsesTheFlatGain() {
        // 0.5 mm / 10 ms = 50 mm/s → factor 0.9 × 11.685 units/mm, speed setting 0.
        val dx = steady(PointerAccel(pxPerMm = 10f, speed = 0f), stepPx = 5f)
        assertEquals(0.5f * 11.685f * 0.9f, dx, 0.02f)
    }

    @Test fun slowIsDampedAndFastIsAmplified() {
        val accel = PointerAccel(pxPerMm = 10f, speed = 0f)
        val slow = steady(accel, 0.2f) / 0.2f     // 2 mm/s
        val medium = steady(accel, 5f) / 5f       // 50 mm/s
        val fast = steady(accel, 50f) / 50f       // 500 mm/s
        assertTrue("slow=$slow medium=$medium", slow < medium * 0.7f)
        assertTrue("medium=$medium fast=$fast", fast > medium * 3f)
    }

    @Test fun higherSpeedSettingScalesTheWholeCurve() {
        val base = steady(PointerAccel(pxPerMm = 10f, speed = 0f), 5f)
        val quick = steady(PointerAccel(pxPerMm = 10f, speed = 0.5f), 5f)
        assertEquals(2.54f, quick / base, 0.05f)
    }

    @Test fun resetForgetsPreviousSpeed() {
        val accel = PointerAccel(pxPerMm = 10f, speed = 0f)
        steady(accel, 50f)
        accel.reset(0f, 0f, 1000)
        val dx = accel.accelerate(0.2f, 0f, 1010).first
        assertTrue("dx=$dx", dx < 0.2f * 1.17f * 0.9f)
    }
}
