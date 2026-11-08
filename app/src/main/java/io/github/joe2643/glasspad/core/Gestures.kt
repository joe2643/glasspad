package io.github.joe2643.glasspad.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** What happened to the touch surface in one frame. */
enum class PadPhase { DOWN, MOVE, UP, CANCEL }

/** One finger on the touch surface, with the platform's stable pointer id. */
data class PadPointer(val id: Int, val x: Float, val y: Float)

/**
 * A framework-free snapshot of the touchpad at one instant, produced by the Android `TouchpadView`.
 * [pointers] lists every finger currently down (for UP, still including the lifting one at its
 * final position). [changedId] is the finger that went down / lifted, or -1 for MOVE and CANCEL.
 */
data class PadFrame(
    val phase: PadPhase,
    val changedId: Int,
    val pointers: List<PadPointer>,
    val timeMs: Long,
)

/** System navigation a gesture can ask for (Android's touchpad navigation gestures). */
enum class NavTarget { BACK, HOME, RECENTS }

/** Semantic actions the recognizer emits. Move/DragMove deltas are cursor pixels (accelerated). */
sealed interface GestureAction {
    data class Move(val dx: Float, val dy: Float) : GestureAction
    data object LeftClick : GestureAction
    data object RightClick : GestureAction
    data class Scroll(val dx: Float, val dy: Float) : GestureAction
    data object DragStart : GestureAction
    data class DragMove(val dx: Float, val dy: Float) : GestureAction
    data object DragEnd : GestureAction
    data object PinchStart : GestureAction
    /** Finger distance relative to when the two fingers began together (> 1 = spread = zoom in). */
    data class Pinch(val scale: Float) : GestureAction
    data object PinchEnd : GestureAction
    data class Navigate(val target: NavTarget) : GestureAction
}

/**
 * Tunable thresholds. Distances are physical millimetres (converted with [pxPerMm], which the
 * Android layer derives from the phone's real dpi), so the feel doesn't change with screen density.
 * Defaults are libinput's touchpad values (`evdev-mt-touchpad-tap.c`, `-gestures.c`).
 */
data class GestureConfig(
    val pxPerMm: Float = 160f / 25.4f,
    /** A tap must lift within this long after its (last) finger landed. */
    val tapTimeoutMs: Long = 180,
    /** …and no finger may travel further than this from where it landed. */
    val tapMaxMoveMm: Float = 1.3f,
    /** A touch landing this soon after a one-finger tap can become tap-and-drag. */
    val dragWindowMs: Long = 180,
    /** Both fingers must travel this far, together, before two fingers scroll. */
    val scrollStartMm: Float = 1.5f,
    /** The finger distance must change this much (and more than the fingers drift) to pinch. */
    val pinchStartMm: Float = 2.5f,
    /** Movement past which an undecided two-finger gesture is decided by direction alone. */
    val gestureCertainMm: Float = 8f,
    /** For this long after two fingers meet, a scroll or a thumb can still turn into a pinch. */
    val pinchSwitchMs: Long = 300,
    /** Three fingers must move this far together before their swipe counts. */
    val swipeStartMm: Float = 6f,
    /** A three-finger swipe up that rests this long is "swipe up and hold" (recents). */
    val swipeHoldMs: Long = 300,
    /** A finger that has stayed within this distance past the tap timeout is resting: a tap beside
     *  it is still a one-finger click. */
    val restMaxMoveMm: Float = 1.5f,
    /** One of two fingers moving this far while the other stays put still scrolls (it lags or rests). */
    val scrollLeadMm: Float = 4f,
    /** Pause in pointer motion after the finger count drops, so the cursor doesn't lurch. */
    val fingerSwitchMs: Long = 100,
    /** Pointer speed setting in [-1, 1]; 0.5 ≈ the old fixed 1.4× gain at normal speed. */
    val pointerSpeed: Float = 0.5f,
)

