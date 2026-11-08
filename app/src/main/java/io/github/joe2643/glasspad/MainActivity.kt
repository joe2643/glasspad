package io.github.joe2643.glasspad

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.github.joe2643.glasspad.Ui.tint
import io.github.joe2643.glasspad.core.DesktopScale
import io.github.joe2643.glasspad.core.DisplayInfo

/**
 * The phone UI: a header (status + panel toggles), the trackpad card that drives
 * [GlassPadA11yService] (cursor + gestures on the external display), and a bottom sheet holding the
 * optional keyboard / desktop-scale panel above the always-on navigation row.
 *
 * Keyboard: on-screen keys (Buttons, which never take input focus, so the desktop field you clicked
 * stays selected) that edit that focused field's text via the accessibility service.
 */
class MainActivity : Activity() {

    private lateinit var touchpad: TouchpadView
    private lateinit var setupCard: View
    private lateinit var statusText: TextView
    private lateinit var statusDot: GradientDrawable
    private lateinit var scaleButton: ImageButton
    private lateinit var kbButton: ImageButton
    private lateinit var lockButton: ImageButton
    private lateinit var lockOverlay: FrameLayout
    private lateinit var holdLevel: ClipDrawable
    private lateinit var keyboardPanel: LinearLayout
    private lateinit var kbStatus: TextView
    private lateinit var header: LinearLayout
    private lateinit var rowsContainer: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var keyPreview: TextView
    private lateinit var bottomSheet: LinearLayout
    private lateinit var scalePanel: LinearLayout
    private lateinit var scaleValue: TextView
    private lateinit var scaleStatus: TextView
    private lateinit var scaleSeek: SeekBar
    private val scaleControls = mutableListOf<View>()
    private var external: DisplayInfo? = null

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = Runnable { pollForService() }
    private var unlockHold: Runnable? = null
    private var holdAnim: ValueAnimator? = null
    private var wiredService: GlassPadA11yService? = null
    private var shiftOn = false
    private var page = LETTERS // LETTERS, SYMBOLS, or MORE_SYMBOLS
    private var shiftKey: Button? = null

