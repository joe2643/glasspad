package com.example.deskpad.core

/**
 * Desktop-mode commands for SystemUI's window-manager shell, and parsing of their output.
 *
 * The commands are what `adb shell dumpsys activity service SystemUIService WMShell <args>` runs
 * (names as printed by `… WMShell help` on Android 17). Moving a task out of its desk makes it a
 * true fullscreen task: the desktop taskbar stashes, and an immersive app hides the status bar too.
 * Pure.
 */
object WmShell {

    fun moveOutOfDesk(taskId: Int) = listOf("desktopmode", "moveTaskOutOfDesk", "$taskId")

    fun moveToDesk(taskId: Int, deskId: Int) = listOf("desktopmode", "moveTaskToDesk", "$taskId", "$deskId")

    fun deskIds(displayId: Int) = listOf("desktopmode", "getDeskIds", "$displayId")

    /**
     * The WMShell part of a SystemUIService dump: the lines between its `…WMShell:` header (and the
     * dashed rule under it) and its `dump took` footer. Null if the dump has no WMShell section.
     */
    fun section(dump: String): String? {
        val lines = dump.lines().map { it.trim() }
        val header = lines.indexOfFirst { it.endsWith("WMShell:") }
        if (header < 0) return null
        val body = lines.drop(header + 1).dropWhile { it.isNotEmpty() && it.all { c -> c == '-' } }
        return body.takeWhile { "dump took" !in it }.joinToString("\n").trim()
    }

    fun parseDeskIds(section: String): List<Int> =
        section.split(',').mapNotNull { it.trim().toIntOrNull() }

    /** WMShell answers a bad command by printing "Invalid command" and its help, not by failing. */
    fun isError(section: String): Boolean = section.startsWith("Invalid command")
}
