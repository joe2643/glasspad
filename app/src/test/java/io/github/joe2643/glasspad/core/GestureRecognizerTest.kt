package io.github.joe2643.glasspad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Behaviour of the touchpad recognizer. Thresholds follow libinput's touchpad defaults (1.3 mm tap
 * slop, 180 ms tap / tap-and-drag windows); 10 px per mm here keeps the arithmetic obvious.
 */
class GestureRecognizerTest {

    /** Drives a recognizer with per-pointer frames, the way TouchpadView does. */
    private class Pad(config: GestureConfig = GestureConfig(pxPerMm = 10f)) {
        val r = GestureRecognizer(config)
        private val pts = LinkedHashMap<Int, PadPointer>()
        val out = mutableListOf<GestureAction>()

        private fun frame(phase: PadPhase, changed: Int, t: Long) =
            r.onFrame(PadFrame(phase, changed, pts.values.toList(), t))

        fun down(id: Int, x: Float, y: Float, t: Long) = apply {
            pts[id] = PadPointer(id, x, y); out += frame(PadPhase.DOWN, id, t)
        }

        fun move(id: Int, x: Float, y: Float, t: Long) = apply {
            pts[id] = PadPointer(id, x, y); out += frame(PadPhase.MOVE, -1, t)
        }

        /** Slides the given pointers together by (dx, dy) in [steps] equal frames over [durMs]. */
        fun slide(dx: Float, dy: Float, from: Long, durMs: Long, vararg ids: Int, steps: Int = 10) = apply {
            val start = ids.associateWith { pts.getValue(it) }
            for (i in 1..steps) {
                for (id in ids) {
                    val p = start.getValue(id)
                    pts[id] = PadPointer(id, p.x + dx * i / steps, p.y + dy * i / steps)
                }
                out += frame(PadPhase.MOVE, -1, from + durMs * i / steps)
            }
        }

        /** Moves [a] left and [b] right by [delta]/2 each (negative = toward each other). */
        fun pinch(delta: Float, from: Long, durMs: Long, a: Int, b: Int, steps: Int = 10) = apply {
            val pa = pts.getValue(a); val pb = pts.getValue(b)
            for (i in 1..steps) {
                val d = delta / 2f * i / steps
                pts[a] = PadPointer(a, pa.x - d, pa.y)
                pts[b] = PadPointer(b, pb.x + d, pb.y)
                out += frame(PadPhase.MOVE, -1, from + durMs * i / steps)
            }
        }

        fun up(id: Int, t: Long) = apply { out += frame(PadPhase.UP, id, t); pts.remove(id) }
        fun cancel(t: Long) = apply { out += frame(PadPhase.CANCEL, -1, t); pts.clear() }
        fun clear() = apply { out.clear() }

        fun tap(id: Int, x: Float, y: Float, t: Long, durMs: Long = 60) = down(id, x, y, t).up(id, t + durMs)
    }

    private val clicks = setOf(GestureAction.LeftClick, GestureAction.RightClick)
    private fun List<GestureAction>.moves() = filterIsInstance<GestureAction.Move>()
    private fun List<GestureAction>.has(a: GestureAction) = contains(a)

    // ---- one finger: tap vs move ----

    @Test fun singleTapIsLeftClick() {
        val pad = Pad().down(0, 100f, 100f, 0).move(0, 103f, 101f, 40).up(0, 80)
        assertEquals(listOf(GestureAction.LeftClick), pad.out)
    }

    @Test fun slideMovesCursorAndNeverClicks() {
        val pad = Pad().down(0, 100f, 100f, 0).slide(60f, 0f, 0, 120, 0).up(0, 140)
        assertTrue(pad.out.moves().isNotEmpty())
        assertTrue(pad.out.none { it in clicks || it == GestureAction.DragStart })
    }

    @Test fun shortQuickFlickIsAMoveNotAClick() {
        // 2 mm in 40 ms: quick enough to be a tap by time, but past the 1.3 mm slop.
        val pad = Pad().down(0, 100f, 100f, 0).slide(20f, 0f, 0, 40, 0, steps = 2).up(0, 60)
        assertFalse(pad.out.has(GestureAction.LeftClick))
        assertTrue(pad.out.moves().isNotEmpty())
    }

    @Test fun jitterDuringATapDoesNotNudgeTheCursor() {
        val pad = Pad().down(0, 100f, 100f, 0).move(0, 105f, 100f, 30).up(0, 70)
        assertEquals(listOf(GestureAction.LeftClick), pad.out)
    }

    @Test fun longPressWithoutMovingDoesNothing() {
        val pad = Pad().down(0, 100f, 100f, 0).up(0, 400)
        assertTrue(pad.out.isEmpty())
    }