/**
 * Stateful interpreter: fed a stream of [PadFrame]s, emits [GestureAction]s. Pure — no Android
 * types cross this boundary. The rules follow libinput's touchpad tap state machine:
 *
 *  - **Tap = click.** One finger down→up within [GestureConfig.tapTimeoutMs] that never travelled
 *    past [GestureConfig.tapMaxMoveMm] → LeftClick; two fingers → RightClick. Any travel past the
 *    slop, or holding past the timeout, makes the touch a pointer touch that can never click.
 *  - **Move = move only.** While a touch could still be a tap its motion is held back (so a tap
 *    never nudges the cursor); the moment it can't, the held motion is flushed and the cursor
 *    follows the finger with [PointerAccel] acceleration.
 *  - **Tap-and-drag.** A touch landing within [GestureConfig.dragWindowMs] of a one-finger tap that
 *    then moves past the slop (or is held) presses and drags; lifting it again quickly instead is a
 *    second click (double-click).
 *  - **Two fingers never move the pointer** (Chrome Remote Desktop's rule): it's a scroll or a pinch,
 *    whenever the second finger lands — even if the first was already moving the pointer or had
 *    been resting. They are judged from where they were when they met. Their distance changing by
 *    [GestureConfig.pinchStartMm] and by more than they drift together is a **pinch** — so one finger
 *    may stay put and the hand may drift. Moving together with little change in distance is a
 *    **scroll**, and so is one finger moving on while the other lags or rests. Within
 *    [GestureConfig.pinchSwitchMs] a scroll can still become a pinch (libinput, ChromeOS gestures).
 *  - **After a scroll or pinch** the pointer stays still until every finger has lifted, so lifting
 *    the fingers one at a time never nudges the cursor (Chrome Remote Desktop). Putting a second
 *    finger back down resumes the scroll.
 *  - **Resting fingers.** A finger that rested motionless past the tap timeout before another lands
 *    doesn't count toward a tap: tapping beside it is a left click, not a right click. A finger that
 *    lands during a drag, or a third finger during a scroll or pinch, is ignored.
 *  - **Lifting one finger** never ends the gesture for the others; motion pauses briefly
 *    ([GestureConfig.fingerSwitchMs]) and continues from the remaining finger without a jump.
 *  - **Three fingers** landing together make a navigation gesture, as on an Android touchpad:
 *    swipe sideways → back, swipe up → home, swipe up and hold → recents, tap → recents; a swipe
 *    down does nothing (Android keeps it for notifications). Once three fingers are down, the
 *    gesture never moves the pointer, scrolls or pinches until every finger has lifted.
 *  - **Cancel** never clicks, and releases a drag or pinch.
 */
class GestureRecognizer(private val config: GestureConfig = GestureConfig()) {

    private enum class Mode { UNDECIDED, POINTER, SCROLL, PINCH, DRAG }
    private enum class Swipe { NONE, SIDEWAYS, UP, DOWN }

    private class Touch(val id: Int, val startX: Float, val startY: Float, val downTime: Long) {
        var x = startX
        var y = startY
        var dx = 0f
        var dy = 0f
        var travelMm = 0f
        var thumb = false   // ignored entirely (lands during a drag, or a third finger mid-scroll)
        var rested = false  // was resting when the next finger landed: moves, but never taps
        // Where this finger was when it was last paired with another: two-finger gestures measure from here.
        var anchorX = startX
        var anchorY = startY
    }

    private val accel = PointerAccel(config.pxPerMm, config.pointerSpeed)
    private val touches = LinkedHashMap<Int, Touch>()

    private var mode = Mode.UNDECIDED
    private var primaryId = NONE
    private var tapCandidate = false
    private var tapFingers = 0
    private var lastFingerDown = 0L
    private var dragCandidate = false
    private var lastTapUp = NO_TAP
    private var pauseUntil = Long.MIN_VALUE
    private var heldX = 0f
    private var heldY = 0f
    private var pairSpan = 0f            // finger distance when the current pair met (px)
    private var pairStart = NO_TIME      // when it met; NO_TIME = no pair can still become a pinch
    private var lastPinchScale = 1f
    private var pointerLocked = false    // a scroll or pinch happened: no pointer motion until all lift
    // Three-finger gesture state.
    private var trio = false             // three fingers have been down together this gesture
    private var swipe = Swipe.NONE
    private var swipeDone = false        // its one navigation action has been emitted
    private var swipeMovedAt = 0L        // last time the swipe-up was still moving
    private var swipeRefX = 0f           // its mean displacement then (px)
    private var swipeRefY = 0f

