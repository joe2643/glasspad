package io.github.joe2643.glasspad.core

/**
 * The seam between pure logic and privileged injection. The real implementation forwards over
 * AIDL to the Shizuku `Injector`; unit tests use a fake that records calls.
 */
interface InputSink {
    fun moveCursor(displayId: Int, x: Float, y: Float)
    fun button(displayId: Int, x: Float, y: Float, primary: Boolean, down: Boolean)
    fun scroll(displayId: Int, x: Float, y: Float, hScroll: Float, vScroll: Float)
    fun key(displayId: Int, keyCode: Int, metaState: Int, down: Boolean)
    /** Two fingers go down around (x, y) and stay down until [pinchEnd]. */
    fun pinchBegin(displayId: Int, x: Float, y: Float)
    /** Their distance changes by [scale] (> 1 spreads them = zoom in). */
    fun pinchScale(displayId: Int, scale: Float)
    fun pinchEnd(displayId: Int)
    /** System back / home / recents on [displayId]. */
    fun navigate(displayId: Int, target: NavTarget)
}
