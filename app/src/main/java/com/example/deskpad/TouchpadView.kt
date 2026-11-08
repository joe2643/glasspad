package com.example.deskpad

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.deskpad.core.PadFrame
import com.example.deskpad.core.PadPhase
import com.example.deskpad.core.PadPointer

/**
 * The phone-side trackpad surface. Translates raw [MotionEvent]s into framework-free [PadFrame]s
 * and hands them to [onPadFrame]; all interpretation happens in the pure `GestureRecognizer`.
 *
 * Every frame carries **all** pointers with their stable ids, so the recognizer can follow one
 * finger even when another (a resting thumb, a palm) comes and goes. Batched historical samples
 * are replayed as individual MOVE frames so speed-based pointer acceleration sees real timings.
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onPadFrame: ((PadFrame) -> Unit)? = null

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
        return true
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

    private companion object {
        const val NO_POINTER = -1
    }
}
