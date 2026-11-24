package com.pockethub.launcher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min

/**
 * Icon shapes drawn by the launcher itself (no system shape overlays needed).
 * - Adaptive icons: draw background and foreground ourselves and clip to the shape.
 * - Legacy full-bleed icons: just clip to the shape.
 * - Legacy icons with transparent edges: put them on a white plate of that shape.
 */
enum class IconShape { ROUND, SQUARE }

object RoundIcon {

    /** Current icon shape (circle or rounded square). */
    @Volatile var shape = IconShape.ROUND

    private fun cornerRadius(size: Float) = size * 0.24f

    fun render(d: Drawable, size: Int): Bitmap {
        val content = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(content)

        if (d is AdaptiveIconDrawable) {
            // Adaptive icon layer = 108dp, visible part = 72dp (2/3) → the layer is 1.5x larger.
            val inset = size / 4
            val b = Rect(-inset, -inset, size + inset, size + inset)
            val bg = d.background
            val fg = d.foreground
            if (bg != null) { bg.bounds = b; bg.draw(c) } else c.drawColor(Color.WHITE)
            if (fg != null) { fg.bounds = b; fg.draw(c) }
            return mask(content)
        }

        val src = drawableToBitmap(d, size)
        if (edgesOpaque(src)) return mask(src)

        c.drawColor(Color.WHITE)
        val s = size * 0.70f
        val off = (size - s) / 2f
        c.drawBitmap(src, null, RectF(off, off, off + s, off + s), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return mask(content)
    }

    /** Folder icon: a glass shape with up to 4 mini icons in a 2x2 grid. */
    fun folder(icons: List<Bitmap>, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C1A2A36.toInt() }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = max(1f, size / 48f)
        }
        drawShape(c, size.toFloat(), 1f, fill)
        drawShape(c, size.toFloat(), 1.5f, stroke)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val n = min(4, icons.size)
        val mini = size * 0.31f
        val gap = size * 0.05f
        val start = (size - (mini * 2 + gap)) / 2f
        for (i in 0 until n) {
            val l = start + (i % 2) * (mini + gap)
            val t = start + (i / 2) * (mini + gap)
            c.drawBitmap(icons[i], null, RectF(l, t, l + mini, t + mini), p)
        }
        return out
    }

    /** Clip a bitmap to the current shape. */
    private fun mask(src: Bitmap): Bitmap {
        val s = src.width
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        drawShape(Canvas(out), s.toFloat(), 0.5f, paint)
        return out
    }

    private fun drawShape(c: Canvas, size: Float, inset: Float, paint: Paint) {
        when (shape) {
            IconShape.ROUND -> c.drawCircle(size / 2f, size / 2f, size / 2f - inset, paint)
            IconShape.SQUARE -> {
                val r = cornerRadius(size)
                c.drawRoundRect(inset, inset, size - inset, size - inset, r, r, paint)
            }
        }
    }

    private fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        if (iw > 0 && ih > 0 && iw != ih) {
            val scale = size.toFloat() / max(iw, ih)
            val w = (iw * scale).toInt()
            val h = (ih * scale).toInt()
            val l = (size - w) / 2
            val t = (size - h) / 2
            d.setBounds(l, t, l + w, t + h)
        } else {
            d.setBounds(0, 0, size, size)
        }
        d.draw(Canvas(b))
        return b
    }

    /** Whether the edge midpoints are opaque (a full-bleed icon). */
    private fun edgesOpaque(b: Bitmap): Boolean {
        val s = b.width
        val m = max(1, (s * 0.03f).toInt())
        val pts = listOf(s / 2 to m, s / 2 to s - 1 - m, m to s / 2, s - 1 - m to s / 2)
        return pts.count { (x, y) -> Color.alpha(b.getPixel(x, y)) > 200 } >= 3
    }
}
