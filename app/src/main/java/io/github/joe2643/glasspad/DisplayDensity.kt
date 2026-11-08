package io.github.joe2643.glasspad

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Overrides the external display's density — what `adb shell wm density <dpi> -d <id>` does.
 * WindowManager keeps the override per display (keyed by the display's unique id), so it survives
 * reconnects and reboots until reset.
 *
 * Optional, and the one adb-assisted feature: it needs WRITE_SECURE_SETTINGS, which only adb can
 * grant, once ([GRANT_COMMAND]). Without it the rest of GlassPad works as before.
 *
 * Why it calls a hidden IWindowManager method (verified on a Pixel 9 Pro XL, Android 17):
 * - Plain reflection: hidden API, blocked for apps targeting API 31+ (NoSuchMethodException).
 * - `cmd window density …` from the app: "Can't find service: window".
 * - Sending the `wm density` shell command over binder: reaches WindowManager and passes the
 *   WRITE_SECURE_SETTINGS check, but the command always runs as USER_CURRENT, which needs
 *   INTERACT_ACROSS_USERS_FULL — not grantable by adb.
 * So: exempt IWindowManager from hidden-API checks in this process only, and pass our own user id,
 * which skips the cross-user check.
 */
object DisplayDensity {

    const val GRANT_COMMAND =
        "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS"

    private const val TAG = "GlassPadDensity"
    private const val PER_USER_RANGE = 100_000 // UserHandle.PER_USER_RANGE

    private val exempted: Boolean by lazy {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/view/IWindowManager;")
    }

    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Forces [dpi] on [displayId]. Blocking binder call — run off the main thread. */
    fun set(displayId: Int, dpi: Int): Result<Unit> =
        callWindowManager("setForcedDisplayDensityForUser", displayId, dpi, myUserId())

    /** Drops the override, back to the density Android derived from the display. */
    fun reset(displayId: Int): Result<Unit> =
        callWindowManager("clearForcedDisplayDensityForUser", displayId, myUserId())

    private fun myUserId(): Int = Process.myUid() / PER_USER_RANGE

    private fun callWindowManager(method: String, vararg args: Int): Result<Unit> = runCatching {
        check(exempted) { "hidden API exemption refused" }
        val wm = Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService").invoke(null)
            ?: error("window manager unavailable")
        val types = Array<Class<*>>(args.size) { Int::class.javaPrimitiveType!! }
        Class.forName("android.view.IWindowManager").getMethod(method, *types)
            .invoke(wm, *args.toTypedArray())
        Unit
    }.onFailure { Log.w(TAG, "$method(${args.joinToString()}) failed", it) }
}
