package io.github.joe2643.glasspad.tools

import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * One-time setup, run from a computer as the shell user — which holds INTERNAL_SYSTEM_WINDOW, the
 * permission WindowManager.setDisplayImePolicy needs and no app can get:
 *
 *     adb shell 'CLASSPATH=$(pm path io.github.joe2643.glasspad | cut -d: -f2) app_process / io.github.joe2643.glasspad.tools.KeyboardOnPhone phone'
 *
 * Sets every connected external display's IME policy. `phone` is DISPLAY_IME_POLICY_FALLBACK_DISPLAY:
 * a text field on the desktop opens the keyboard on the phone, while the desktop app keeps focus and
 * the keyboard types straight into it. `display` is DISPLAY_IME_POLICY_LOCAL, Android's default.
 * WindowManager stores the policy per display (by its unique id), so it survives reconnects.
 *
 * It runs inside the GlassPad APK via app_process (no extra file to install), never in the app.
 */
object KeyboardOnPhone {
    /** The command the app offers to copy (the display panel). */
    const val COMMAND = "adb shell 'CLASSPATH=\$(pm path io.github.joe2643.glasspad | cut -d: -f2) " +
        "app_process / io.github.joe2643.glasspad.tools.KeyboardOnPhone phone'"

    private const val POLICY_LOCAL = 0     // WindowManager.DISPLAY_IME_POLICY_LOCAL
    private const val POLICY_FALLBACK = 1  // WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY (hidden)
    private const val TYPE_EXTERNAL = 2    // Display.TYPE_EXTERNAL (hidden)

    @JvmStatic
    fun main(args: Array<String>) {
        val policy = when (args.firstOrNull() ?: "phone") {
            "phone" -> POLICY_FALLBACK
            "display", "reset" -> POLICY_LOCAL
            "status" -> null // report only
            else -> {
                System.err.println("usage: KeyboardOnPhone [phone|display|status]")
                return
            }
        }
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }

        val wm = Class.forName("android.view.IWindowManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, service("window"))!!
        val setPolicy = wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val getPolicy = wm.javaClass.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType)

        val externals = externalDisplays()
        if (externals.isEmpty()) {
            println("No external display connected — connect the glasses or monitor first.")
            return
        }
        for ((id, name) in externals) {
            if (policy != null) setPolicy.invoke(wm, id, policy)
            val now = getPolicy.invoke(wm, id) as Int
            val where = when (now) {
                POLICY_FALLBACK -> "on the phone"
                POLICY_LOCAL -> "on the display itself"
                else -> "nowhere (policy $now)"
            }
            println("Display $id ($name): keyboard opens $where.")
        }
    }

    private fun service(name: String): IBinder =
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder

    /** (id, name) of each connected external display. */
    private fun externalDisplays(): List<Pair<Int, String>> {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val dmg = global.getMethod("getInstance").invoke(null)!!
        val ids = runCatching { global.getMethod("getDisplayIds").invoke(dmg) as IntArray }
            .getOrElse { global.getMethod("getDisplayIds", Boolean::class.javaPrimitiveType).invoke(dmg, false) as IntArray }
        val getInfo = global.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
        val found = mutableListOf<Pair<Int, String>>()
        for (id in ids) {
            val info = getInfo.invoke(dmg, id) ?: continue
            if (info.javaClass.getField("type").getInt(info) != TYPE_EXTERNAL) continue
            val name = info.javaClass.getField("name").get(info) as String?
            found += Pair(id, name ?: "external")
        }
        return found
    }
}
