package com.example.deskpad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WmShellTest {

    /** Real output of `dumpsys activity service SystemUIService WMShell desktopmode getDeskIds 7`. */
    private val deskIdsDump = """
        SERVICE com.android.systemui/.SystemUIService 3a52c9c pid=2622 user=0
          Client:
            Dump starting: 11-08 00:43:54.333
            com.android.systemui.wmshell.WMShell:
            ----------------------------------------------------------------------------
            35703
            NORMAL dump took 0ms -- com.android.systemui.wmshell.WMShell
            StatusBarOrchestrator7:
            ----------------------------------------------------------------------------
            Hidden
              PhoneStatusBarTransitions.BarTransitions.mMode=MODE_TRANSPARENT
            CRITICAL dump took 0ms -- StatusBarOrchestrator7
    """.trimIndent()

    @Test fun commandsMatchTheShellsHelpText() {
        assertEquals(listOf("desktopmode", "moveTaskOutOfDesk", "35707"), WmShell.moveOutOfDesk(35707))
        assertEquals(listOf("desktopmode", "moveTaskToDesk", "35707", "35703"), WmShell.moveToDesk(35707, 35703))
        assertEquals(listOf("desktopmode", "getDeskIds", "7"), WmShell.deskIds(7))
    }

    @Test fun sectionIsOnlyTheWmShellPart() {
        assertEquals("35703", WmShell.section(deskIdsDump))
    }

    @Test fun emptySectionWhenTheCommandPrintsNothing() {
        val dump = """
            com.android.systemui.wmshell.WMShell:
            ------------------------------------
            NORMAL dump took 1ms -- com.android.systemui.wmshell.WMShell
        """.trimIndent()
        assertEquals("", WmShell.section(dump))
    }

    @Test fun noSectionWhenWmShellNeverAnswered() {
        assertNull(WmShell.section("Permission Denial: can't dump ActivityManager"))
    }

    @Test fun deskIdsAreCommaSeparated() {
        assertEquals(listOf(35703), WmShell.parseDeskIds("35703"))
        assertEquals(listOf(4, 9), WmShell.parseDeskIds("4, 9"))
        assertEquals(emptyList<Int>(), WmShell.parseDeskIds(""))
    }

    @Test fun invalidCommandIsAnError() {
        assertTrue(WmShell.isError("Invalid command: getActivateDeskId\n moveTaskToDesk <taskId|0> <deskId>"))
        assertFalse(WmShell.isError("35703"))
        assertFalse(WmShell.isError(""))
    }
}