    @Test fun afterAHoldEvenATinyNudgeMovesTheCursor() {
        // Precision positioning: rest the finger, then roll it a fraction of a millimetre.
        val pad = Pad().down(0, 100f, 100f, 0).move(0, 103f, 100f, 250).up(0, 300)
        assertTrue(pad.out.moves().isNotEmpty())
        assertTrue(pad.out.none { it in clicks })
    }

    @Test fun motionHeldBackDuringTheTapCheckIsNotLost() {
        // The first 1.2 mm is withheld (it might have been a tap) and must be flushed, not dropped.
        val withheld = Pad().down(0, 100f, 100f, 0).move(0, 112f, 100f, 10).move(0, 140f, 100f, 20)
        val direct = Pad().down(0, 100f, 100f, 0).move(0, 140f, 100f, 20)
        val a = withheld.out.moves().sumOf { it.dx.toDouble() }
        val b = direct.out.moves().sumOf { it.dx.toDouble() }
        assertTrue("withheld=$a direct=$b", a > b * 0.6)
    }

    // ---- tap-and-drag / double tap ----

    @Test fun tapThenSlideIsADrag() {
        val pad = Pad().tap(0, 100f, 100f, 0).clear()
            .down(0, 100f, 100f, 150).slide(50f, 0f, 150, 100, 0).up(0, 300)
        assertEquals(GestureAction.DragStart, pad.out.first())
        assertTrue(pad.out.any { it is GestureAction.DragMove })
        assertEquals(GestureAction.DragEnd, pad.out.last())
        assertTrue(pad.out.none { it in clicks })
    }

    @Test fun doubleTapWithJitterIsTwoClicksNotADrag() {
        val pad = Pad().tap(0, 100f, 100f, 0)
            .down(0, 100f, 100f, 140).move(0, 104f, 100f, 160).up(0, 200)
        assertEquals(listOf(GestureAction.LeftClick, GestureAction.LeftClick), pad.out)
    }

    @Test fun slideStartedAfterTheDragWindowIsAPlainMove() {
        val pad = Pad().tap(0, 100f, 100f, 0).clear()
            .down(0, 100f, 100f, 300).slide(50f, 0f, 300, 100, 0).up(0, 420)
        assertFalse(pad.out.has(GestureAction.DragStart))
        assertTrue(pad.out.moves().isNotEmpty())
    }

    @Test fun slideAfterASlideIsNeverADrag() {
        // Repositioning strokes (swipe, lift, swipe) must not arm tap-and-drag.
        val pad = Pad().down(0, 100f, 100f, 0).slide(50f, 0f, 0, 80, 0).up(0, 90)
            .down(0, 100f, 100f, 120).slide(50f, 0f, 120, 80, 0).up(0, 210)
        assertFalse(pad.out.has(GestureAction.DragStart))
    }

    // ---- two fingers ----

    @Test fun twoFingerTapIsRightClick() {
        val pad = Pad().down(0, 100f, 100f, 0).down(1, 200f, 100f, 20).up(1, 80).up(0, 90)
        assertEquals(listOf(GestureAction.RightClick), pad.out)
    }

    @Test fun twoFingerSlideScrollsWithoutMovingTheCursor() {
        val pad = Pad().down(0, 100f, 100f, 0).down(1, 200f, 100f, 10)
            .slide(0f, 60f, 20, 120, 0, 1).up(0, 150).up(1, 160)
        assertTrue(pad.out.any { it is GestureAction.Scroll })
        assertTrue(pad.out.moves().isEmpty())
        assertTrue(pad.out.none { it in clicks })
    }

    @Test fun oneStillFingerAndOneSidewaysFingerMovesThePointer() {
        // Moving across the line between the fingers barely changes their distance: not a pinch.
        val pad = Pad().down(0, 100f, 500f, 0).down(1, 300f, 500f, 10)
            .slide(0f, 60f, 20, 100, 1).up(1, 130).up(0, 140)
        assertTrue(pad.out.moves().isNotEmpty())
        assertFalse(pad.out.any { it is GestureAction.Scroll || it is GestureAction.PinchStart })
        assertTrue(pad.out.none { it in clicks })
    }

    @Test fun liftingOneScrollFingerDoesNotMakeTheCursorJump() {
        val pad = Pad().down(0, 100f, 100f, 0).down(1, 400f, 100f, 10)
            .slide(0f, 60f, 20, 100, 0, 1)
            .up(0, 130)
            .move(1, 400f, 165f, 160)   // inside the 100 ms switch pause: ignored
            .move(1, 403f, 165f, 260)   // after it: a small, local move
        assertTrue(pad.out.moves().all { hypot(it.dx, it.dy) < 40f })
    }

    // ---- pinch ----

    /** The zoom a pinch ended at: Pinch scales are cumulative from where the fingers landed. */
    private fun List<GestureAction>.zoom() = filterIsInstance<GestureAction.Pinch>().last().scale

