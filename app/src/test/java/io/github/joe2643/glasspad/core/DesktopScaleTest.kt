package io.github.joe2643.glasspad.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopScaleTest {

    @Test fun sliderSpansMinToMaxInEvenSteps() {
        assertEquals(DesktopScale.MIN_DPI, DesktopScale.dpiAt(0))
        assertEquals(DesktopScale.MAX_DPI, DesktopScale.dpiAt(DesktopScale.positions - 1))
        assertEquals(DesktopScale.MIN_DPI + DesktopScale.STEP_DPI, DesktopScale.dpiAt(1))
    }

    @Test fun outOfRangePositionsClamp() {
        assertEquals(DesktopScale.MIN_DPI, DesktopScale.dpiAt(-3))
        assertEquals(DesktopScale.MAX_DPI, DesktopScale.dpiAt(999))
    }

    @Test fun everyPositionRoundTrips() {
        for (p in 0 until DesktopScale.positions) {
            assertEquals(p, DesktopScale.positionOf(DesktopScale.dpiAt(p)))
        }
    }

    @Test fun positionOfSnapsToNearestStepAndClamps() {
        // The VITURE's physical 416 dpi sits between the 400 and 420 steps; nearer 420.
        assertEquals(420, DesktopScale.dpiAt(DesktopScale.positionOf(416)))
        assertEquals(400, DesktopScale.dpiAt(DesktopScale.positionOf(409)))
        assertEquals(0, DesktopScale.positionOf(50))
        assertEquals(DesktopScale.positions - 1, DesktopScale.positionOf(2000))
    }

    @Test fun workspaceMatchesWhatAndroidLaysOutAgainst() {
        // Measured on the Pixel + VITURE: 1920×1200 at 416 dpi → w738dp h462dp; at 240 → 1280×800.
        assertEquals(738 to 462, DesktopScale.workspaceDp(1920, 1200, 416))
        assertEquals(1280 to 800, DesktopScale.workspaceDp(1920, 1200, 240))
    }
}
