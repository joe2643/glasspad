package io.github.joe2643.glasspad

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.joe2643.glasspad.core.ActivityDump
import io.github.joe2643.glasspad.core.TaskRef
import io.github.joe2643.glasspad.core.WmShell
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.concurrent.thread

/**
 * Reads the activity manager's state and runs SystemUI window-manager-shell commands ([WmShell]) —
 * what `adb shell dumpsys activity activities` and
 * `adb shell dumpsys activity service SystemUIService WMShell <args>` do.
 *
 * Moving a task between the desk and fullscreen needs MANAGE_ACTIVITY_TASKS, which no app can be
 * granted (signature|recents). But SystemUI holds it, and its WMShell commands arrive through
 * ActivityManager's dump: ActivityManager only asks the caller for DUMP and PACKAGE_USAGE_STATS,
 * both of which adb can grant once ([GRANT_COMMANDS]). Optional, like [DisplayDensity].
 * Every call blocks — run off the main thread.
 */
object DesktopShell {

    val GRANT_COMMANDS = listOf(Manifest.permission.DUMP, Manifest.permission.PACKAGE_USAGE_STATS)
        .map { "adb shell pm grant ${BuildConfig.APPLICATION_ID} $it" }

    private const val TAG = "GlassPadShell"

    private val exempted: Boolean by lazy { HiddenApiBypass.addHiddenApiExemptions("Landroid/os/ServiceManager;") }

    fun isGranted(context: Context): Boolean =
        listOf(Manifest.permission.DUMP, Manifest.permission.PACKAGE_USAGE_STATS).all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    /** The front app task on [displayId], from the activity manager. */
    fun topAppTask(displayId: Int): TaskRef? {
        val text = dump("activities")
        return ActivityDump.topAppTask(text, displayId).also {
            if (it == null) {
                val lines = text.lines()
                val heads = lines.indices.filter { i -> lines[i].startsWith("Display #") }
                    .joinToString(" | ") { i -> lines.subList(i, minOf(i + 2, lines.size)).joinToString(" ⏎ ").take(200) }
                Log.w(TAG, "no app on display $displayId in ${text.length}-char dump; heads: $heads; start: ${text.take(160)}")
            }
        }
    }

    /** Runs one WMShell command; returns its output. */
    fun wmShell(args: List<String>): String {
        val out = dump("service", "SystemUIService", "WMShell", *args.toTypedArray())
        val section = WmShell.section(out)
            ?: error(out.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "no output from SystemUI")
        check(!WmShell.isError(section)) { section.lineSequence().first() }
        return section
    }

    /** `dumpsys activity <args>`, as this app. */
    private fun dump(vararg args: String): String {
        check(exempted) { "hidden API exemption refused" }
        val activity = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "activity") as? IBinder
            ?: error("activity service unavailable")
        val (read, write) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
        // Drain concurrently: the dump is synchronous and must never block on a full pipe.
        var text = ""
        val reader = thread(name = "glasspad-dump") {
            text = ParcelFileDescriptor.AutoCloseInputStream(read).bufferedReader().use { it.readText() }
        }
        try {
            activity.dump(write.fileDescriptor, args)
        } catch (e: Exception) {
            Log.w(TAG, "dump ${args.joinToString(" ")} failed", e)
            throw e
        } finally {
            write.close()
        }
        reader.join(5_000)
        return text
    }
}
