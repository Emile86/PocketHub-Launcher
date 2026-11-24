package com.pockethub.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.Toast

// Sources live directly in src/main/java (Kotlin convention: omit the common root package from folders).

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

/** PocketHub-style colors. */
object Palette {
    const val ACCENT = 0xFF2CFFB1.toInt()
    const val PANEL = 0xF2101C26.toInt()
    const val PANEL_STROKE = 0x402CFFB1
    const val TEXT = 0xFFF1FFF9.toInt()
    const val TEXT_DIM = 0xB3F1FFF9.toInt()
    const val SCRIM = 0x99000000.toInt()
    const val DRAWER_BG = 0xEB070D12.toInt()
    const val RIPPLE = 0x332CFFB1
    const val FOCUS = 0x4D2CFFB1
}

fun roundedBg(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

private fun copyOf(d: Drawable): Drawable = d.constantState?.newDrawable()?.mutate() ?: d

private fun tinted(d: Drawable, color: Int): Drawable = copyOf(d).apply { setTint(color) }

/**
 * Invisible background: the mask shape only bounds the press ripple.
 * When focused (controller) — a teal highlight of the same shape.
 */
fun rippleBg(mask: Drawable = ColorDrawable(Color.WHITE)): Drawable = StateListDrawable().apply {
    val ripple = ColorStateList.valueOf(Palette.RIPPLE)
    addState(intArrayOf(android.R.attr.state_focused), RippleDrawable(ripple, tinted(mask, Palette.FOCUS), copyOf(mask)))
    addState(intArrayOf(), RippleDrawable(ripple, null, copyOf(mask)))
}

/** Visible content background with a press ripple; lighter when focused. */
fun pressBg(content: Drawable): Drawable = StateListDrawable().apply {
    val ripple = ColorStateList.valueOf(Palette.RIPPLE)
    addState(intArrayOf(android.R.attr.state_focused), LayerDrawable(arrayOf(
        RippleDrawable(ripple, copyOf(content), null),
        tinted(content, 0x40FFFFFF),
    )))
    addState(intArrayOf(), RippleDrawable(ripple, copyOf(content), null))
}

// ---------- modern settings style ----------

object Look {
    const val GRAD_A = 0xFF12C99B.toInt()   // teal
    const val GRAD_B = 0xFF3B7BF6.toInt()   // blue
    const val BG_TOP = 0xFF070B0F.toInt()
    const val BG_BOTTOM = 0xFF020304.toInt()
    const val CARD_TOP = 0xFF0E141A.toInt() // card gradient
    const val CARD_BOTTOM = 0xFF0A0E12.toInt()
    const val CARD_STROKE = 0x14FFFFFF
    const val TRACK = 0xFF06090C.toInt()    // segmented control / stepper track
    const val TRACK_STROKE = 0x17FFFFFF
    const val GLASS = 0x17FFFFFF            // glass (app list bar, etc.)
    const val GLASS_STROKE = 0x24FFFFFF
    const val GLASS_HI = 0x1FFFFFFF
    const val TEXT_1 = 0xFFE9EFF3.toInt()
    const val TEXT_2 = 0xFF8C98A2.toInt()
    const val TEXT_3 = 0xFFA9B4BC.toInt()
    const val SUB = 0xFF6FE3C2.toInt()      // sub-headers inside cards
    const val WARN_A = 0xFFF08C00.toInt()
    const val WARN_B = 0xFFFFA94D.toInt()
    const val WARN_TEXT = 0xFFFFB86B.toInt()
}

fun gradientBg(radius: Float, a: Int = Look.GRAD_A, b: Int = Look.GRAD_B): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(a, b)).apply { cornerRadius = radius }

/** PocketHub-style background: dark gradient with soft colored glows. */
class AuroraDrawable : Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, h,
            Look.BG_TOP, Look.BG_BOTTOM, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
        // Very soft glows: the background stays almost black.
        glow(canvas, w * 0.10f, h * 0.02f, w * 0.70f, 0x2212C99B)
        glow(canvas, w * 0.95f, h * 0.30f, w * 0.60f, 0x1A3B7BF6)
        glow(canvas, w * 0.35f, h * 1.00f, w * 0.65f, 0x147C5CFF)
        paint.shader = null
    }

    private fun glow(c: android.graphics.Canvas, x: Float, y: Float, r: Float, color: Int) {
        paint.shader = android.graphics.RadialGradient(x, y, r, color, color and 0x00FFFFFF,
            android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}

fun accentSwitchTints(sw: android.widget.Switch) {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    sw.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, 0xFFB7C4CC.toInt()))
    sw.trackTintList = ColorStateList(states, intArrayOf(Look.GRAD_A, 0x33FFFFFF))
}

/** Copy of a key event with another key code (gamepad buttons → standard keys). */
fun remapKey(e: android.view.KeyEvent, code: Int) = android.view.KeyEvent(
    e.downTime, e.eventTime, e.action, code, e.repeatCount, e.metaState,
    e.deviceId, e.scanCode, e.flags, e.source,
)
