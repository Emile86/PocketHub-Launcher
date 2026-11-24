package com.pockethub.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Built-in still (non-animated) retro gaming wallpapers, drawn in code at the screen size:
 *  - PIXEL_DUSK: 16-bit dusk landscape — dithered sky, pixel sun, three mountain layers, a lake.
 *  - GAMEPADS: dark doodle pattern of outlined gamepads, D-pads, buttons, sticks and cartridges.
 *  - PIXEL_CASTLE: pixel night — moon, stars and a castle on a hill with lit windows.
 *  - WIRE_HORIZON: 80s wireframe terrain with distant peaks under a low striped sun, star dust, grain.
 *  - MEMPHIS: dark 80s Memphis pattern in muted colours — squiggles, triangles, half circles, dots.
 */
object StillRenderer {

    fun render(ctx: Context, type: WallpaperType, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), w.toFloat(), h.toFloat(), type, ctx.resources.displayMetrics.density)
        return bmp
    }

    @Synchronized // the shared Paint below
    fun draw(c: Canvas, w: Float, h: Float, type: WallpaperType, density: Float) {
        when (type) {
            WallpaperType.PIXEL_DUSK -> dusk(c, w, h)
            WallpaperType.GAMEPADS -> gamepads(c, w, h, density)
            WallpaperType.WIRE_HORIZON -> wireHorizon(c, w, h, density)
            WallpaperType.MEMPHIS -> memphis(c, w, h, density)
            else -> castle(c, w, h)
        }
    }

    private val pix = Paint() // no anti-aliasing: crisp pixels

    private fun unit(w: Float, h: Float, div: Float) = max(2f, floor(min(w, h) / div))
    private fun snap(v: Float, u: Float) = floor(v / u) * u

    private fun rect(c: Canvas, x: Float, y: Float, rw: Float, rh: Float, color: Int) {
        pix.color = color
        c.drawRect(x, y, x + rw, y + rh, pix)
    }

    /** Horizontal bands with a checkerboard dither row between neighbours (classic 16-bit sky). */
    private fun ditheredBands(c: Canvas, w: Float, top: Float, bottom: Float, u: Float, colors: IntArray) {
        val bandH = snap((bottom - top) / colors.size, u).coerceAtLeast(u)
        for (i in colors.indices) {
            val y = top + i * bandH
            val hh = if (i == colors.lastIndex) bottom - y else bandH
            rect(c, 0f, y, w, hh, colors[i])
            if (i > 0) {
                // dither: one row of the previous colour on every other pixel
                pix.color = colors[i - 1]
                var x = 0f
                var k = 0
                while (x < w) {
                    if (k % 2 == 0) c.drawRect(x, y, x + u, y + u, pix)
                    else c.drawRect(x, y + u, x + u, y + 2 * u, pix)
                    x += u; k++
                }
            }
        }
    }

    private fun pixelCircle(c: Canvas, cx: Float, cy: Float, r: Int, u: Float, color: Int, clipBottom: Float = Float.MAX_VALUE) {
        pix.color = color
        for (y in -r..r) for (x in -r..r) {
            if (x * x + y * y <= r * r) {
                val px = cx + x * u
                val py = cy + y * u
                if (py + u <= clipBottom) c.drawRect(px, py, px + u, py + u, pix)
            }
        }
    }

    // ---------- pixel dusk ----------

    private fun dusk(c: Canvas, w: Float, h: Float) {
        val u = unit(w, h, 90f)
        val horizon = snap(h * 0.62f, u)
        ditheredBands(c, w, 0f, horizon, u, intArrayOf(
            0xFF1B1033.toInt(), 0xFF2A1745.toInt(), 0xFF45204F.toInt(), 0xFF6E2C55.toInt(),
            0xFFA53F55.toInt(), 0xFFD9614C.toInt(), 0xFFF2924A.toInt(), 0xFFF7C26B.toInt()))

        // stars in the upper sky
        val r = Random(11)
        for (i in 0 until 40) {
            val x = snap(r.nextFloat() * w, u)
            val y = snap(r.nextFloat() * horizon * 0.35f, u)
            rect(c, x, y, u, u, if (i % 4 == 0) 0xFFFFF3C4.toInt() else 0x88FFFFFF.toInt())
        }
        // pixel clouds
        for (k in 0 until 4) {
            val cx = snap(w * (0.12f + k * 0.24f + r.nextFloat() * 0.05f), u)
            val cy = snap(horizon * (0.28f + (k % 2) * 0.14f), u)
            val col = if (k % 2 == 0) 0xFF8E3A5C.toInt() else 0xFFB8505A.toInt()
            rect(c, cx, cy, 14 * u, 2 * u, col)
            rect(c, cx + 3 * u, cy - u, 7 * u, u, col)
            rect(c, cx + 2 * u, cy + 2 * u, 10 * u, u, col)
        }
        // sun (clipped at the horizon) with an inner highlight
        val sunCx = snap(w * 0.5f, u)
        pixelCircle(c, sunCx, horizon - 2 * u, 11, u, 0xFFFFD27A.toInt(), clipBottom = horizon)
        pixelCircle(c, sunCx, horizon - 2 * u, 7, u, 0xFFFFF0B8.toInt(), clipBottom = horizon)

        // three mountain layers (stepped columns)
        val layers = arrayOf(
            Triple(0xFF5B2A5E.toInt(), 0.16f, 3.1f),
            Triple(0xFF3C1E48.toInt(), 0.11f, 5.3f),
            Triple(0xFF24122F.toInt(), 0.07f, 8.7f),
        )
        for ((li, layer) in layers.withIndex()) {
            val (col, amp, freq) = layer
            var x = 0f
            while (x < w) {
                val n = sin(x / w * freq + li * 1.7f) * 0.6f + sin(x / w * freq * 2.3f + li) * 0.4f
                val top = snap(horizon - h * amp * (0.45f + 0.55f * abs(n)), u)
                rect(c, x, top, u, horizon - top, col)
                x += u
            }
        }
        // lake with the sun's reflection
        rect(c, 0f, horizon, w, h - horizon, 0xFF160B24.toInt())
        var y = horizon + u
        var row = 0
        while (y < h * 0.86f) {
            val half = (11 - row * 0.8f).coerceAtLeast(2f) * u
            val shift = if (row % 2 == 0) 0f else 2 * u
            rect(c, sunCx - half + shift, y, half * 2 - 4 * u, u, if (row < 3) 0xFFF7C26B.toInt() else 0xFFA53F55.toInt())
            y += 2 * u; row++
        }
        // foreground pines on both sides
        pix.color = 0xFF0D0717.toInt()
        val ground = snap(h * 0.88f, u)
        c.drawRect(0f, ground, w, h, pix)
        for (side in 0..1) {
            for (k in 0 until 4) {
                val tx = if (side == 0) snap(w * (0.02f + k * 0.06f), u) else snap(w * (0.80f + k * 0.06f), u)
                val th = (14 + (k * 5) % 9)
                for (j in 0 until th) {
                    val half = ((j / 2) + 1).coerceAtMost(6)
                    c.drawRect(tx - half * u, ground - (th - j) * u, tx + (half + 1) * u, ground - (th - j - 1) * u, pix)
                }
            }
        }
        // a few birds
        pix.color = 0xFF2A1745.toInt()
        for (k in 0 until 3) {
            val bx = snap(w * (0.62f + k * 0.05f), u)
            val by = snap(horizon * (0.42f + k * 0.04f), u)
            c.drawRect(bx, by, bx + u, by + u, pix)
            c.drawRect(bx + u, by + u, bx + 2 * u, by + 2 * u, pix)
            c.drawRect(bx + 2 * u, by, bx + 3 * u, by + u, pix)
        }
    }

    // ---------- gamepads doodle pattern ----------

    private fun gamepads(c: Canvas, w: Float, h: Float, d: Float) {
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.4f, h, 0xFF0B0E13.toInt(), 0xFF121721.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, bg)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val cell = 96f * d
        val r = Random(5)
        var row = 0
        var y = -cell / 2
        while (y < h + cell) {
            var x = if (row % 2 == 0) -cell / 2 else 0f
            while (x < w + cell) {
                val kind = r.nextInt(8)
                val accent = r.nextInt(7)
                line.color = when (accent) {
                    0 -> 0x552CFFB1
                    1 -> 0x55FF4FD8
                    2 -> 0x4DFFD166
                    else -> 0x22FFFFFF
                }
                c.save()
                c.translate(x + cell / 2, y + cell / 2)
                c.rotate((r.nextFloat() - 0.5f) * 40f)
                icon(c, kind, cell * 0.32f, line)
                c.restore()
                x += cell
            }
            y += cell * 0.86f
            row++
        }
        val vignette = Paint().apply {
            shader = RadialGradient(w / 2, h / 2, max(w, h) * 0.75f,
                intArrayOf(0x00000000, 0x00000000, 0xAA000000.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, vignette)
    }

    /** One outlined doodle centred at 0,0 with half-size s. */
    private fun icon(c: Canvas, kind: Int, s: Float, p: Paint) {
        when (kind) {
            0 -> { // gamepad
                val path = Path()
                path.addRoundRect(-s, -s * 0.5f, s, s * 0.5f, s * 0.45f, s * 0.45f, Path.Direction.CW)
                c.drawPath(path, p)
                c.drawLine(-s * 0.62f, 0f, -s * 0.3f, 0f, p)
                c.drawLine(-s * 0.46f, -s * 0.16f, -s * 0.46f, s * 0.16f, p)
                c.drawCircle(s * 0.38f, -s * 0.1f, s * 0.08f, p)
                c.drawCircle(s * 0.58f, s * 0.1f, s * 0.08f, p)
            }
            1 -> { // D-pad
                val a = s * 0.3f
                val path = Path()
                path.moveTo(-a, -s); path.lineTo(a, -s); path.lineTo(a, -a); path.lineTo(s, -a)
                path.lineTo(s, a); path.lineTo(a, a); path.lineTo(a, s); path.lineTo(-a, s)
                path.lineTo(-a, a); path.lineTo(-s, a); path.lineTo(-s, -a); path.lineTo(-a, -a); path.close()
                c.drawPath(path, p)
            }
            2 -> { // four face buttons
                val o = s * 0.5f
                val br = s * 0.24f
                c.drawCircle(0f, -o, br, p); c.drawCircle(o, 0f, br, p)
                c.drawCircle(0f, o, br, p); c.drawCircle(-o, 0f, br, p)
            }
            3 -> { // joystick
                c.drawCircle(0f, -s * 0.35f, s * 0.3f, p)
                c.drawLine(0f, -s * 0.05f, 0f, s * 0.45f, p)
                c.drawRoundRect(-s * 0.7f, s * 0.45f, s * 0.7f, s * 0.8f, s * 0.15f, s * 0.15f, p)
            }
            4 -> { // cartridge
                val path = Path()
                path.moveTo(-s * 0.7f, -s); path.lineTo(s * 0.7f, -s); path.lineTo(s * 0.7f, s * 0.8f)
                path.lineTo(s * 0.5f, s); path.lineTo(-s * 0.5f, s); path.lineTo(-s * 0.7f, s * 0.8f); path.close()
                c.drawPath(path, p)
                c.drawRect(-s * 0.45f, -s * 0.7f, s * 0.45f, s * 0.15f, p)
                for (k in 0..2) c.drawLine(-s * 0.35f + k * s * 0.35f, s * 0.5f, -s * 0.35f + k * s * 0.35f, s * 0.8f, p)
            }
            5 -> { // pixel heart
                val q = s / 3.5f
                val rows = arrayOf(".##.##.", "#######", "#######", ".#####.", "..###..", "...#...")
                for (ry in rows.indices) for (rx in rows[ry].indices) if (rows[ry][rx] == '#') {
                    c.drawRect(-3.5f * q + rx * q, -3f * q + ry * q, -2.5f * q + rx * q, -2f * q + ry * q, p)
                }
            }
            6 -> { // star
                val path = Path()
                for (k in 0 until 10) {
                    val rad = if (k % 2 == 0) s else s * 0.45f
                    val ang = Math.toRadians((k * 36 - 90).toDouble())
                    val px = (kotlin.math.cos(ang) * rad).toFloat()
                    val py = (kotlin.math.sin(ang) * rad).toFloat()
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                c.drawPath(path, p)
            }
            else -> { // sparkle "+"
                c.drawLine(-s * 0.5f, 0f, s * 0.5f, 0f, p)
                c.drawLine(0f, -s * 0.5f, 0f, s * 0.5f, p)
            }
        }
    }

    // ---------- wireframe horizon ----------

    private fun grain(c: Canvas, w: Float, h: Float, seed: Int, amount: Int, alpha: Int) {
        val r = Random(seed)
        val p = Paint()
        for (i in 0 until amount) {
            val v = 120 + r.nextInt(136)
            p.color = android.graphics.Color.argb(r.nextInt(alpha), v, v, v)
            val x = r.nextFloat() * w
            val y = r.nextFloat() * h
            c.drawRect(x, y, x + 1.5f, y + 1.5f, p)
        }
    }

    private fun wireHorizon(c: Canvas, w: Float, h: Float, d: Float) {
        val horizon = h * 0.55f
        val sky = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, horizon,
                intArrayOf(0xFF03040A.toInt(), 0xFF080A1A.toInt(), 0xFF141029.toInt()), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, horizon, sky)
        val ground = Paint().apply {
            shader = LinearGradient(0f, horizon, 0f, h, 0xFF07081A.toInt(), 0xFF020205.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, horizon, w, h, ground)

        // star dust
        val r = Random(31)
        val star = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 120) {
            star.color = android.graphics.Color.argb(40 + r.nextInt(120), 220, 228, 255)
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * horizon * 0.85f, (0.5f + r.nextFloat()) * d * 0.8f, star)
        }

        // low sun with horizontal cuts, muted colours
        val sunR = min(w, h) * 0.2f
        val scx = w * 0.5f
        val scy = horizon - sunR * 0.15f
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(scx, scy, sunR * 2.4f, 0x33D9557A, 0x00D9557A, Shader.TileMode.CLAMP)
        }
        c.drawCircle(scx, scy, sunR * 2.4f, glow)
        val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, scy - sunR, 0f, scy + sunR,
                intArrayOf(0xFFE8B36A.toInt(), 0xFFD9667A.toInt(), 0xFF8A3D7A.toInt()), null, Shader.TileMode.CLAMP)
        }
        c.save()
        c.clipRect(0f, 0f, w, horizon)
        for (i in 0 until 8) {
            val k = i / 8f
            val y = scy - sunR * 0.1f + k * sunR * 1.1f
            c.clipOutRect(scx - sunR, y, scx + sunR, y + sunR * (0.012f + 0.05f * k))
        }
        c.drawCircle(scx, scy, sunR, sun)
        c.restore()

        // wireframe terrain: rows far → near, peaks rise toward the horizon
        val cols = 36
        val rows = 22
        val xs = Array(rows) { FloatArray(cols + 1) }
        val ys = Array(rows) { FloatArray(cols + 1) }
        for (i in 0 until rows) {
            val z = (i + 1f) / rows                 // 0 far … 1 near
            val yBase = horizon + (h - horizon) * z.toDouble().pow(2.2).toFloat()
            val spread = w * (0.35f + 1.9f * z)
            for (j in 0..cols) {
                val u = j.toFloat() / cols - 0.5f
                val ridge = (sin(j * 0.55f + i * 0.35f) + sin(j * 0.21f - i * 0.17f) * 0.8f +
                    sin(j * 1.3f) * 0.25f).coerceAtLeast(0f)
                val side = (abs(u) * 2f).coerceIn(0f, 1f)  // mountains mostly to the sides
                val elev = ridge * (1f - z) * (1f - z) * h * 0.16f * (0.35f + 0.65f * side)
                xs[i][j] = scx + u * spread
                ys[i][j] = yBase - elev
            }
        }
        // occlude the sun behind the terrain: fill under the farthest row
        val fillPath = Path()
        fillPath.moveTo(xs[0][0], ys[0][0])
        for (j in 1..cols) fillPath.lineTo(xs[0][j], ys[0][j])
        fillPath.lineTo(w, h); fillPath.lineTo(0f, h); fillPath.close()
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF05060F.toInt() }
        c.drawPath(fillPath, dark)

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * d
        }
        for (i in 0 until rows) {
            val z = (i + 1f) / rows
            line.color = android.graphics.Color.argb((50 + 150 * z).toInt(), 0x5F, 0xD8, 0xE6)
            val p = Path()
            p.moveTo(xs[i][0], ys[i][0])
            for (j in 1..cols) p.lineTo(xs[i][j], ys[i][j])
            c.drawPath(p, line)
        }
        for (j in 0..cols) {
            val p = Path()
            p.moveTo(xs[0][j], ys[0][j])
            for (i in 1 until rows) p.lineTo(xs[i][j], ys[i][j])
            line.color = 0x665FD8E6
            c.drawPath(p, line)
        }
        // horizon haze line
        val haze = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, horizon - 6 * d, 0f, horizon + 6 * d,
                intArrayOf(0x00D9667A, 0x40D9667A, 0x00D9667A), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, horizon - 6 * d, w, horizon + 6 * d, haze)
        grain(c, w, h, 77, (w * h / 350f).toInt(), 26)
    }

    // ---------- Memphis ----------

    private fun memphis(c: Canvas, w: Float, h: Float, d: Float) {
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, w, h, 0xFF141519.toInt(), 0xFF1B1C22.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, bg)
        val colors = intArrayOf(
            0xCC4E8F8A.toInt(), // teal
            0xCCC97B6B.toInt(), // salmon
            0xCCC9A54B.toInt(), // mustard
            0xCC8B7FB8.toInt(), // lavender
            0xB3D9D4C7.toInt(), // off-white
        )
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = Random(19)
        val cell = 110f * d
        var y = 0f
        while (y < h + cell) {
            var x = 0f
            while (x < w + cell) {
                val cx = x + (r.nextFloat() - 0.5f) * cell * 0.6f
                val cy = y + (r.nextFloat() - 0.5f) * cell * 0.6f
                val s = (22f + r.nextFloat() * 18f) * d
                val col = colors[r.nextInt(colors.size)]
                stroke.color = col
                fill.color = col
                c.save()
                c.translate(cx, cy)
                c.rotate(r.nextFloat() * 360f)
                when (r.nextInt(8)) {
                    0 -> { // squiggle
                        val p = Path()
                        p.moveTo(-s, 0f)
                        var k = 0
                        while (k < 8) {
                            val x0 = -s + k * s / 4f
                            p.quadTo(x0 + s / 8f, if (k % 2 == 0) -s / 3f else s / 3f, x0 + s / 4f, 0f)
                            k++
                        }
                        c.drawPath(p, stroke)
                    }
                    1 -> { // triangle outline
                        val p = Path()
                        p.moveTo(0f, -s * 0.8f); p.lineTo(s * 0.75f, s * 0.5f); p.lineTo(-s * 0.75f, s * 0.5f); p.close()
                        c.drawPath(p, stroke)
                    }
                    2 -> c.drawArc(-s * 0.6f, -s * 0.6f, s * 0.6f, s * 0.6f, 0f, 180f, true, fill) // half circle
                    3 -> { // 3x3 dots
                        for (a in -1..1) for (b in -1..1) c.drawCircle(a * s * 0.35f, b * s * 0.35f, 2.2f * d, fill)
                    }
                    4 -> { // zigzag
                        val p = Path()
                        p.moveTo(-s, s * 0.2f)
                        for (k in 1..6) p.lineTo(-s + k * s / 3f, if (k % 2 == 1) -s * 0.2f else s * 0.2f)
                        c.drawPath(p, stroke)
                    }
                    5 -> c.drawCircle(0f, 0f, s * 0.45f, stroke) // ring
                    6 -> { // parallel bars
                        for (k in -1..1) c.drawLine(-s * 0.5f, k * s * 0.3f, s * 0.5f, k * s * 0.3f, stroke)
                    }
                    else -> { // small cross
                        c.drawLine(-s * 0.3f, -s * 0.3f, s * 0.3f, s * 0.3f, stroke)
                        c.drawLine(s * 0.3f, -s * 0.3f, -s * 0.3f, s * 0.3f, stroke)
                    }
                }
                c.restore()
                x += cell
            }
            y += cell
        }
        val vignette = Paint().apply {
            shader = RadialGradient(w / 2, h / 2, max(w, h) * 0.75f,
                intArrayOf(0x00000000, 0x00000000, 0x8C000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, vignette)
        grain(c, w, h, 91, (w * h / 450f).toInt(), 20)
    }

    // ---------- pixel castle ----------

    private fun castle(c: Canvas, w: Float, h: Float) {
        val u = unit(w, h, 100f)
        val skyBottom = snap(h * 0.8f, u)
        ditheredBands(c, w, 0f, skyBottom, u, intArrayOf(
            0xFF04060F.toInt(), 0xFF070B1E.toInt(), 0xFF0B1230.toInt(), 0xFF101A42.toInt(), 0xFF172354.toInt()))
        // stars, some cross-shaped
        val r = Random(23)
        for (i in 0 until 70) {
            val x = snap(r.nextFloat() * w, u)
            val y = snap(r.nextFloat() * skyBottom * 0.7f, u)
            val col = if (i % 6 == 0) 0xFFFFF3C4.toInt() else 0x99DDE6FF.toInt()
            rect(c, x, y, u, u, col)
            if (i % 9 == 0) {
                rect(c, x - u, y, u, u, 0x66DDE6FF); rect(c, x + u, y, u, u, 0x66DDE6FF)
                rect(c, x, y - u, u, u, 0x66DDE6FF); rect(c, x, y + u, u, u, 0x66DDE6FF)
            }
        }
        // moon with craters
        val mx = snap(w * 0.78f, u)
        val my = snap(h * 0.2f, u)
        pixelCircle(c, mx, my, 10, u, 0xFFE9E3C6.toInt())
        pixelCircle(c, mx - 3 * u, my - 2 * u, 2, u, 0xFFC8C1A2.toInt())
        pixelCircle(c, mx + 4 * u, my + 3 * u, 1, u, 0xFFC8C1A2.toInt())
        pixelCircle(c, mx + 2 * u, my - 5 * u, 1, u, 0xFFC8C1A2.toInt())

        // distant hills
        var x = 0f
        while (x < w) {
            val top = snap(h * 0.66f - abs(sin(x / w * 4.2f)) * h * 0.08f, u)
            rect(c, x, top, u, h - top, 0xFF0E1638.toInt())
            x += u
        }
        // the castle's hill
        val hillTop = snap(h * 0.62f, u)
        val cx = snap(w * 0.36f, u)
        x = 0f
        while (x < w) {
            val dx = (x - cx) / (w * 0.32f)
            val top = snap(hillTop + dx * dx * h * 0.22f, u).coerceAtLeast(hillTop)
            rect(c, x, top, u, h - top, 0xFF080C22.toInt())
            x += u
        }
        // castle
        val wall = 0xFF05081A.toInt()
        val base = hillTop
        rect(c, cx - 18 * u, base - 14 * u, 36 * u, 14 * u, wall)            // main wall
        for (k in 0 until 9) rect(c, cx - 18 * u + k * 4 * u, base - 16 * u, 2 * u, 2 * u, wall) // battlements
        fun tower(tx: Float, tw: Int, th: Int) {
            rect(c, tx, base - th * u, tw * u, th * u, wall)
            // stepped pointed roof
            for (j in 0 until tw / 2 + 1) rect(c, tx + j * u, base - (th + j) * u, (tw - 2 * j) * u, u, wall)
        }
        tower(cx - 24 * u, 8, 24)
        tower(cx + 16 * u, 8, 22)
        tower(cx - 4 * u, 8, 32)
        // flag on the tallest tower
        rect(c, cx, base - 42 * u, u, 6 * u, wall)
        rect(c, cx + u, base - 42 * u, 4 * u, 2 * u, 0xFFE0405A.toInt())
        // lit windows
        val lit = 0xFFFFD36E.toInt()
        rect(c, cx - 21 * u, base - 18 * u, u, 2 * u, lit)
        rect(c, cx - 1 * u, base - 26 * u, u, 2 * u, lit)
        rect(c, cx + 1 * u, base - 20 * u, u, 2 * u, lit)
        rect(c, cx + 19 * u, base - 16 * u, u, 2 * u, lit)
        rect(c, cx - 8 * u, base - 8 * u, u, 2 * u, lit)
        rect(c, cx + 8 * u, base - 8 * u, u, 2 * u, lit)
        // gate
        rect(c, cx - 2 * u, base - 5 * u, 4 * u, 5 * u, 0xFF1A1205.toInt())
        // path down the hill
        var py = base
        var pxp = cx
        pix.color = 0xFF141A3A.toInt()
        while (py < h) {
            c.drawRect(pxp - u, py, pxp + 2 * u, py + u, pix)
            py += u
            pxp += u
        }
        // fireflies
        for (i in 0 until 12) {
            val fx = snap(w * (0.55f + r.nextFloat() * 0.4f), u)
            val fy = snap(h * (0.78f + r.nextFloat() * 0.18f), u)
            rect(c, fx, fy, u, u, 0xCCD9FF7A.toInt())
        }
    }
}
