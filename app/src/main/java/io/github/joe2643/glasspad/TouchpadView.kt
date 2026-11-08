package io.github.joe2643.glasspad

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import io.github.joe2643.glasspad.core.PadFrame
import io.github.joe2643.glasspad.core.PadPhase
import io.github.joe2643.glasspad.core.PadPointer

/**
 * The phone-side trackpad surface. Translates raw [MotionEvent]s into framework-free [PadFrame]s
 * and hands them to [onPadFrame]; all interpretation happens in the pure `GestureRecognizer`.
 *
 * Every frame carries **all** pointers with their stable ids, so the recognizer can follow one
 * finger even when another (a resting thumb, a palm) comes and goes. Batched historical samples
 * are replayed as individual MOVE frames so speed-based pointer acceleration sees real timings.
 *
 * It also draws a soft mark under each finger, and a short gesture guide until the first touch.
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onPadFrame: ((PadFrame) -> Unit)? = null

    private val fingers = mutableMapOf<Int, PointF>()
    private val fingerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT; alpha = 34 }
    private val fingerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.ACCENT; alpha = 120; style = Paint.Style.STROKE; strokeWidth = px(1.5f)
    }
    private val hintTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.MUTED; textAlign = Paint.Align.CENTER; textSize = sp(15f); isFakeBoldText = true
    }
    private val hintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.FAINT; textAlign = Paint.Align.CENTER; textSize = sp(13f)
    }
    private var hintAlpha = 1f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sink = onPadFrame
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                sink?.invoke(frame(PadPhase.DOWN, event, event.getPointerId(event.actionIndex)))
            MotionEvent.ACTION_MOVE -> {
                if (sink != null) {
                    for (h in 0 until event.historySize) {
                        val pointers = List(event.pointerCount) { i ->
                            PadPointer(event.getPointerId(i), event.getHistoricalX(i, h), event.getHistoricalY(i, h))
                        }
                        sink(PadFrame(PadPhase.MOVE, NO_POINTER, pointers, event.getHistoricalEventTime(h)))
                    }
                    sink(frame(PadPhase.MOVE, event, NO_POINTER))
                }
            }
            MotionEvent.ACTION_POINTER_UP ->
                sink?.invoke(frame(PadPhase.UP, event, event.getPointerId(event.actionIndex)))
            MotionEvent.ACTION_UP -> {
                performClick()
                sink?.invoke(frame(PadPhase.UP, event, event.getPointerId(event.actionIndex)))
            }
            MotionEvent.ACTION_CANCEL -> sink?.invoke(frame(PadPhase.CANCEL, event, NO_POINTER))
            else -> return false
        }
        trackFingers(event)
        return true
    }

    private fun trackFingers(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && hintAlpha == 1f) fadeHint()
        fingers.clear()
        if (e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until e.pointerCount) {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                fingers[e.getPointerId(i)] = PointF(e.getX(i), e.getY(i))
            }
        }
        invalidate()
    }

    private fun fadeHint() {
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 400
            addUpdateListener { hintAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hintAlpha > 0f) drawHint(canvas)
        val r = px(26f)
        for (p in fingers.values) {
            canvas.drawCircle(p.x, p.y, r, fingerFill)
            canvas.drawCircle(p.x, p.y, r, fingerRing)
        }
    }

    private fun drawHint(canvas: Canvas) {
        val gap = px(24f)
        var y = height / 2f - gap * (HINTS.size + 1) / 2f
        hintTitle.alpha = (255 * hintAlpha).toInt()
        canvas.drawText("Trackpad", width / 2f, y, hintTitle)
        hintLine.alpha = (255 * hintAlpha).toInt()
        y += gap * 1.3f
        for (line in HINTS) {
            canvas.drawText(line, width / 2f, y, hintLine)
            y += gap
        }
    }

    private fun frame(phase: PadPhase, e: MotionEvent, changedId: Int) = PadFrame(
        phase = phase,
        changedId = changedId,
        pointers = List(e.pointerCount) { i -> PadPointer(e.getPointerId(i), e.getX(i), e.getY(i)) },
        timeMs = e.eventTime,
    )

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun px(dp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private companion object {
        const val NO_POINTER = -1
        val HINTS = listOf(
            "Slide to move · tap to click",
            "Two-finger tap to right-click",
            "Two fingers to scroll or pinch",
            "Tap, then press and slide to drag",
            "Three fingers: back · home · recents",
        )
    }
}