    fun onFrame(frame: PadFrame): List<GestureAction> {
        val out = mutableListOf<GestureAction>()
        when (frame.phase) {
            PadPhase.DOWN -> onDown(frame)
            PadPhase.MOVE -> onMove(frame, out)
            PadPhase.UP -> onUp(frame, out)
            PadPhase.CANCEL -> onCancel(out)
        }
        return out
    }

    private fun fingers() = touches.values.filter { !it.thumb }

    private fun isResting(t: Touch, now: Long) =
        t.travelMm < config.restMaxMoveMm && now - t.downTime >= config.tapTimeoutMs

    // ---- DOWN ----

    private fun onDown(frame: PadFrame) {
        val p = frame.pointers.firstOrNull { it.id == frame.changedId } ?: return
        if (touches.containsKey(p.id)) return
        val now = frame.timeMs
        val fingers = fingers()
        val touch = Touch(p.id, p.x, p.y, now)
        touches[p.id] = touch

        when {
            fingers.isEmpty() -> startGesture(touch, now, allowDrag = true)
            mode == Mode.DRAG -> touch.thumb = true
            fingers.size == 1 && isResting(fingers[0], now) -> {
                // The finger already down has been resting (say, a thumb holding the phone). Taps
                // count only the new finger, but the two still scroll or pinch together.
                val resting = fingers[0]
                startGesture(touch, now, allowDrag = false)
                resting.rested = true
                pair(resting, touch, now)
            }
            fingers.size == 1 && mode == Mode.POINTER -> {
                // A second finger joins a moving pointer: two fingers scroll or pinch, never point.
                mode = Mode.UNDECIDED
                tapCandidate = false
                pair(fingers[0], touch, now)
            }
            mode != Mode.UNDECIDED -> touch.thumb = true // a third finger mid-scroll/pinch
            else -> {
                // Another finger joining a fresh gesture: a multi-finger tap, a scroll or a pinch.
                // With two other fingers down, a resting one is just a thumb.
                fingers.filter { it.rested }.forEach { it.thumb = true }
                val active = fingers.filter { !it.thumb }
                val count = active.size + 1
                tapFingers = max(tapFingers, count)
                if (count > 3) tapCandidate = false
                lastFingerDown = now // libinput restarts the tap timer on every finger down
                dragCandidate = false
                if (count == 2) pair(active[0], touch, now)
                if (count == 3) startTrio(active + touch)
            }
        }
    }

    /** Three fingers down together: a navigation gesture, measured from where they are now. */
    private fun startTrio(trioFingers: List<Touch>) {
        trio = true
        swipe = Swipe.NONE
        swipeDone = false
        pairStart = NO_TIME
        for (t in trioFingers) { t.anchorX = t.x; t.anchorY = t.y }
    }

    /** Two touches start being judged together: their motion and distance count from here. */
    private fun pair(a: Touch, b: Touch, now: Long) {
        a.anchorX = a.x; a.anchorY = a.y
        b.anchorX = b.x; b.anchorY = b.y
        pairSpan = span(a, b)
        pairStart = now
    }

    private fun startGesture(t: Touch, now: Long, allowDrag: Boolean) {
        mode = Mode.UNDECIDED
        primaryId = t.id
        tapCandidate = true
        tapFingers = 1
        lastFingerDown = now
        dragCandidate = allowDrag && lastTapUp != NO_TAP && now - lastTapUp <= config.dragWindowMs
        lastTapUp = NO_TAP
        pairStart = NO_TIME
        pointerLocked = false
        pauseUntil = Long.MIN_VALUE
        heldX = 0f
        heldY = 0f
        accel.reset(t.x, t.y, now)
    }

    // ---- MOVE ----

