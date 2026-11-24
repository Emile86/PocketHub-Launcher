package com.pockethub.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Wallpaper kinds a screen can use. */
enum class WallpaperType {
    SYNTHWAVE, WAVES, ARCADE, NIGHT_CITY, AURORA, PLEXUS, BOKEH, CUSTOM_ANIM, VIDEO,
    PIXEL_DUSK, GAMEPADS, PIXEL_CASTLE, WIRE_HORIZON, MEMPHIS, IMAGE, SYSTEM;

    /** Built-in still wallpaper drawn by StillRenderer. */
    val still get() = this == PIXEL_DUSK || this == GAMEPADS || this == PIXEL_CASTLE ||
        this == WIRE_HORIZON || this == MEMPHIS

    /** Built-in scene drawn by SceneRenderer. */
    val scene get() = this in SCENES

    /** Anything that moves: a built-in scene, the user's own GIF / WebP or video. */
    val animated get() = scene || this == CUSTOM_ANIM || this == VIDEO

    companion object {
        val SCENES = setOf(SYNTHWAVE, WAVES, ARCADE, NIGHT_CITY, AURORA, PLEXUS, BOKEH)
    }
}

/**
 * Draws the animated retro scenes. One instance per view (paints and cached shaders are not shared).
 *  - SYNTHWAVE: striped sunset sun, mountains and a neon grid moving toward the viewer.
 *  - WAVES: neon sine waves, drifting 80s geometric shapes and scanlines.
 *  - ARCADE: dark starfield, a marching formation of pixel aliens (original designs) and a firing ship.
 *  - NIGHT_CITY: pixel skyline in three parallax layers, flickering windows, moon, rain and car lights.
 *  - AURORA: calm northern lights over a dark forest line.
 *  - PLEXUS: drifting points joined by faint lines when close (a slowly changing constellation).
 *  - BOKEH: soft out-of-focus lights in muted tones drifting in the dark.
 */
