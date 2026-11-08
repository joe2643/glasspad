package io.github.joe2643.glasspad.core

/** A task as `dumpsys activity activities` lists it: its id and windowing mode. */
data class TaskRef(val id: Int, val mode: String)

/**
 * Reads `dumpsys activity activities`. Used instead of accessibility to find the desktop's front
 * app, because accessibility only reports windows of the top-focused display. Pure.
 */
object ActivityDump {

    private val TASK = Regex("""^\s*\* Task\{\S+ #(\d+) (.*)\}$""")
    private const val WALLPAPER = "com.android.wm.shell.desktop_wallpaper"

    /**
     * The front-most visible app task on [displayId] — the first standard, visible task with an
     * app (not the desktop wallpaper) in that display's "activities from top to bottom" block.
     */
    fun topAppTask(dump: String, displayId: Int): TaskRef? {
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.startsWith("Display #$displayId ") }
        if (start < 0) return null
        for (line in lines.drop(start + 1)) {
            if (line.isBlank()) continue
            // The block ends at the next display, its "Resumed activities" footer, or a new section.
            if (!line.startsWith(" ") || line.trimStart().startsWith("Resumed activities")) break
            val m = TASK.find(line) ?: continue
            val fields = m.groupValues[2].split(' ')
            val app = fields.firstOrNull { it.startsWith("A=") }?.substringAfter(':')
            if ("type=standard" !in fields || "visible=true" !in fields || app == null || app == WALLPAPER) continue
            val mode = fields.firstOrNull { it.startsWith("mode=") }?.substringAfter('=') ?: continue
            return TaskRef(m.groupValues[1].toInt(), mode)
        }
        return null
    }
}