    @Test fun spreadingTwoFingersPinchesOut() {
        val pad = Pad().down(0, 300f, 500f, 0).down(1, 500f, 500f, 10)
            .pinch(120f, 20, 120, 0, 1).up(0, 150).up(1, 160)
        assertEquals(GestureAction.PinchStart, pad.out.first())
        assertEquals(320f / 200f, pad.out.zoom(), 1e-3f) // finger distance 200 → 320 px
        assertEquals(1, pad.out.count { it == GestureAction.PinchEnd })
        assertTrue(pad.out.none { it is GestureAction.Scroll || it is GestureAction.Move || it in clicks })
    }

    @Test fun closingTwoFingersPinchesIn() {
        val pad = Pad().down(0, 200f, 500f, 0).down(1, 600f, 500f, 10)
            .pinch(-200f, 20, 120, 0, 1).up(0, 150).up(1, 160)
        assertEquals(200f / 400f, pad.out.zoom(), 1e-3f)
    }

    @Test fun pinchEndsWhenAFingerLiftsAndTheOtherMovesThePointer() {
        val pad = Pad().down(0, 300f, 500f, 0).down(1, 500f, 500f, 10)
            .pinch(120f, 20, 120, 0, 1).up(0, 150)
        assertEquals(GestureAction.PinchEnd, pad.out.last())
        pad.clear().move(1, 600f, 500f, 300).move(1, 640f, 500f, 320)
        assertTrue(pad.out.none { it is GestureAction.Pinch })
        assertTrue(pad.out.moves().isNotEmpty())
    }

    @Test fun movingOneFingerAwayFromAStillOnePinches() {
        // Chrome Remote Desktop's rule: an anchored finger plus a radial mover is a pinch.
        val pad = Pad().down(0, 300f, 500f, 0).down(1, 500f, 500f, 10)
            .slide(80f, 0f, 20, 100, 1).up(1, 130).up(0, 140)
        assertEquals(GestureAction.PinchStart, pad.out.first())
        assertEquals(280f / 200f, pad.out.zoom(), 1e-3f)
        assertTrue(pad.out.none { it is GestureAction.Move || it is GestureAction.Scroll })
    }

    @Test fun spreadingWhileTheHandDriftsIsStillAPinch() {
        // Both fingers move right (the old dot-product rule called this a scroll), but their
        // distance grows faster than they drift together.
        val pad = Pad().down(0, 300f, 500f, 0).down(1, 500f, 500f, 10)
        for (i in 1..10) {
            pad.move(0, 300f + 2f * i, 500f, 20L + 10 * i).move(1, 500f + 8f * i, 500f, 20L + 10 * i)
        }
        pad.up(0, 150).up(1, 160)
        assertTrue(pad.out.contains(GestureAction.PinchStart))
        assertTrue(pad.out.none { it is GestureAction.Scroll })
    }

    @Test fun aRestingThumbCanJoinAPinch() {
        // The first finger rested past the tap timeout (a thumb), but moves with the second one.
        val pad = Pad().down(0, 300f, 500f, 0).down(1, 500f, 500f, 500)
            .pinch(120f, 510, 120, 0, 1).up(0, 650).up(1, 660)
        assertTrue(pad.out.contains(GestureAction.PinchStart))
        assertEquals(320f / 200f, pad.out.zoom(), 1e-3f)
    }

    @Test fun aScrollThatTurnsIntoASpreadSoonBecomesAPinch() {
        val pad = Pad().down(0, 300f, 500f, 0).down(1, 500f, 500f, 10)
            .slide(0f, 30f, 20, 40, 0, 1, steps = 3)  // 3 mm together: a scroll
        assertTrue(pad.out.any { it is GestureAction.Scroll })
        pad.pinch(160f, 70, 80, 0, 1, steps = 8)     // within 300 ms the fingers spread
        assertTrue(pad.out.contains(GestureAction.PinchStart))
    }

    @Test fun cancelEndsAPinch() {
        val pad = Pad().down(0, 300f, 500f, 0).down(1, 500f, 500f, 10)
            .pinch(120f, 20, 120, 0, 1).cancel(150)
        assertEquals(GestureAction.PinchEnd, pad.out.last())
    }

    // ---- three fingers (Android's touchpad navigation gestures) ----

    private fun threeDown() = Pad().down(0, 300f, 500f, 0).down(1, 400f, 500f, 5).down(2, 500f, 500f, 10)
    private fun nav(t: NavTarget) = GestureAction.Navigate(t)
    private fun List<GestureAction>.navs() = filterIsInstance<GestureAction.Navigate>()

    @Test fun threeFingerSwipeSidewaysIsBack() {
        for (dx in listOf(-80f, 80f)) {
            val pad = threeDown().slide(dx, 0f, 20, 100, 0, 1, 2).up(0, 130).up(1, 135).up(2, 140)
            assertEquals(listOf(nav(NavTarget.BACK)), pad.out)
        }
    }