    private fun onMove(frame: PadFrame, out: MutableList<GestureAction>) {
        val now = frame.timeMs
        var any = false
        for (p in frame.pointers) {
            val t = touches[p.id] ?: continue
            any = true
            t.dx = p.x - t.x
            t.dy = p.y - t.y
            t.x = p.x
            t.y = p.y
            t.travelMm = max(t.travelMm, hypot(p.x - t.startX, p.y - t.startY) / config.pxPerMm)
        }
        if (!any) return
        val fingers = fingers()
        if (fingers.isEmpty()) return
        if (fingers.any { !it.rested && it.travelMm > config.tapMaxMoveMm } || now - lastFingerDown > config.tapTimeoutMs) {
            tapCandidate = false
        }
        if (trio) { threeFingerMove(fingers, now, out); return }
        if (fingers.size >= 2) twoFingerMove(fingers[0], fingers[1], now, out)
        else oneFingerMove(fingers[0], now, out)
    }

    private fun inPinchWindow(now: Long) = pairStart != NO_TIME && now - pairStart <= config.pinchSwitchMs

    // ---- three fingers ----

    /**
     * Decides the swipe from the fingers' mean motion, once it passes [GestureConfig.swipeStartMm]:
     * mostly sideways → back at once; up → home on lift, or recents if it rests first; down → nothing.
     */
    private fun threeFingerMove(fingers: List<Touch>, now: Long, out: MutableList<GestureAction>) {
        if (swipeDone || fingers.size < 3) return
        val mx = fingers.sumOf { (it.x - it.anchorX).toDouble() }.toFloat() / fingers.size
        val my = fingers.sumOf { (it.y - it.anchorY).toDouble() }.toFloat() / fingers.size
        when (swipe) {
            Swipe.NONE -> {
                if (max(abs(mx), abs(my)) / config.pxPerMm < config.swipeStartMm) return
                tapCandidate = false
                swipe = when {
                    abs(mx) > abs(my) -> Swipe.SIDEWAYS
                    my < 0f -> Swipe.UP
                    else -> Swipe.DOWN
                }
                when (swipe) {
                    Swipe.SIDEWAYS -> navigate(NavTarget.BACK, out)
                    Swipe.UP -> { swipeMovedAt = now; swipeRefX = mx; swipeRefY = my }
                    else -> swipeDone = true // down is Android's notifications: leave it alone
                }
            }
            Swipe.UP -> when {
                hypot(mx - swipeRefX, my - swipeRefY) / config.pxPerMm >= SWIPE_STILL_MM -> {
                    swipeMovedAt = now; swipeRefX = mx; swipeRefY = my
                }
                now - swipeMovedAt >= config.swipeHoldMs -> navigate(NavTarget.RECENTS, out)
            }
            else -> Unit
        }
    }

    private fun navigate(target: NavTarget, out: MutableList<GestureAction>) {
        swipeDone = true
        out += GestureAction.Navigate(target)
    }

    /** A finger lifted from a three-finger gesture. Returns true while others are still down. */
    private fun threeFingerUp(now: Long, out: MutableList<GestureAction>): Boolean {
        // Lifting ends a swipe up: home, or recents if it had been resting (even with no frames).
        if (swipe == Swipe.UP && !swipeDone) {
            navigate(if (now - swipeMovedAt >= config.swipeHoldMs) NavTarget.RECENTS else NavTarget.HOME, out)
        }
        if (fingers().isNotEmpty()) return true
        if (swipe == Swipe.NONE && tapCandidate && tapFingers >= 3 && now - lastFingerDown <= config.tapTimeoutMs) {
            out += GestureAction.Navigate(NavTarget.RECENTS)
        }
        return false
    }

