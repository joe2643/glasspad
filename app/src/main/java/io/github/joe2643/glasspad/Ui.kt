package io.github.joe2643.glasspad

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity

/** GlassPad's palette and drawable helpers. The whole UI is built in code, so they live here. */
object Ui {
    val BG = 0xFF0A0D12.toInt()
    val PAD = 0xFF10151C.toInt()
    val PAD_BORDER = 0xFF1D2530.toInt()
    val SHEET = 0xFF121820.toInt()
    val KEY = 0xFF262E39.toInt()
    val KEY_SPECIAL = 0xFF1B222B.toInt()
    val PREVIEW = 0xFF36414F.toInt()
    val TEXT = 0xFFE6EDF3.toInt()
    val MUTED = 0xFF8B96A3.toInt()
    val FAINT = 0xFF4A5462.toInt()
    val ACCENT = 0xFF5CC8FF.toInt()
    val ON_ACCENT = 0xFF04121C.toInt()
    const val ACCENT_SOFT = 0x335CC8FF
    val WARN = 0xFFE3B341.toInt()
    const val WARN_SOFT = 0x33E3B341
    val GOOD = 0xFF3FB950.toInt()
    const val RIPPLE = 0x33FFFFFF
    val SCRIM = 0xE6080B10.toInt()

    fun tint(color: Int): ColorStateList = ColorStateList.valueOf(color)

    fun rounded(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    /** Rounded on top only, like a bottom sheet. */
    fun sheet(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
    }

    fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** A rounded surface that ripples when pressed. */
    fun pressable(color: Int, radius: Float): Drawable =
        RippleDrawable(tint(RIPPLE), rounded(color, radius), rounded(Color.WHITE, radius))

    /** An unbounded ripple, for icon buttons; [fill] gives the icon a soft round backdrop. */
    fun borderless(fill: Int? = null): Drawable =
        RippleDrawable(tint(RIPPLE), fill?.let { oval(it) }, null)

    /** A pill whose [ClipDrawable] layer fills left to right as its level goes 0 → 10 000. */
    fun holdFill(base: Int, fill: Int, radius: Float): Pair<Drawable, ClipDrawable> {
        val clip = ClipDrawable(rounded(fill, radius), Gravity.START, ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf(rounded(base, radius), clip)) to clip
    }
}