class SceneRenderer(private val density: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val pix = Paint() // no anti-aliasing: crisp pixel art

    private var cacheW = 0
    private var cacheH = 0
    private var cacheType: WallpaperType? = null
    private var sky: Shader? = null
    private var ground: Shader? = null
    private var sun: Shader? = null
    private var sunGlow: Shader? = null
    private var bg: Shader? = null
    private var scan: Shader? = null
    private var arcadeBg: Shader? = null
    private var citySky: Shader? = null
    private var moon: Bitmap? = null
    private var auroraSky: Shader? = null
    private var forest: Path? = null
    private var plexusBg: Shader? = null
    private var bokehBg: Shader? = null
    private var vignette: Shader? = null
    /** Vertical fade for an aurora curtain strip, in a 0..1 local space (scaled per strip). */
    private val curtain = List(3) { i ->
        val c = intArrayOf(0x5534E0A1, 0x403A9BD5, 0x30A06BFF)[i]
        LinearGradient(0f, 0f, 0f, 1f, intArrayOf(c and 0x00FFFFFF, c, c and 0x00FFFFFF),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
    }

    private val rnd = Random(42)
    private val stars = List(70) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat() * 0.75f, rnd.nextFloat() * 6f, 0.5f + rnd.nextFloat() * 1.5f) }
    private val shapes = List(9) { i ->
        // kind, x, y, size, speed, rotation speed, phase, color index
        floatArrayOf((i % 4).toFloat(), rnd.nextFloat(), rnd.nextFloat(), 0.04f + rnd.nextFloat() * 0.06f,
            0.2f + rnd.nextFloat() * 0.5f, (rnd.nextFloat() - 0.5f) * 0.8f, rnd.nextFloat() * 6f, (i % NEON.size).toFloat())
    }

    fun draw(c: Canvas, w: Int, h: Int, t: Float, type: WallpaperType) {
        if (w <= 0 || h <= 0) return
        if (w != cacheW || h != cacheH || type != cacheType) buildShaders(w, h, type)
        when (type) {
            WallpaperType.SYNTHWAVE -> synthwave(c, w.toFloat(), h.toFloat(), t)
            WallpaperType.ARCADE -> arcade(c, w.toFloat(), h.toFloat(), t)
            WallpaperType.NIGHT_CITY -> nightCity(c, w.toFloat(), h.toFloat(), t)
            WallpaperType.AURORA -> aurora(c, w.toFloat(), h.toFloat(), t)
            WallpaperType.PLEXUS -> plexus(c, w.toFloat(), h.toFloat(), t)
            WallpaperType.BOKEH -> bokeh(c, w.toFloat(), h.toFloat(), t)
            else -> waves(c, w.toFloat(), h.toFloat(), t)
        }
    }

    private fun buildShaders(w: Int, h: Int, type: WallpaperType) {
        cacheW = w; cacheH = h; cacheType = type
        val hf = h.toFloat()
        val wf = w.toFloat()
        val horizon = hf * HORIZON
        sky = LinearGradient(0f, 0f, 0f, horizon,
            intArrayOf(0xFF0B0221.toInt(), 0xFF2A0845.toInt(), 0xFF7A1F6E.toInt(), 0xFFFF5E8A.toInt()),
            floatArrayOf(0f, 0.45f, 0.82f, 1f), Shader.TileMode.CLAMP)
        ground = LinearGradient(0f, horizon, 0f, hf, 0xFF1A0433.toInt(), 0xFF05000F.toInt(), Shader.TileMode.CLAMP)
        val r = min(wf, hf) * 0.24f
        val cy = horizon - r * 0.35f
        sun = LinearGradient(0f, cy - r, 0f, cy + r,
            intArrayOf(0xFFFFE66D.toInt(), 0xFFFF8A3D.toInt(), 0xFFFF3C8E.toInt()), null, Shader.TileMode.CLAMP)
        sunGlow = RadialGradient(wf / 2f, cy, r * 2.2f, 0x66FF3C8E, 0x00FF3C8E, Shader.TileMode.CLAMP)
        bg = LinearGradient(0f, 0f, wf * 0.3f, hf,
            intArrayOf(0xFF0B1026.toInt(), 0xFF1B0B3A.toInt(), 0xFF2B0F3F.toInt()), null, Shader.TileMode.CLAMP)
        val sl = Bitmap.createBitmap(1, max(3, (3 * density).toInt()), Bitmap.Config.ARGB_8888)
        sl.setPixel(0, 0, 0x26000000)
        scan = BitmapShader(sl, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        arcadeBg = LinearGradient(0f, 0f, 0f, hf, 0xFF02030A.toInt(), 0xFF090520.toInt(), Shader.TileMode.CLAMP)
        citySky = LinearGradient(0f, 0f, 0f, hf,
            intArrayOf(0xFF020208.toInt(), 0xFF090720.toInt(), 0xFF1A1032.toInt()),
            floatArrayOf(0f, 0.55f, 0.9f), Shader.TileMode.CLAMP)
        moon = null // rebuilt for the new pixel size
        auroraSky = LinearGradient(0f, 0f, 0f, hf,
            intArrayOf(0xFF010408.toInt(), 0xFF041019.toInt(), 0xFF071A22.toInt()), null, Shader.TileMode.CLAMP)
        forest = null
        plexusBg = RadialGradient(wf * 0.5f, hf * 0.35f, max(wf, hf) * 0.8f,
            0xFF0B111A.toInt(), 0xFF040609.toInt(), Shader.TileMode.CLAMP)
        bokehBg = LinearGradient(0f, 0f, 0f, hf, 0xFF08090E.toInt(), 0xFF0D0E15.toInt(), Shader.TileMode.CLAMP)
        vignette = RadialGradient(wf / 2f, hf / 2f, max(wf, hf) * 0.75f,
            intArrayOf(0x00000000, 0x00000000, 0x99000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    // ---------- aurora ----------

    private fun buildForest(w: Float, h: Float): Path {
        val p = Path()
        val base = h * 0.9f
        p.moveTo(0f, h)
        p.lineTo(0f, base)
        val r = Random(7)
        var x = 0f
        while (x < w) {
            val tw = h * (0.025f + r.nextFloat() * 0.03f)
            val th = h * (0.05f + r.nextFloat() * 0.09f)
            p.lineTo(x + tw / 2f, base - th)
            p.lineTo(x + tw, base)
            x += tw * (0.6f + r.nextFloat() * 0.5f)
        }
        p.lineTo(w, h)
        p.close()
        return p
    }

    private fun aurora(c: Canvas, w: Float, h: Float, t: Float) {
        fill.shader = auroraSky
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        for (s in stars) {
            val a = (0.2f + 0.45f * abs(sin(t * 0.5f + s[2]))).coerceIn(0f, 1f)
            fill.color = Color.argb((a * 200).toInt(), 220, 235, 255)
            c.drawCircle(s[0] * w, s[1] * h * 0.7f, s[3] * density * 0.7f, fill)
        }
        // Curtains: thin vertical strips along slow waves, each strip faded top-to-bottom.
        val step = max(4f, 7f * density)
        for (i in 0..2) {
            fill.shader = curtain[i]
            var x = 0f
            while (x < w) {
                val base = h * (0.18f + i * 0.08f) +
                    sin(x / w * 5.2f + t * (0.12f + i * 0.04f) + i * 2f) * h * 0.07f +
                    sin(x / w * 11f - t * 0.2f) * h * 0.025f
                val len = h * (0.16f + 0.07f * sin(x / w * 7f + t * 0.3f + i))
                val glow = 0.55f + 0.45f * sin(x / w * 3f + t * 0.25f + i * 1.7f)
                fill.alpha = (255 * glow.coerceIn(0.1f, 1f)).toInt()
                c.save()
                c.translate(x, base)
                c.scale(1f, len)
                c.drawRect(0f, 0f, step + 1f, 1f, fill)
                c.restore()
                x += step
            }
        }
        fill.alpha = 255
        fill.shader = null
        // Dark forest line.
        val f = forest ?: buildForest(w, h).also { forest = it }
        fill.color = 0xFF010305.toInt()
        c.drawPath(f, fill)
    }

    // ---------- plexus ----------

    private val points = List(64) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat() * 6f,
        (rnd.nextFloat() - 0.5f) * 0.012f, (rnd.nextFloat() - 0.5f) * 0.008f) }
    private val px = FloatArray(64)
    private val py = FloatArray(64)

    private fun plexus(c: Canvas, w: Float, h: Float, t: Float) {
        fill.shader = plexusBg
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        for (i in points.indices) {
            val p = points[i]
            val x = ((p[0] + p[3] * t + sin(t * 0.15f + p[2]) * 0.02f) % 1f + 1f) % 1f
            val y = ((p[1] + p[4] * t + cos(t * 0.12f + p[2]) * 0.02f) % 1f + 1f) % 1f
            px[i] = x * w
            py[i] = y * h
        }
        val maxD = min(w, h) * 0.22f
        line.strokeWidth = max(1f, 0.9f * density)
        for (i in points.indices) for (j in i + 1 until points.size) {
            val dx = px[i] - px[j]
            val dy = py[i] - py[j]
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d < maxD) {
                val a = ((1f - d / maxD) * 90).toInt()
                line.color = Color.argb(a, 0x8F, 0xA7, 0xC4)
                c.drawLine(px[i], py[i], px[j], py[j], line)
            }
        }
        for (i in points.indices) {
            val pulse = 0.55f + 0.45f * sin(t * 0.8f + points[i][2])
            fill.color = Color.argb((140 + 90 * pulse).toInt(), 0xC9, 0xD6, 0xE6)
            c.drawCircle(px[i], py[i], (1.4f + pulse * 0.8f) * density, fill)
        }
    }

    // ---------- bokeh ----------

    private val bokehDots = List(24) { i ->
        // x, y, radius (dp), speed, phase, color index
        floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 18f + rnd.nextFloat() * 70f,
            0.004f + rnd.nextFloat() * 0.01f, rnd.nextFloat() * 6f, (i % 4).toFloat())
    }
    private var bokehShaders: List<Shader>? = null

    private fun bokeh(c: Canvas, w: Float, h: Float, t: Float) {
        fill.shader = bokehBg
        c.drawRect(0f, 0f, w, h, fill)
        val colors = intArrayOf(0xFFE0A15A.toInt(), 0xFFC77D8F.toInt(), 0xFF6E8FB5.toInt(), 0xFF7FA38C.toInt())
        val shaders = bokehShaders ?: bokehDots.map { d ->
            val r = d[2] * density
            val col = colors[d[5].toInt()]
            RadialGradient(0f, 0f, r,
                intArrayOf((col and 0x00FFFFFF) or 0x38000000, (col and 0x00FFFFFF) or 0x24000000, col and 0x00FFFFFF),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }.also { bokehShaders = it }
        for (i in bokehDots.indices) {
            val d = bokehDots[i]
            val r = d[2] * density
            val x = ((d[0] + sin(t * 0.07f + d[4]) * 0.04f) % 1f) * w
            val y = (((d[1] - t * d[3]) % 1f + 1f) % 1f) * (h + 2 * r) - r
            val breathe = 0.6f + 0.4f * sin(t * 0.35f + d[4])
            fill.shader = shaders[i]
            fill.alpha = (255 * breathe).toInt()
            c.save()
            c.translate(x, y)
            c.drawCircle(0f, 0f, r, fill)
            c.restore()
        }
        fill.alpha = 255
        fill.shader = vignette
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    // ---------- pixel helpers ----------

    private fun unit(w: Float, h: Float, div: Float) = max(2f, kotlin.math.floor(min(w, h) / div))

    private fun snap(v: Float, u: Float) = kotlin.math.floor(v / u) * u

    private fun sprite(c: Canvas, rows: Array<String>, x: Float, y: Float, u: Float, color: Int) {
        pix.color = color
        for (r in rows.indices) {
            val row = rows[r]
            for (k in row.indices) if (row[k] == '#') c.drawRect(x + k * u, y + r * u, x + (k + 1) * u, y + (r + 1) * u, pix)
        }
    }

    private fun hash(a: Int, b: Int, c: Int): Int {
        var x = a * 73856093 xor b * 19349663 xor c * 83492791
        x = x xor (x ushr 13)
        x *= 0x5bd1e995
        return (x xor (x ushr 15)) and 0x7fffffff
    }

    // ---------- arcade ----------

    private val arcadeStars = List(110) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), (it % 3).toFloat(), rnd.nextFloat() * 6f) }

    private fun arcade(c: Canvas, w: Float, h: Float, t: Float) {
        val u = unit(w, h, 110f)
        fill.shader = arcadeBg
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        // three layers of falling pixel stars
        for (s in arcadeStars) {
            val layer = s[2].toInt()
            val speed = 0.015f + layer * 0.02f
            val y = ((s[1] + t * speed) % 1f) * h
            val a = (0.35f + 0.25f * layer + 0.25f * sin(t * 2f + s[3])).coerceIn(0f, 1f)
            pix.color = Color.argb((a * 255).toInt(), 200, 220, 255)
            val size = if (layer == 2) u else max(1f, u / 2)
            val x = snap(s[0] * w, u)
            c.drawRect(x, snap(y, u), x + size, snap(y, u) + size, pix)
        }

        // marching formation
        val cols = 7
        val rowsN = 3
        val cellW = 13 * u
        val cellH = 10 * u
        val formW = cols * cellW
        val step = (t * 2f).toInt()
        val pos = step % 40
        val tri = if (pos < 20) pos / 20f else (40 - pos) / 20f
        val span = max(0f, w * 0.8f - formW)
        val x0 = snap((w - formW) / 2f - span / 2f + span * tri, u)
        val y0 = snap(h * 0.10f + ((t / 8f).toInt() % 4) * u * 2, u)
        val frame = step % 2
        for (r in 0 until rowsN) {
            val art = ALIENS[r][frame]
            val color = ALIEN_COLORS[r]
            for (k in 0 until cols) {
                val ox = (cellW - art[0].length * u) / 2f
                sprite(c, art, x0 + k * cellW + ox, y0 + r * cellH, u, color)
            }
        }
        val formBottom = y0 + rowsN * cellH

        // ship and its shots
        val shipY = snap(h * 0.84f, u)
        fun shipX(time: Float) = snap(w / 2f + sin(time * 0.6f) * w * 0.32f - SHIP[0].length * u / 2f, u)
        val period = 0.9f
        val travel = (shipY - formBottom).coerceAtLeast(1f)
        val speed = h * 0.9f
        val last = (t / period).toInt()
        pix.color = 0xFFFFF27A.toInt()
        for (k in 0..4) {
            val launch = (last - k) * period
            if (launch < 0f) continue
            val y = shipY - (t - launch) * speed
            if (shipY - y > travel) continue
            val bx = shipX(launch) + (SHIP[0].length / 2) * u
            c.drawRect(bx, snap(y, u), bx + u, snap(y, u) + 3 * u, pix)
        }
        sprite(c, SHIP, shipX(t), shipY, u, 0xFF3FF7FF.toInt())

        // ground line
        pix.color = 0xFF1E8C5A.toInt()
        val gy = snap(h * 0.93f, u)
        c.drawRect(0f, gy, w, gy + u, pix)

        fill.shader = scan
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    // ---------- night city ----------

    /** Building widths/heights in pixel units per parallax layer (far, mid, near). */
    private val city = List(3) { layer ->
        val list = ArrayList<IntArray>()
        var total = 0
        while (total < 420) {
            val bw = 6 + rnd.nextInt(13)
            val bh = when (layer) { 0 -> 22 + rnd.nextInt(30); 1 -> 16 + rnd.nextInt(26); else -> 10 + rnd.nextInt(20) }
            list += intArrayOf(bw, bh, rnd.nextInt(1000))
            total += bw + rnd.nextInt(2)
        }
        list
    }
    private val rain = List(70) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.7f + rnd.nextFloat() * 0.6f) }

    private fun buildMoon(u: Float): Bitmap {
        val r = 9
        val size = (2 * r + 1)
        val bmp = Bitmap.createBitmap((size * u).toInt(), (size * u).toInt(), Bitmap.Config.ARGB_8888)
        val mc = Canvas(bmp)
        val p = Paint()
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x - r
            val dy = y - r
            if (dx * dx + dy * dy <= r * r) {
                // a few darker "craters"
                val crater = (dx + 3) * (dx + 3) + (dy + 2) * (dy + 2) <= 4 || (dx - 4) * (dx - 4) + (dy - 3) * (dy - 3) <= 2
                p.color = if (crater) 0xFFC9C3A8.toInt() else 0xFFEDE6C8.toInt()
                mc.drawRect(x * u, y * u, (x + 1) * u, (y + 1) * u, p)
            }
        }
        return bmp
    }

    private fun nightCity(c: Canvas, w: Float, h: Float, t: Float) {
        val u = unit(w, h, 140f)
        fill.shader = citySky
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        // stars
        for (s in stars) {
            val a = (0.3f + 0.5f * abs(sin(t * 0.6f + s[2]))).coerceIn(0f, 1f)
            pix.color = Color.argb((a * 220).toInt(), 230, 230, 255)
            val x = snap(s[0] * w, u)
            val y = snap(s[1] * h * 0.6f, u)
            c.drawRect(x, y, x + max(1f, u / 2), y + max(1f, u / 2), pix)
        }

        // moon
        val m = moon ?: buildMoon(u).also { moon = it }
        c.drawBitmap(m, snap(w * 0.76f, u), snap(h * 0.10f, u), null)

        // skyline layers, scrolling left at different speeds
        val ground = snap(h * 0.88f, u)
        val colors = intArrayOf(0xFF140E2A.toInt(), 0xFF0C0A1C.toInt(), 0xFF060511.toInt())
        val speeds = floatArrayOf(1.5f, 4f, 9f)
        for (layer in 0..2) {
            val list = city[layer]
            val totalU = list.sumOf { it[0] + 1 }
            val totalPx = totalU * u
            val offset = (t * speeds[layer] * u) % totalPx
            var x = -offset
            var i = 0
            while (x < w) {
                val b = list[i % list.size]
                val bw = b[0] * u
                val bh = b[1] * u * (if (layer == 0) 1.15f else 1f)
                if (x + bw > 0) {
                    pix.color = colors[layer]
                    val top = snap(ground - bh, u)
                    c.drawRect(x, top, x + bw, ground, pix)
                    if (layer > 0) {
                        // windows: lit ones change every few seconds
                        val bucket = (t / 3f).toInt()
                        var wy = top + 2 * u
                        var row = 0
                        while (wy < ground - 2 * u) {
                            var wx = x + u
                            var col = 0
                            while (wx < x + bw - u) {
                                val hsh = hash(b[2] + layer * 7919, row * 31 + col, bucket + (b[2] % 5))
                                if (hsh % 6 == 0) {
                                    pix.color = if (hsh % 5 == 0) 0xFF5AE0FF.toInt() else 0xFFFFC75A.toInt()
                                    pix.alpha = if (layer == 1) 150 else 230
                                    c.drawRect(wx, wy, wx + u, wy + u, pix)
                                    pix.alpha = 255
                                }
                                wx += 2 * u; col++
                            }
                            wy += 3 * u; row++
                        }
                    }
                }
                x += bw + u
                i++
            }
        }

        // road and car lights
        pix.color = 0xFF040308.toInt()
        c.drawRect(0f, ground, w, h, pix)
        for (k in 0..4) {
            val dir = if (k % 2 == 0) 1 else -1
            val p = ((k * 0.23f + t * 0.06f * (1 + k % 3)) % 1.3f) - 0.15f
            val cx = snap(if (dir > 0) p * w else (1f - p) * w, u)
            val cy = snap(ground + u * (2 + (k % 2) * 3), u)
            pix.color = if (dir > 0) 0xFFFF4D6D.toInt() else 0xFFFFF1C1.toInt()
            c.drawRect(cx, cy, cx + 2 * u, cy + u, pix)
            c.drawRect(cx + 4 * u, cy, cx + 6 * u, cy + u, pix)
        }

        // light rain
        pix.color = 0x405AE0FF
        val dropW = max(1f, u / 3)
        for (d in rain) {
            val x = ((d[0] + t * 0.03f) % 1f) * w
            val y = ((d[1] + t * 0.8f * d[2]) % 1f) * h
            c.drawRect(x, y, x + dropW, y + 3 * u, pix)
        }

        fill.shader = scan
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    // ---------- synthwave ----------

    private fun synthwave(c: Canvas, w: Float, h: Float, t: Float) {
        val horizon = h * HORIZON
        fill.shader = sky
        c.drawRect(0f, 0f, w, horizon, fill)

        // twinkling stars
        fill.shader = null
        for (s in stars) {
            val a = (0.35f + 0.65f * abs(sin(t * 0.8f + s[2]))).coerceIn(0f, 1f)
            fill.color = Color.argb((a * 200).toInt(), 255, 240, 255)
            c.drawCircle(s[0] * w, s[1] * horizon, s[3] * density, fill)
        }

        // sun with glow and moving stripes
        val r = min(w, h) * 0.24f
        val cx = w / 2f
        val cy = horizon - r * 0.35f
        fill.shader = sunGlow
        c.drawCircle(cx, cy, r * 2.2f, fill)
        c.save()
        c.clipRect(0f, 0f, w, horizon)
        val stripes = 7
        val shift = (t * 0.25f) % 1f
        for (i in 0 until stripes) {
            val k = (i + shift) / stripes
            val y = cy + r * 0.05f + k * r
            val hh = r * (0.02f + 0.07f * k)
            c.clipOutRect(cx - r, y, cx + r, y + hh)
        }
        fill.shader = sun
        c.drawCircle(cx, cy, r, fill)
        c.restore()

        // mountains
        fill.shader = null
        fill.color = 0xFF240A3D.toInt()
        path.reset()
        path.moveTo(0f, horizon)
        val peaks = floatArrayOf(0f, 0.6f, 0.08f, 0.35f, 0.16f, 0.75f, 0.24f, 0.45f, 0.3f, 0.2f,
            0.7f, 0.25f, 0.78f, 0.6f, 0.86f, 0.4f, 0.93f, 0.8f, 1f, 0.5f)
        for (i in peaks.indices step 2) path.lineTo(peaks[i] * w, horizon - peaks[i + 1] * h * 0.12f)
        path.lineTo(w, horizon)
        path.close()
        c.drawPath(path, fill)

        // ground
        fill.shader = ground
        c.drawRect(0f, horizon, w, h, fill)
        fill.shader = null

        // neon grid
        val pink = 0xFFFF2BD6.toInt()
        line.color = pink
        line.strokeWidth = 1.6f * density
        glow.color = 0x55FF2BD6
        glow.strokeWidth = 5f * density
        val vp = w / 2f
        val n = 16
        for (k in -n..n) {
            val xTop = vp + k * w * 0.02f
            val xBottom = vp + k * w * 0.14f
            c.drawLine(xTop, horizon, xBottom, h, glow)
            c.drawLine(xTop, horizon, xBottom, h, line)
        }
        val rows = 14
        val phase = (t * 0.45f) % 1f
        for (i in 0 until rows) {
            val z = (i + phase) / rows
            val y = horizon + (h - horizon) * z.pow(2.3f)
            val a = (40 + 215 * z).toInt().coerceIn(0, 255)
            line.color = Color.argb(a, 0xFF, 0x2B, 0xD6)
            glow.color = Color.argb(a / 3, 0xFF, 0x2B, 0xD6)
            c.drawLine(0f, y, w, y, glow)
            c.drawLine(0f, y, w, y, line)
        }
        // horizon line
        line.color = 0xFF00E5FF.toInt()
        line.strokeWidth = 1.2f * density
        glow.color = 0x6600E5FF
        c.drawLine(0f, horizon, w, horizon, glow)
        c.drawLine(0f, horizon, w, horizon, line)
    }

    // ---------- retro waves ----------

    private fun waves(c: Canvas, w: Float, h: Float, t: Float) {
        fill.shader = bg
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        // drifting geometric shapes
        line.strokeWidth = 2.5f * density
        for (s in shapes) {
            val size = s[3] * min(w, h) * 1.6f
            val x = s[1] * w + sin(t * 0.3f * s[4] + s[6]) * w * 0.06f
            val travel = h + size * 2
            val y = ((s[2] * travel - t * s[4] * h * 0.03f) % travel + travel) % travel - size
            val rot = t * s[5]
            line.color = (NEON[s[7].toInt()] and 0x00FFFFFF) or 0xB0000000.toInt()
            c.save()
            c.rotate(rot * 57.3f, x, y)
            when (s[0].toInt()) {
                0 -> c.drawCircle(x, y, size / 2, line)
                1 -> {
                    path.reset()
                    for (k in 0..2) {
                        val ang = (k * 120 - 90) * PI / 180
                        val px = x + cos(ang).toFloat() * size / 2
                        val py = y + sin(ang).toFloat() * size / 2
                        if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    c.drawPath(path, line)
                }
                2 -> c.drawRect(x - size / 2.6f, y - size / 2.6f, x + size / 2.6f, y + size / 2.6f, line)
                else -> {
                    path.reset()
                    val step = size / 4
                    path.moveTo(x - size / 2, y)
                    for (k in 1..4) path.lineTo(x - size / 2 + k * step, y + (if (k % 2 == 1) -step / 1.5f else step / 1.5f))
                    c.drawPath(path, line)
                }
            }
            c.restore()
        }

        // neon sine waves
        val count = 4
        val stepX = max(6f, 6f * density)
        for (i in 0 until count) {
            val base = h * (0.52f + i * 0.09f)
            val amp = h * (0.05f + 0.015f * i)
            val freq = (2f * PI.toFloat() / w) * (1.2f + i * 0.35f)
            val speed = 0.7f + i * 0.25f
            path.reset()
            var x = 0f
            while (x <= w + stepX) {
                val y = base + amp * sin(x * freq + t * speed + i * 1.3f)
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += stepX
            }
            val col = NEON[i % NEON.size]
            glow.color = (col and 0x00FFFFFF) or 0x44000000
            glow.strokeWidth = 7f * density
            line.color = col
            line.strokeWidth = 2f * density
            c.drawPath(path, glow)
            c.drawPath(path, line)
        }

        // scanlines
        fill.shader = scan
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    companion object {
        private const val HORIZON = 0.6f

        /** Original pixel aliens, two animation frames each ('#' = pixel). */
        private val ALIENS = arrayOf(
            arrayOf(
                arrayOf("...###...", ".#######.", "##.###.##", "#########", ".#.#.#.#.", "#.#...#.#"),
                arrayOf("...###...", ".#######.", "##.###.##", "#########", ".#.#.#.#.", ".#.#.#.#."),
            ),
            arrayOf(
                arrayOf("..####..", ".######.", "#.#..#.#", "########", "..#..#..", ".#.##.#."),
                arrayOf("..####..", ".######.", "#.#..#.#", "########", "..#..#..", "#......#"),
            ),
            arrayOf(
                arrayOf("#.......#", ".#.###.#.", "..#####..", ".##.#.##.", ".#######.", "..#...#.."),
                arrayOf("....#....", ".#.###.#.", "..#####..", ".##.#.##.", ".#######.", ".#.....#."),
            ),
        )
        private val ALIEN_COLORS = intArrayOf(0xE0FF4FD8.toInt(), 0xE03FF7FF.toInt(), 0xE09DFF57.toInt())
        private val SHIP = arrayOf("....#....", "...###...", ".#######.", "#########", "##.....##")
        private val NEON = intArrayOf(0xFF00E5FF.toInt(), 0xFFFF2BD6.toInt(), 0xFFFFD166.toInt(), 0xFFFF8A3D.toInt(), 0xFF8C5CFF.toInt())

        /** One still frame (used for the system wallpaper, which shows during the Recents gesture). */
        fun renderStill(ctx: Context, type: WallpaperType, w: Int, h: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            SceneRenderer(ctx.resources.displayMetrics.density).draw(Canvas(bmp), w, h, 2.5f, type)
            return bmp
        }
    }
}

/** Animated wallpaper view (~30 fps). Runs only between resume() and pause(). */
class AnimatedWallpaperView(ctx: Context) : View(ctx) {
    private val renderer = SceneRenderer(ctx.resources.displayMetrics.density)
    private val start = SystemClock.uptimeMillis()
    private var running = false

    var type = WallpaperType.SYNTHWAVE
        set(v) { field = v; invalidate() }

    fun resume() {
        if (running) return
        running = true
        invalidate()
    }

    fun pause() {
        running = false
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        renderer.draw(canvas, width, height, t, type)
        if (running && visibility == VISIBLE) postInvalidateDelayed(FRAME_MS)
    }

    companion object { private const val FRAME_MS = 33L }
}