    private fun oneFingerMove(f: Touch, now: Long, out: MutableList<GestureAction>) {
        if (pointerLocked) return // the rest of a scroll or pinch
        if (f.id != primaryId || now < pauseUntil) {
            // A different finger now drives the pointer, or we're in the post-lift pause:
            // re-anchor at the current position so nothing jumps.
            primaryId = f.id
            accel.reset(f.x, f.y, now)
            return
        }
        if (f.dx == 0f && f.dy == 0f) return
        val (cx, cy) = accel.accelerate(f.x, f.y, now)
        when (mode) {
            Mode.UNDECIDED -> {
                heldX += cx
                heldY += cy
                if (!tapCandidate) {
                    if (dragCandidate) {
                        mode = Mode.DRAG
                        out += GestureAction.DragStart
                        out += GestureAction.DragMove(heldX, heldY)
                    } else {
                        mode = Mode.POINTER
                        out += GestureAction.Move(heldX, heldY)
                    }
                    heldX = 0f
                    heldY = 0f
                }
            }
            Mode.POINTER -> out += GestureAction.Move(cx, cy)
            Mode.DRAG -> out += GestureAction.DragMove(cx, cy)
            Mode.SCROLL, Mode.PINCH -> Unit // not reachable: a lift ends these and switches to POINTER
        }
    }

    // ---- two fingers ----

    private fun span(a: Touch, b: Touch) = hypot(a.x - b.x, a.y - b.y)

    /** How far [t] has moved since it was paired, in mm. */
    private fun moved(t: Touch) = hypot(t.x - t.anchorX, t.y - t.anchorY) / config.pxPerMm

    /** How much the pair's distance has changed since they met, in mm. */
    private fun spread(a: Touch, b: Touch) = abs(span(a, b) - pairSpan) / config.pxPerMm

    /** How far the pair has moved together (the mean of their motions), in mm. */
    private fun drift(a: Touch, b: Touch) = hypot(
        ((a.x - a.anchorX) + (b.x - b.anchorX)) / 2f,
        ((a.y - a.anchorY) + (b.y - b.anchorY)) / 2f,
    ) / config.pxPerMm

    /** The distance changed enough, and more than the pair drifted together. */
    private fun isPinch(a: Touch, b: Touch): Boolean {
        val s = spread(a, b)
        return s >= config.pinchStartMm && s >= drift(a, b)
    }

    private fun twoFingerMove(a: Touch, b: Touch, now: Long, out: MutableList<GestureAction>) {
        when (mode) {
            Mode.SCROLL ->
                if (inPinchWindow(now) && isPinch(a, b)) startPinch(a, b, out)
                else out += GestureAction.Scroll((a.dx + b.dx) / 2f, (a.dy + b.dy) / 2f)
            Mode.PINCH -> pinchUpdate(a, b, out)
            Mode.UNDECIDED -> classifyPair(a, b, now, out)
            Mode.POINTER, Mode.DRAG -> Unit // extra touches in these modes are already thumbs
        }
    }

    /**
     * Decides what an undecided pair is doing — synthesised from ChromeOS gestures
     * (`immediate_interpreter.cc` UpdatePinchState), libinput (`evdev-mt-touchpad-gestures.c`) and
     * Chrome Remote Desktop (`SwipePinchDetector`).
     */
    private fun classifyPair(a: Touch, b: Touch, now: Long, out: MutableList<GestureAction>) {
        val ma = moved(a)
        val mb = moved(b)
        if (ma < NOISE_MM && mb < NOISE_MM) return
        if (isPinch(a, b)) { startPinch(a, b, out); return }
        val sameWay = (a.x - a.anchorX) * (b.x - b.anchorX) + (a.y - a.anchorY) * (b.y - b.anchorY) > 0f
        val together = ma >= config.scrollStartMm && mb >= config.scrollStartMm && spread(a, b) < 0.5f * drift(a, b)
        val certain = ma >= config.gestureCertainMm || mb >= config.gestureCertainMm
        val lagging = (ma >= config.scrollLeadMm && mb < config.restMaxMoveMm) ||
            (mb >= config.scrollLeadMm && ma < config.restMaxMoveMm)
        when {
            together || (certain && sameWay) || lagging -> {
                mode = Mode.SCROLL
                tapCandidate = false
                pointerLocked = true
                out += GestureAction.Scroll((a.dx + b.dx) / 2f, (a.dy + b.dy) / 2f)
            }
            ma >= config.gestureCertainMm && mb >= config.gestureCertainMm -> startPinch(a, b, out) // opposite ways
        }
    }

