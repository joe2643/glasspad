package io.github.joe2643.glasspad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityDumpTest {

    /** Trimmed `dumpsys activity activities` from a Pixel 9 Pro XL (Android 17) in desktop mode. */
    private val fullscreenOnGlasses = """
        Display #0 (activities from top to bottom):
          * Task{6a511bf #35713 type=standard A=10354:io.github.joe2643.glasspad U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
          * Task{ed8c104 #1 type=home U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
        Display #7 (activities from top to bottom):
          * Task{c317c53 #35707 type=standard A=10488:eu.kanade.tachiyomi.sy U=0 visible=true visibleRequested=false mode=fullscreen translucent=false sz=2}
            mLastPausedActivity: ActivityRecord{14724301 u0 eu.kanade.tachiyomi.sy/eu.kanade.tachiyomi.ui.reader.ReaderActivity t35707}
          * Task{5a3a7a8 #35701 type=home U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
          * Task{d96707e #35705 type=standard A=10241:com.android.wm.shell.desktop_wallpaper U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
          * Task{b478c3f #35703 name=Desk type=undefined U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=0}
          Resumed activities in task display areas (from top to bottom):
            Resumed: ActivityRecord{14724301 u0 eu.kanade.tachiyomi.sy/eu.kanade.tachiyomi.ui.reader.ReaderActivity t35707}
        ActivityTaskSupervisor state:
          topDisplayFocusedRootTask=Task{6a511bf #35713 type=standard A=10354:io.github.joe2643.glasspad}
              * Task{6a511bf #35713 type=standard A=10354:io.github.joe2643.glasspad U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    """.trimIndent()

    /** Same display with the apps in the desk: the desk root task, then its freeform children. */
    private val windowsInDesk = """
        Display #7 (activities from top to bottom):
          * Task{b478c3f #35703 name=Desk type=undefined U=0 visible=true visibleRequested=true mode=freeform translucent=false sz=2}
            * Task{2922ea9 #35708 type=standard A=10293:com.whatsapp U=0 rootTaskId=35703 visible=true visibleRequested=true mode=freeform translucent=false sz=1}
            * Task{c317c53 #35707 type=standard A=10488:eu.kanade.tachiyomi.sy U=0 rootTaskId=35703 visible=true visibleRequested=true mode=freeform translucent=false sz=1}
          * Task{d96707e #35705 type=standard A=10241:com.android.wm.shell.desktop_wallpaper U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    """.trimIndent()

    @Test fun topVisibleAppOnTheDisplay() {
        assertEquals(TaskRef(35707, "fullscreen"), ActivityDump.topAppTask(fullscreenOnGlasses, 7))
    }

    @Test fun topmostDeskWindowIsTheOneInFront() {
        assertEquals(TaskRef(35708, "freeform"), ActivityDump.topAppTask(windowsInDesk, 7))
    }

    @Test fun otherDisplaysAndLaterSectionsAreIgnored() {
        // Display #0's GlassPad, and the supervisor section's re-listing of it, are never picked.
        assertEquals(TaskRef(35713, "fullscreen"), ActivityDump.topAppTask(fullscreenOnGlasses, 0))
        val noVisibleApp = fullscreenOnGlasses.replace(
            "A=10488:eu.kanade.tachiyomi.sy U=0 visible=true", "A=10488:eu.kanade.tachiyomi.sy U=0 visible=false",
        )
        assertNull(ActivityDump.topAppTask(noVisibleApp, 7))
    }

    @Test fun blankLineAfterTheHeaderIsSkipped() {
        // How the dump reads when GlassPad itself requests it (seen on device): a blank line follows
        // the display header.
        val withBlank = fullscreenOnGlasses.replace(
            "Display #7 (activities from top to bottom):\n", "Display #7 (activities from top to bottom):\n\n",
        )
        assertEquals(TaskRef(35707, "fullscreen"), ActivityDump.topAppTask(withBlank, 7))
    }

    @Test fun unknownDisplayHasNoTask() {
        assertNull(ActivityDump.topAppTask(fullscreenOnGlasses, 3))
    }
}