    @Test fun threeFingerSwipeUpIsHome() {
        val pad = threeDown().slide(0f, -80f, 20, 100, 0, 1, 2).up(0, 130).up(1, 135).up(2, 140)
        assertEquals(listOf(nav(NavTarget.HOME)), pad.out)
    }

    @Test fun threeFingerSwipeUpAndHoldIsRecents() {
        val pad = threeDown().slide(0f, -80f, 20, 100, 0, 1, 2)
            .slide(2f, 0f, 130, 400, 0, 1, 2, steps = 8) // the fingers rest, with sensor jitter
        assertEquals(listOf(nav(NavTarget.RECENTS)), pad.out)
        pad.up(0, 600).up(1, 605).up(2, 610)
        assertEquals(listOf(nav(NavTarget.RECENTS)), pad.out) // and lifting doesn't also go home
    }

    @Test fun holdingStillWithNoFramesStillCountsAsAHold() {
        val pad = threeDown().slide(0f, -80f, 20, 100, 0, 1, 2).up(0, 500).up(1, 505).up(2, 510)
        assertEquals(listOf(nav(NavTarget.RECENTS)), pad.out)
    }

    @Test fun threeFingerTapIsRecents() {
        val pad = threeDown().up(0, 60).up(1, 65).up(2, 70)
        assertEquals(listOf(nav(NavTarget.RECENTS)), pad.out)
    }

    @Test fun threeFingerSwipeDownDoesNothing() {
        val pad = threeDown().slide(0f, 80f, 20, 100, 0, 1, 2).up(0, 130).up(1, 135).up(2, 140)
        assertTrue(pad.out.isEmpty())
    }

    @Test fun aThreeFingerGestureNeverMovesScrollsOrPinchesAfterwards() {
        val pad = threeDown().slide(-80f, 0f, 20, 100, 0, 1, 2)
            .up(0, 130).up(1, 135)
            .slide(60f, 30f, 140, 100, 2).up(2, 260)
        assertEquals(listOf(nav(NavTarget.BACK)), pad.out)
    }

    // ---- resting thumb / palm ----

    @Test fun restingThumbDoesNotTurnATapIntoARightClick() {
        val pad = Pad().down(0, 50f, 1500f, 0)          // thumb rests while holding the phone
            .tap(1, 300f, 600f, 500).up(0, 900)
        assertEquals(listOf(GestureAction.LeftClick), pad.out)
    }

    @Test fun restingThumbDoesNotTurnASlideIntoAScroll() {
        val pad = Pad().down(0, 50f, 1500f, 0)
            .down(1, 300f, 600f, 500).slide(60f, 0f, 500, 100, 1).up(1, 620)
        assertTrue(pad.out.moves().isNotEmpty())
        assertFalse(pad.out.any { it is GestureAction.Scroll })
    }

    @Test fun fingerLandingWhileTheCursorMovesIsIgnored() {
        val pad = Pad().down(0, 100f, 100f, 0).slide(40f, 0f, 0, 80, 0)
        val movesBefore = pad.out.moves().size
        pad.down(1, 300f, 800f, 90).up(1, 120)
            .slide(40f, 0f, 130, 70, 0).up(0, 210)
        assertTrue(pad.out.moves().size > movesBefore)
        assertTrue(pad.out.none { it in clicks || it is GestureAction.Scroll })
    }

    // ---- cancel / robustness ----

    @Test fun cancelNeverClicks() {
        val pad = Pad().down(0, 100f, 100f, 0).cancel(50)
        assertTrue(pad.out.isEmpty())
    }

    @Test fun cancelDuringADragReleasesTheButton() {
        val pad = Pad().tap(0, 100f, 100f, 0)
            .down(0, 100f, 100f, 150).slide(50f, 0f, 150, 100, 0).cancel(260)
        assertEquals(GestureAction.DragEnd, pad.out.last())
    }

    @Test fun cancelForgetsAPendingTapAndDrag() {
        val pad = Pad().tap(0, 100f, 100f, 0).cancel(70).clear()
            .down(0, 100f, 100f, 120).slide(50f, 0f, 120, 80, 0).up(0, 210)
        assertFalse(pad.out.has(GestureAction.DragStart))
    }

    @Test fun framesForUnknownPointersAreIgnored() {
        val r = GestureRecognizer(GestureConfig(pxPerMm = 10f))
        val stray = listOf(PadPointer(7, 10f, 10f))
        assertTrue(r.onFrame(PadFrame(PadPhase.MOVE, -1, stray, 0)).isEmpty())
        assertTrue(r.onFrame(PadFrame(PadPhase.UP, 7, stray, 10)).isEmpty())
    }
}
