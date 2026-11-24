package com.pockethub.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.min

/**
 * Animated drawing of the AYANEO Pocket DS (open clamshell: big top screen, small bottom screen
 * between the controls) showing a bottom screen gesture with a touch point:
 *  - HANDLE: hold the top center → three dots appear → tap them → a small menu opens;
 *  - SWIPE: swipe up from the bottom edge → the app shrinks away (minimize).
 */
class DeviceIllustrationView(ctx: Context, private val mode: Mode) : View(ctx) {
    enum class Mode { HANDLE, SWIPE }

    private val d = ctx.resources.displayMetrics.density
    private val start = SystemClock.uptimeMillis()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val r = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // Device proportions: about 1 : 0.95 (lid + base).
        setMeasuredDimension(w, (w * 0.95f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = w * 0.04f

        // ---- lid with the big top screen ----
        val lid = RectF(pad + w * 0.03f, pad, w - pad - w * 0.03f, h * 0.46f)
        body(c, lid)
        val topScreen = RectF(lid.left + w * 0.035f, lid.top + w * 0.035f, lid.right - w * 0.035f, lid.bottom - w * 0.035f)
        screen(c, topScreen, dim = true)
        fill.color = 0xFF2A323C.toInt()
        c.drawCircle(lid.centerX(), lid.top + w * 0.018f, w * 0.006f, fill) // camera

        // ---- hinge ----
        fill.color = 0xFF151A20.toInt()
        r.set(lid.left + w * 0.08f, lid.bottom, lid.right - w * 0.08f, lid.bottom + h * 0.025f)
        c.drawRoundRect(r, w * 0.01f, w * 0.01f, fill)

        // ---- base with controls and the small bottom screen ----
        val base = RectF(pad, lid.bottom + h * 0.025f, w - pad, h - pad)
        body(c, base)
        val bs = RectF(base.left + base.width() * 0.29f, base.top + base.height() * 0.12f,
            base.right - base.width() * 0.29f, base.bottom - base.height() * 0.14f)
        val leftCx = base.left + base.width() * 0.145f
        val rightCx = base.right - base.width() * 0.145f
        // left: stick above the D-pad
        stick(c, leftCx, base.top + base.height() * 0.3f, w * 0.045f)
        dpad(c, leftCx, base.top + base.height() * 0.68f, w * 0.05f)
        // right: ABXY above the stick
        abxy(c, rightCx, base.top + base.height() * 0.3f, w * 0.05f)
        stick(c, rightCx, base.top + base.height() * 0.68f, w * 0.045f)

        // bottom screen: dark with an app mock, highlighted
        val app = when (mode) {
            Mode.SWIPE -> {
                // the app shrinks toward the bottom while the swipe ends
                val p = (t % 2.4f)
                if (p in 1.0f..1.6f) 1f - (p - 1.0f) / 0.6f * 0.6f else if (p > 1.6f) 0.4f else 1f
            }
            Mode.HANDLE -> 1f
        }
        screen(c, bs, dim = false, appScale = app)
        stroke.color = 0x992CFFB1.toInt()
        stroke.strokeWidth = 1.5f * d
        c.drawRoundRect(bs, w * 0.012f, w * 0.012f, stroke)

        when (mode) {
            Mode.HANDLE -> handle(c, bs, t)
            Mode.SWIPE -> swipe(c, bs, t)
        }
        if (isShown) postInvalidateDelayed(33)
    }

    private fun body(c: Canvas, rect: RectF) {
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, 0xFF232A33.toInt(), 0xFF161B21.toInt(), Shader.TileMode.CLAMP)
        val rad = rect.width() * 0.06f
        c.drawRoundRect(rect, rad, rad, fill)
        fill.shader = null
        stroke.color = 0xFF323B46.toInt()
        stroke.strokeWidth = 1.2f * d
        c.drawRoundRect(rect, rad, rad, stroke)
    }