    private fun startPinch(a: Touch, b: Touch, out: MutableList<GestureAction>) {
        mode = Mode.PINCH
        tapCandidate = false
        pointerLocked = true
        lastPinchScale = 1f
        out += GestureAction.PinchStart
        pinchUpdate(a, b, out)
    }

    /** Emits the cumulative scale since the pair met, so motion made while deciding isn't lost. */
    private fun pinchUpdate(a: Touch, b: Touch, out: MutableList<GestureAction>) {
        if (pairSpan <= 0f) return
        val scale = span(a, b) / pairSpan
        if (abs(scale - lastPinchScale) < PINCH_STEP) return
        out += GestureAction.Pinch(scale)
        lastPinchScale = scale
    }

    // ---- UP / CANCEL ----

    private fun onUp(frame: PadFrame, out: MutableList<GestureAction>) {
        val t = touches[frame.changedId] ?: return
        val now = frame.timeMs
        frame.pointers.firstOrNull { it.id == t.id }?.let { p ->
            t.travelMm = max(t.travelMm, hypot(p.x - t.startX, p.y - t.startY) / config.pxPerMm)
        }
        touches.remove(t.id)
        if (t.thumb) return
        if (t.travelMm > config.tapMaxMoveMm) tapCandidate = false

        if (trio) {
            if (!threeFingerUp(now, out)) endGesture()
            return
        }

        val remaining = fingers()
        if (remaining.isNotEmpty()) {
            // Tapped beside a resting finger: a click by the tapping fingers alone.
            if (remaining.all { it.rested } && tapCandidate && now - lastFingerDown <= config.tapTimeoutMs) {
                out += if (tapFingers >= 2) GestureAction.RightClick else GestureAction.LeftClick
                tapCandidate = false
            }
            // Fewer fingers, same gesture: carry on from a remaining finger after a short pause.
            if (mode == Mode.SCROLL || mode == Mode.PINCH) {
                if (mode == Mode.PINCH) out += GestureAction.PinchEnd
                mode = Mode.POINTER
                tapCandidate = false
            }
            val next = remaining[0]
            primaryId = next.id
            pauseUntil = now + config.fingerSwitchMs
            accel.reset(next.x, next.y, now)
            heldX = 0f
            heldY = 0f
            return
        }

        // The last finger lifted: the gesture is over.
        when {
            mode == Mode.DRAG -> out += GestureAction.DragEnd
            mode == Mode.PINCH -> out += GestureAction.PinchEnd
            tapCandidate && now - lastFingerDown <= config.tapTimeoutMs ->
                if (tapFingers >= 2) {
                    out += GestureAction.RightClick
                } else {
                    out += GestureAction.LeftClick
                    lastTapUp = now
                }
        }
        endGesture()
    }

    private fun onCancel(out: MutableList<GestureAction>) {
        if (mode == Mode.DRAG) out += GestureAction.DragEnd
        if (mode == Mode.PINCH) out += GestureAction.PinchEnd
        touches.clear()
        endGesture()
        lastTapUp = NO_TAP
    }

    private fun endGesture() {
        mode = Mode.UNDECIDED
        primaryId = NONE
        tapCandidate = false
        tapFingers = 0
        dragCandidate = false
        pauseUntil = Long.MIN_VALUE
        heldX = 0f
        heldY = 0f
        pairSpan = 0f
        pairStart = NO_TIME
        lastPinchScale = 1f
        pointerLocked = false
        trio = false
        swipe = Swipe.NONE
        swipeDone = false
    }

    private companion object {
        const val NONE = -1
        const val NO_TAP = Long.MIN_VALUE
        const val NO_TIME = Long.MIN_VALUE
        const val NOISE_MM = 1f        // below this, two fingers haven't really moved (ChromeOS: d1²+d2² < 2)
        const val PINCH_STEP = 0.0025f // emit a pinch update per 0.25% change (ChromeOS sends per 0.5% of d²)
        const val SWIPE_STILL_MM = 1f  // a resting swipe-up moves less than this between checks
    }
}