    private val service get() = GlassPadA11yService.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
    }

    /**
     * Keep the desktop app focused while you use the trackpad, without ever leaving the system
     * with no focused window.
     *
     * A touch on a focusable window moves the system's top-focused display to the phone, which
     * un-focuses the app on the external display. A non-focusable window avoids that — but if the
     * phone is the top-focused display while GlassPad (its top activity) can't take focus, there is
     * no focused window at all: accessibility then won't compute windows (every
     * getWindowsOnAllDisplays waits 5 s per display) and key events to the phone ANR. Measured on a
     * Pixel 9 Pro XL, Android 17: 10 003 ms vs 7 ms.
     *
     * So GlassPad is focusable exactly while it's the top resumed activity (the phone is
     * top-focused), and stops being focusable once focus moves to the desktop — e.g. on your first
     * click there — so later trackpad touches leave it there.
     */
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity) window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(pollRunnable)
        pollForService()
        service?.probeWindows()
    }

    override fun onPause() {
        super.onPause()
        wiredService?.onState = null
        wiredService = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun pollForService() {
        val svc = service
        if (svc !== wiredService) {
            wiredService?.onState = null
            wiredService = svc
            if (svc != null) {
                touchpad.onPadFrame = { service?.submitPadFrame(it) }
                svc.onState = { renderState(it) }
            }
        }
        if (svc == null) {
            setStatus("Accessibility is off — tap to turn on", Ui.WARN, textColor = Ui.WARN)
            setNeedsSetup(true)
        }
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    /** The setup card covers the trackpad until the accessibility service is on. */
    private fun setNeedsSetup(needed: Boolean) {
        setupCard.visibility = if (needed) View.VISIBLE else View.GONE
        touchpad.guideVisible = !needed
    }

    private fun renderState(s: GlassPadA11yService.State) {
        setNeedsSetup(false)
        val ext = s.external
        if (ext == null) setStatus("Connect a display in Desktop mode", Ui.WARN)
        else setStatus("Ready · ${ext.width}×${ext.height}", Ui.GOOD)
        lockButton.setImageResource(if (s.locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        lockButton.imageTintList = tint(if (s.locked) Ui.WARN else Ui.MUTED)
        lockButton.contentDescription = if (s.locked) "Touchpad locked" else "Lock touchpad"
        lockOverlay.visibility = if (s.locked) View.VISIBLE else View.GONE
        if (!s.locked) resetHold()
        external = ext
        if (scalePanel.visibility == View.VISIBLE) refreshScale()
    }

    private fun setStatus(text: String, color: Int, textColor: Int = Ui.MUTED) {
        statusText.text = text
        statusText.setTextColor(textColor)
        statusDot.setColor(color)
    }

    private fun buildSetupCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(28), dp(24), dp(22))
        background = Ui.rounded(Ui.SHEET, dp(22).toFloat(), dp(1), Ui.WARN_SOFT)
        elevation = dp(4).toFloat()
        isClickable = true // touches on the card never reach the trackpad behind it
        visibility = View.GONE
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_accessibility)
            imageTintList = tint(Ui.WARN)
            background = Ui.oval(Ui.WARN_SOFT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(15), dp(15), dp(15), dp(15))
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(TextView(this@MainActivity).apply {
            text = "Turn on GlassPad"; setTextColor(Ui.TEXT); textSize = 21f; typeface = MEDIUM
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0)
        })
        addView(TextView(this@MainActivity).apply {
            text = "GlassPad moves the cursor, clicks and types on your display through an " +
                "Accessibility service. Turn it on once — nothing leaves your phone."
            setTextColor(Ui.MUTED); textSize = 14f; gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f); setPadding(0, dp(8), 0, 0)
        })
        addView(Button(this@MainActivity).apply {
            text = "Open Accessibility settings"; setTextColor(Ui.ON_ACCENT); textSize = 15f
            typeface = MEDIUM; isAllCaps = false; stateListAnimator = null
            background = Ui.pressable(Ui.ACCENT, dp(26).toFloat())
            setOnClickListener { v -> hapticTap(v); openAccessibilitySettings() }
        }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(22) })
        addView(TextView(this@MainActivity).apply {
            text = "Then choose GlassPad and switch it on."
            setTextColor(Ui.MUTED); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        addView(TextView(this@MainActivity).apply {
            text = "Switch greyed out? Settings › Apps › GlassPad › ⋮ › Allow restricted settings"
            setTextColor(Ui.FAINT); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
    }

    /** Runs [block] with the accessibility service, or says why nothing happened. */
    private fun withService(block: (GlassPadA11yService) -> Unit) {
        val svc = service
        if (svc == null) needService() else block(svc)
    }

    private fun needService() {
        Toast.makeText(this, "Turn on GlassPad in Accessibility first", Toast.LENGTH_SHORT).show()
    }

    private fun openAccessibilitySettings() {
        if (service == null) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Turn on “GlassPad”, then come back", Toast.LENGTH_LONG).show()
        }
    }

    // ---- panels (keyboard / desktop scale share the bottom sheet) ----

    /** Shows [panel] (or none) and lights up its header button. */
    private fun showPanel(panel: View?) {
        for ((p, button) in listOf(keyboardPanel to kbButton, scalePanel to scaleButton)) {
            val on = p === panel
            p.visibility = if (on) View.VISIBLE else View.GONE
            button.imageTintList = tint(if (on) Ui.ACCENT else Ui.MUTED)
            button.background = Ui.borderless(if (on) Ui.ACCENT_SOFT else null)
        }
    }

    private fun toggleKeyboard() {
        val show = keyboardPanel.visibility != View.VISIBLE
        showPanel(if (show) keyboardPanel else null)
        if (show) refreshKbStatus()
    }

    private fun toggleScale() {
        val show = scalePanel.visibility != View.VISIBLE
        showPanel(if (show) scalePanel else null)
        if (show) refreshScale()
    }

    // ---- desktop scale (external display density) ----

    private fun currentDpi(displayId: Int): Int? =
        getSystemService(DisplayManager::class.java).getDisplay(displayId)
            ?.let { createDisplayContext(it).resources.displayMetrics.densityDpi }

    private fun refreshScale() {
        val ext = external
        val dpi = ext?.let { currentDpi(it.id) }
        when {
            ext == null || dpi == null -> setScale("", "Connect a display first", Ui.MUTED, enabled = false)
            !DisplayDensity.isGranted(this) -> setScale(
                "", "One-time setup, from a PC:\n${DisplayDensity.GRANT_COMMAND}", Ui.WARN,
                enabled = false, mono = true,
            )
            else -> {
                scaleSeek.progress = DesktopScale.positionOf(dpi)
                setScale("$dpi dpi", describeScale(ext, dpi), Ui.MUTED, enabled = true)
            }
        }
    }

    private fun describeScale(ext: DisplayInfo, dpi: Int): String {
        val (w, h) = DesktopScale.workspaceDp(ext.width, ext.height, dpi)
        return "Desktop ${w}×$h dp · smaller fits more"
    }

    private fun setScale(value: String, text: String, color: Int, enabled: Boolean, mono: Boolean = false) {
        scaleValue.text = value
        scaleStatus.text = text
        scaleStatus.setTextColor(color)
        scaleStatus.typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
        scaleControls.forEach { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.4f }
    }

    private fun applyScale(dpi: Int?) {
        val ext = external ?: return
        scaleStatus.text = if (dpi == null) "Resetting…" else "Applying $dpi dpi…"
        Thread {
            val result = if (dpi == null) DisplayDensity.reset(ext.id) else DisplayDensity.set(ext.id, dpi)
            runOnUiThread {
                val error = result.exceptionOrNull()
                if (error == null) refreshScale()
                else setScale("", "Failed: ${rootCause(error)}", Ui.WARN, enabled = true)
            }
        }.start()
    }

    private fun stepScale(delta: Int) {
        val p = (scaleSeek.progress + delta).coerceIn(0, DesktopScale.positions - 1)
        scaleSeek.progress = p
        applyScale(DesktopScale.dpiAt(p))
    }

    private fun rootCause(t: Throwable): String {
        var c = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    private fun buildScalePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            visibility = View.GONE
        }
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Desktop scale"; setTextColor(Ui.TEXT); textSize = 15f; typeface = MEDIUM
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            scaleValue = TextView(this@MainActivity).apply {
                setTextColor(Ui.ACCENT); textSize = 15f; typeface = MEDIUM
            }
            addView(scaleValue)
        })
        scaleStatus = TextView(this).apply {
            setTextColor(Ui.MUTED); textSize = 12f
            setPadding(0, dp(4), 0, dp(4))
        }
        panel.addView(scaleStatus)
        scaleSeek = SeekBar(this).apply {
            max = DesktopScale.positions - 1
            progressTintList = tint(Ui.ACCENT)
            thumbTintList = tint(Ui.ACCENT)
            progressBackgroundTintList = tint(Ui.FAINT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    // Preview only: applying relayouts the whole desktop, so wait for release.
                    val ext = external
                    if (fromUser && ext != null) {
                        val dpi = DesktopScale.dpiAt(p)
                        scaleValue.text = "$dpi dpi"
                        scaleStatus.text = describeScale(ext, dpi)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) = applyScale(DesktopScale.dpiAt(sb.progress))
            })
        }
        panel.addView(scaleSeek, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); bottomMargin = dp(6) })
        val buttons = row {
            addView(chip("Smaller") { stepScale(-1) })
            addView(chip("Bigger") { stepScale(+1) })
            addView(chip("Reset") { applyScale(null) })
        }
        panel.addView(buttons)
        scaleControls += scaleSeek
        for (i in 0 until buttons.childCount) scaleControls += buttons.getChildAt(i)
        return panel
    }

    // ---- navigation (the desktop taskbar's back / home / recents, plus fullscreen) ----

    private fun buildNavBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), dp(2), dp(8), dp(2))
        for ((icon, desc, onClick) in listOf<Triple<Int, String, () -> Unit>>(
            Triple(R.drawable.ic_back, "Back", { withService { it.navigate(GlassPadA11yService.NavButton.BACK) } }),
            Triple(R.drawable.ic_home, "Home", { withService { it.navigate(GlassPadA11yService.NavButton.HOME) } }),
            Triple(R.drawable.ic_recents, "Recent apps", { withService { it.navigate(GlassPadA11yService.NavButton.RECENTS) } }),
            Triple(R.drawable.ic_fullscreen, "Fullscreen", { toggleFullscreen() }),
        )) {
            addView(iconButton(icon, desc) { onClick() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
    }

    private fun toggleFullscreen() {
        val svc = service ?: return needService()
        Log.d("GlassPadShell", "fullscreen pressed")
        if (!DesktopShell.isGranted(this)) {
            Toast.makeText(this, "Fullscreen needs a one-time grant from a PC:\n" +
                DesktopShell.GRANT_COMMANDS.joinToString("\n"), Toast.LENGTH_LONG).show()
            return
        }
        svc.toggleFullscreen { result ->
            result.exceptionOrNull()?.let {
                Toast.makeText(this, "Fullscreen failed: ${rootCause(it)}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- keyboard ----

    private fun locked() = service?.isLocked == true

    private fun onKey(c: Char) {
        if (locked()) return
        val ch = if (shiftOn && c in 'a'..'z') c.uppercaseChar() else c
        service?.typeText(ch.toString())
        if (shiftOn) { shiftOn = false; refreshShift() }
        refreshKbStatus()
    }

    /** Shows what the accessibility service can actually see — the key on-device signal. */
    private fun refreshKbStatus() {
        if (!::kbStatus.isInitialized) return
        val svc = service
        if (svc == null) { setKb("turn on GlassPad in Accessibility first", Ui.WARN); return }
        svc.keyboardDiagnostics { diag -> setKb(diag, if (diag.startsWith("●")) Ui.GOOD else Ui.WARN) }
    }

    private fun setKb(text: String, color: Int) {
        kbStatus.text = text; kbStatus.setTextColor(color)
    }

    private fun buildKeyboardPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(10), dp(5), dp(4))
            visibility = View.GONE
        }
        kbStatus = TextView(this).apply {
            text = "click a desktop text field first"
            setTextColor(Ui.MUTED); textSize = 11f
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        panel.addView(kbStatus)
        panel.addView(actionsRow())
        rowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(rowsContainer)
        populateRows()
        return panel
    }

    private fun populateRows() {
        rowsContainer.removeAllViews()
        letterKeys.clear()
        shiftKey = null
        when (page) {
            SYMBOLS -> {
                rowsContainer.addView(keyRow("1234567890"))
                rowsContainer.addView(keyRow("@#\$_&-+()/"))
                rowsContainer.addView(row {
                    addView(specialKey("=\\<", 1.5f) { page = MORE_SYMBOLS; populateRows() })
                    for (c in "*\"':;!?") addView(charKey(c))
                    addView(backspaceKey())
                })
            }
            MORE_SYMBOLS -> {
                rowsContainer.addView(keyRow("~`|•√π÷×¶∆"))
                rowsContainer.addView(keyRow("£¢€¥^°={}"))
                rowsContainer.addView(row {
                    addView(specialKey("?123", 1.5f) { page = SYMBOLS; populateRows() })
                    for (c in "\\©®™℅[]") addView(charKey(c))
                    addView(backspaceKey())
                })
            }
            else -> { // LETTERS — Gboard's default layout (no persistent number row)
                rowsContainer.addView(keyRow("qwertyuiop"))
                rowsContainer.addView(centeredKeyRow("asdfghjkl"))
                rowsContainer.addView(row {
                    shiftKey = specialKey("⇧", 1.5f, glyph = true) { shiftOn = !shiftOn; refreshShift() }
                    addView(shiftKey)
                    for (c in "zxcvbnm") addView(charKey(c))
                    addView(backspaceKey())
                })
            }
        }
        rowsContainer.addView(bottomRow())
        refreshShift()
    }

    /** Gboard-style bottom row: mode-switch · comma · wide space · period · enter. */
    private fun bottomRow() = row {
        if (page == LETTERS) {
            addView(specialKey("?123", 1.5f) { page = SYMBOLS; shiftOn = false; populateRows() })
        } else {
            addView(specialKey("ABC", 1.5f) { page = LETTERS; populateRows() })
        }
        addView(charKey(if (page == MORE_SYMBOLS) '<' else ','))
        addView(key("space", 5f, Ui.KEY, Ui.MUTED, 13f) { onKey(' ') })
        addView(charKey(if (page == MORE_SYMBOLS) '>' else '.'))
        addView(key("⏎", 1.5f, Ui.ACCENT, Ui.ON_ACCENT, 18f) { if (!locked()) service?.enter() })
    }

    private fun backspaceKey() = specialKey("⌫", 1.5f, glyph = true) { if (!locked()) service?.backspace() }

    /** Clipboard shortcuts above the keys, like Gboard's suggestion strip. */
    private fun actionsRow() = row {
        addView(chip("Copy") { if (!locked()) service?.editAction(AccessibilityNodeInfo.ACTION_COPY) })
        addView(chip("Paste") { if (!locked()) service?.editAction(AccessibilityNodeInfo.ACTION_PASTE) })
        addView(chip("Cut") { if (!locked()) service?.editAction(AccessibilityNodeInfo.ACTION_CUT) })
        addView(chip("Select all") { if (!locked()) service?.selectAll() })
    }

    private val letterKeys = mutableListOf<Button>()

    private fun keyRow(chars: String) = row { for (c in chars) addView(charKey(c)) }

    /** Like [keyRow] but inset by half a key on each side, so a 9-key row centers under a 10-key one. */
    private fun centeredKeyRow(chars: String) = row {
        addView(spacer(0.5f))
        for (c in chars) addView(charKey(c))
        addView(spacer(0.5f))
    }

    // Space (not a bare View) shrink-wraps to zero height; a plain View would expand to fill the
    // row's available height and stretch the whole panel.
    private fun spacer(weight: Float) = android.widget.Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, WRAP, weight)
    }

    private fun row(block: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
        block()
    }

    /** A flat rounded key that ripples; the base of every key, chip and pill here. */
    private fun key(
        label: String, weight: Float, color: Int, textColor: Int, size: Float,
        height: Int = KEY_HEIGHT, onClick: () -> Unit,
    ) = Button(this).apply {
        text = label; textSize = size; setTextColor(textColor); isAllCaps = false
        stateListAnimator = null
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(0, 0, 0, 0)
        background = Ui.pressable(color, dp(8).toFloat())
        layoutParams = LinearLayout.LayoutParams(0, dp(height), weight).apply {
            marginStart = dp(3); marginEnd = dp(3)
        }
        setOnClickListener { v -> hapticTap(v); onClick() }
    }

    private fun charKey(c: Char): Button {
        val b = key(c.toString(), 1f, Ui.KEY, Ui.TEXT, 19f) { onKey(c) }.apply {
            // Gboard-style feedback: haptic + a magnified character bubble while the key is held.
            // (The click listener's own haptic is replaced: it fires on DOWN here.)
            setOnClickListener { onKey(c) }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { hapticTap(v); showKeyPreview(v, displayLabel(c)) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> hideKeyPreview()
                }
                false // don't consume — let the button fire its click (the actual keystroke)
            }
        }
        if (c in 'a'..'z') letterKeys.add(b)
        return b
    }

    /** The glyph shown in the preview bubble — uppercased while shift is engaged. */
    private fun displayLabel(c: Char) =
        if (shiftOn && c in 'a'..'z') c.uppercaseChar().toString() else c.toString()

    private fun specialKey(label: String, weight: Float, glyph: Boolean = false, onClick: () -> Unit) =
        key(label, weight, Ui.KEY_SPECIAL, Ui.MUTED, if (glyph) 19f else 14f, onClick = onClick)

    private fun chip(label: String, onClick: () -> Unit) =
        key(label, 1f, Ui.KEY_SPECIAL, Ui.MUTED, 13f, height = CHIP_HEIGHT, onClick = onClick)

    private fun hapticTap(v: View) {
        v.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
        )
    }

    // ---- key-preview bubble (Gboard-style) ----

    private fun makeKeyPreview() = TextView(this).apply {
        setTextColor(Ui.TEXT); textSize = 28f; isAllCaps = false; gravity = Gravity.CENTER
        minWidth = dp(52)
        setPadding(dp(14), dp(6), dp(14), dp(10))
        visibility = View.GONE
        elevation = dp(8).toFloat()
        background = Ui.rounded(Ui.PREVIEW, dp(12).toFloat())
        layoutParams = FrameLayout.LayoutParams(WRAP, WRAP)
    }

    private fun showKeyPreview(anchor: View, label: String) {
        keyPreview.text = label
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        keyPreview.measure(spec, spec)
        val a = IntArray(2); anchor.getLocationOnScreen(a)
        val r = IntArray(2); root.getLocationOnScreen(r)
        (keyPreview.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (a[0] - r[0] + anchor.width / 2 - keyPreview.measuredWidth / 2).coerceAtLeast(dp(2))
            topMargin = (a[1] - r[1] - keyPreview.measuredHeight - dp(6)).coerceAtLeast(dp(2))
        }
        keyPreview.requestLayout()
        keyPreview.visibility = View.VISIBLE
    }

    private fun hideKeyPreview() {
        if (::keyPreview.isInitialized) keyPreview.visibility = View.GONE
    }

    private fun refreshShift() {
        letterKeys.forEach { it.text = if (shiftOn) it.text.toString().uppercase() else it.text.toString().lowercase() }
        // Lit key when shift is engaged — the Gboard cue.
        shiftKey?.apply {
            text = if (shiftOn) "⬆" else "⇧"
            setTextColor(if (shiftOn) Ui.ACCENT else Ui.MUTED)
            background = Ui.pressable(if (shiftOn) Ui.ACCENT_SOFT else Ui.KEY_SPECIAL, dp(8).toFloat())
        }
    }

    // ---- UI scaffold ----

    private fun buildUi(): View {
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }

        header = buildHeader()
        touchpad = TouchpadView(this).apply {
            background = Ui.rounded(Ui.PAD, dp(24).toFloat(), dp(1), Ui.PAD_BORDER)
        }
        setupCard = buildSetupCard()
        val padArea = FrameLayout(this).apply {
            addView(touchpad, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(setupCard, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply {
                marginStart = dp(14); marginEnd = dp(14)
            })
        }

        // Bottom sheet: optional keyboard / scale panel above an always-on nav row.
        keyboardPanel = buildKeyboardPanel()
        scalePanel = buildScalePanel()
        bottomSheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.sheet(Ui.SHEET, dp(20).toFloat())
            addView(keyboardPanel)
            addView(scalePanel)
            addView(buildNavBar())
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(padArea, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(12)
            })
            addView(bottomSheet, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        // Above everything else so the bubble is never clipped; below the lock overlay.
        keyPreview = makeKeyPreview()
        root.addView(keyPreview)

        lockOverlay = buildLockOverlay()
        root.addView(lockOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        // Inset the header below the status bar / cutout, and the bottom sheet above the
        // navigation bar (3-button or gesture), so its bottom row is never hidden.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = insets.getInsets(WindowInsets.Type.statusBars()).top
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            header.setPadding(dp(18), top + dp(10), dp(8), dp(12))
            bottomSheet.setPadding(0, 0, 0, nav)
            insets
        }
        return root
    }

    private fun buildHeader(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { openAccessibilitySettings() }
        }
        titleBlock.addView(TextView(this).apply {
            text = "GlassPad"; setTextColor(Ui.TEXT); textSize = 20f; typeface = MEDIUM
        })
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        statusDot = Ui.oval(Ui.WARN)
        statusRow.addView(View(this).apply { background = statusDot },
            LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(7) })
        statusText = TextView(this).apply {
            text = "Starting…"; setTextColor(Ui.MUTED); textSize = 13f; maxLines = 1
        }
        statusRow.addView(statusText)
        titleBlock.addView(statusRow)
        bar.addView(titleBlock, LinearLayout.LayoutParams(0, WRAP, 1f))

        scaleButton = iconButton(R.drawable.ic_monitor, "Desktop scale") { toggleScale() }
        kbButton = iconButton(R.drawable.ic_keyboard, "Keyboard") { toggleKeyboard() }
        lockButton = iconButton(R.drawable.ic_lock_open, "Lock touchpad") { withService { it.setLocked(true) } }
        for (b in listOf(scaleButton, kbButton, lockButton)) {
            bar.addView(b, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        return bar
    }

    private fun buildLockOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Ui.SCRIM); visibility = View.GONE; isClickable = true
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_lock); imageTintList = tint(Ui.WARN)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        col.addView(TextView(this).apply {
            text = "Touchpad locked"; setTextColor(Ui.TEXT); textSize = 20f; typeface = MEDIUM
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = "Nothing reaches the desktop until you unlock"
            setTextColor(Ui.MUTED); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(28))
        })
        val (bg, level) = Ui.holdFill(Ui.KEY, Ui.ACCENT_SOFT, dp(28).toFloat())
        holdLevel = level
        col.addView(Button(this).apply {
            text = "Hold to unlock"; setTextColor(Ui.TEXT); textSize = 15f; isAllCaps = false
            stateListAnimator = null
            background = bg
            setPadding(dp(36), 0, dp(36), 0)
            setOnTouchListener { v, e -> onUnlockTouch(v, e); true }
        }, LinearLayout.LayoutParams(WRAP, dp(56)))
        overlay.addView(col, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return overlay
    }

    /** Hold for [UNLOCK_HOLD_MS] to unlock; the pill fills as you hold. */
    private fun onUnlockTouch(v: View, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hapticTap(v)
                holdAnim = ValueAnimator.ofInt(0, 10_000).apply {
                    duration = UNLOCK_HOLD_MS
                    addUpdateListener { holdLevel.level = it.animatedValue as Int }
                    start()
                }
                unlockHold = Runnable {
                    v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    service?.setLocked(false)
                }
                handler.postDelayed(unlockHold!!, UNLOCK_HOLD_MS)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                unlockHold?.let { handler.removeCallbacks(it) }
                resetHold()
            }
        }
    }

    private fun resetHold() {
        holdAnim?.cancel(); holdAnim = null
        if (::holdLevel.isInitialized) holdLevel.level = 0
    }

    private fun iconButton(icon: Int, description: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = tint(Ui.MUTED)
        background = Ui.borderless()
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        setOnClickListener { v -> hapticTap(v); onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val UNLOCK_HOLD_MS = 600L
        const val POLL_MS = 1500L
        const val LETTERS = 0
        const val SYMBOLS = 1
        const val MORE_SYMBOLS = 2
        const val KEY_HEIGHT = 48
        const val CHIP_HEIGHT = 36
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