    private fun screen(c: Canvas, rect: RectF, dim: Boolean, appScale: Float = 1f) {
        val rad = rect.width() * 0.03f
        fill.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            if (dim) 0xFF0B1220.toInt() else 0xFF0E1A24.toInt(), 0xFF070B10.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, rad, rad, fill)
        fill.shader = null
        if (dim) {
            // a few faint icons on the top screen
            fill.color = 0x223FF7FF
            val s = rect.height() * 0.13f
            for (i in 0 until 5) {
                val cx = rect.left + rect.width() * (0.18f + i * 0.16f)
                c.drawCircle(cx, rect.centerY(), s / 2, fill)
            }
            return
        }
        // app mock: header bar + cards, scaled toward the bottom center when minimizing
        c.save()
        c.scale(appScale, appScale, rect.centerX(), rect.bottom)
        fill.color = (((appScale * 0x55).toInt()) shl 24) or 0x3FA7FF
        r.set(rect.left + rect.width() * 0.08f, rect.top + rect.height() * 0.12f,
            rect.right - rect.width() * 0.08f, rect.top + rect.height() * 0.24f)
        c.drawRoundRect(r, 4 * d, 4 * d, fill)
        fill.color = (((appScale * 0x33).toInt()) shl 24) or 0xFFFFFF
        for (i in 0..1) {
            r.set(rect.left + rect.width() * 0.08f, rect.top + rect.height() * (0.32f + i * 0.24f),
                rect.right - rect.width() * 0.08f, rect.top + rect.height() * (0.5f + i * 0.24f))
            c.drawRoundRect(r, 4 * d, 4 * d, fill)
        }
        c.restore()
    }

    private fun stick(c: Canvas, cx: Float, cy: Float, rad: Float) {
        fill.color = 0xFF0E1216.toInt()
        c.drawCircle(cx, cy, rad * 1.25f, fill)
        fill.color = 0xFF2B333D.toInt()
        c.drawCircle(cx, cy, rad, fill)
        stroke.color = 0xFF3A4450.toInt()
        stroke.strokeWidth = 1f * d
        c.drawCircle(cx, cy, rad * 0.6f, stroke)
    }

    private fun dpad(c: Canvas, cx: Float, cy: Float, s: Float) {
        fill.color = 0xFF2B333D.toInt()
        val a = s * 0.38f
        r.set(cx - s, cy - a, cx + s, cy + a); c.drawRoundRect(r, a / 2, a / 2, fill)
        r.set(cx - a, cy - s, cx + a, cy + s); c.drawRoundRect(r, a / 2, a / 2, fill)
    }

    private fun abxy(c: Canvas, cx: Float, cy: Float, s: Float) {
        val br = s * 0.34f
        val colors = intArrayOf(0xFF2B333D.toInt(), 0xFF2B333D.toInt(), 0xFF2B333D.toInt(), 0xFF2B333D.toInt())
        val pts = arrayOf(floatArrayOf(0f, -1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(-1f, 0f))
        for (i in 0..3) {
            fill.color = colors[i]
            c.drawCircle(cx + pts[i][0] * s * 0.7f, cy + pts[i][1] * s * 0.7f, br, fill)
        }
    }

    /** Touch point: soft white dot with a shadow ring. */
    private fun touch(c: Canvas, x: Float, y: Float, alpha: Float, scale: Float = 1f) {
        val rad = 9f * d * scale
        fill.color = ((alpha * 0x55).toInt() shl 24) or 0x000000
        c.drawCircle(x, y + 1.5f * d, rad * 1.1f, fill)
        fill.color = ((alpha * 0xEE).toInt() shl 24) or 0xFFFFFF
        c.drawCircle(x, y, rad, fill)
    }

    private fun handle(c: Canvas, bs: RectF, time: Float) {
        val p = time % 2.8f
        // zone at the top center
        val zw = bs.width() * 0.34f
        val zh = bs.height() * 0.12f
        r.set(bs.centerX() - zw / 2, bs.top, bs.centerX() + zw / 2, bs.top + zh)
        fill.color = 0x332CFFB1
        c.drawRoundRect(r, zh / 2, zh / 2, fill)
        val cx = bs.centerX()
        val cy = bs.top + zh * 0.55f
        val dotsShown = p in 0.9f..2.1f
        if (dotsShown) {
            // three dots pill
            val pw = bs.width() * 0.26f
            val ph = zh * 0.75f
            r.set(cx - pw / 2, bs.top + zh * 0.15f, cx + pw / 2, bs.top + zh * 0.15f + ph)
            fill.color = 0xF0101C26.toInt()
            c.drawRoundRect(r, ph / 2, ph / 2, fill)
            fill.color = 0xFFF1FFF9.toInt()
            for (i in -1..1) c.drawCircle(r.centerX() + i * pw * 0.2f, r.centerY(), ph * 0.13f, fill)
        }
        if (p in 1.6f..2.6f) {
            // menu card with three entries
            val mw = bs.width() * 0.6f
            val mh = bs.height() * 0.42f
            r.set(cx - mw / 2, bs.top + zh * 1.2f, cx + mw / 2, bs.top + zh * 1.2f + mh)
            fill.color = 0xF2101C26.toInt()
            c.drawRoundRect(r, 8 * d, 8 * d, fill)
            fill.color = 0x99F1FFF9.toInt()
            for (i in 0..2) {
                val ly = r.top + mh * (0.22f + i * 0.28f)
                c.drawRoundRect(r.left + mw * 0.12f, ly, r.left + mw * (0.72f - i * 0.1f), ly + mh * 0.09f, 3 * d, 3 * d, fill)
            }
        }
        // finger: hold (0.1–0.9) with a growing ring, tap on the dots (1.3–1.6)
        when {
            p < 0.1f -> {}
            p < 0.9f -> {
                val k = (p - 0.1f) / 0.8f
                stroke.color = (((1f - k) * 0xCC).toInt() shl 24) or 0x2CFFB1
                stroke.strokeWidth = 2f * d
                c.drawCircle(cx, cy, 10 * d + k * 16 * d, stroke)
                touch(c, cx, cy, 1f)
            }
            p in 1.3f..1.6f -> touch(c, cx, cy, 1f, 0.85f)
        }
    }

    private fun swipe(c: Canvas, bs: RectF, time: Float) {
        val p = time % 2.4f
        // edge strip at the bottom center
        val sw = bs.width() * 0.5f
        val sh = bs.height() * 0.07f
        r.set(bs.centerX() - sw / 2, bs.bottom - sh, bs.centerX() + sw / 2, bs.bottom)
        fill.color = 0x332CFFB1
        c.drawRoundRect(r, sh / 2, sh / 2, fill)
        if (p > 1.1f) return
        val k = min(1f, p / 0.9f)
        val x = bs.centerX()
        val y0 = bs.bottom - sh / 2
        val y = y0 - bs.height() * 0.5f * k
        // trail
        stroke.color = 0x882CFFB1.toInt()
        stroke.strokeWidth = 6f * d
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawLine(x, y0, x, y, stroke)
        // arrow head
        path.reset()
        val a = 7 * d
        path.moveTo(x - a, y + a * 0.2f)
        path.lineTo(x, y - a)
        path.lineTo(x + a, y + a * 0.2f)
        stroke.color = 0xFF2CFFB1.toInt()
        stroke.strokeWidth = 2.5f * d
        c.drawPath(path, stroke)
        touch(c, x, y + a * 1.6f, 1f - (k * 0.3f))
        stroke.strokeCap = Paint.Cap.BUTT
    }
}
