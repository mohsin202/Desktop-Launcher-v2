package com.android.desktoplauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Settings of one "special" clock (saved per widget id). */
class ClockCfg(
    var weight: Int = 500,            // font weight 100..900
    var style: Int = 3,               // 0 Material You, 1 transparent, 2 glass, 3 colour
    var alpha: Int = 60,              // transparency (percent opaque) for style 1
    var color: Int = 0xFFFFFFFF.toInt(),
    var seconds: Boolean = false
) {
    fun encode() = "$weight,$style,$alpha,$color,${if (seconds) 1 else 0}"
    fun copy() = ClockCfg(weight, style, alpha, color, seconds)

    companion object {
        fun key(wid: Int) = "fclk_$wid"
        fun decode(s: String?): ClockCfg {
            val p = s?.split(",") ?: return ClockCfg()
            if (p.size < 5) return ClockCfg()
            return ClockCfg(
                p[0].toIntOrNull()?.coerceIn(100, 900) ?: 500, p[1].toIntOrNull()?.coerceIn(0, 3) ?: 3,
                p[2].toIntOrNull()?.coerceIn(5, 100) ?: 60, p[3].toIntOrNull() ?: 0xFFFFFFFF.toInt(), p[4] == "1"
            )
        }
        fun load(c: Context, wid: Int) = decode(Prefs.getStr(c, key(wid)))
        fun save(c: Context, wid: Int, cfg: ClockCfg) = Prefs.setStr(c, key(wid), cfg.encode())
    }
}

/** Passes the desktop picture (without the clock) to the edit page. */
object WidgetEditState {
    var snapshot: Bitmap? = null
    var dw = 1
    var dh = 1
    var fx = 0f
    var fy = 0f
    var fw = 1f
    var fh = 1f
}

private fun lineParts(is24: Boolean, cal: Calendar): IntArray {
    var h = cal.get(Calendar.HOUR_OF_DAY)
    if (!is24) { h %= 12; if (h == 0) h = 12 }
    return intArrayOf(h, cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND))
}

/**
 * Digit shapes of the special clock: rounded-rectangle numerals made of straight lines and quarter circles, drawn with ONE
 * stroke thickness everywhere. When the box gets taller or wider only the straight parts get longer; the corners stay true
 * circles of the same radius, so the outer and inner outlines always run parallel and the digit never looks pulled.
 * A very narrow digit turns its corners into half circles (square -> round), exactly like a real rounded typeface.
 */
object ClockGlyphs {
    /** Width of a digit cell relative to a normal digit ("1" is narrower). */
    fun factor(ch: Char) = if (ch == '1') 0.55f else 1f

    private fun Path.arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float = 90f) {
        arcTo(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep)
    }

    private fun Path.box(x0: Float, y0: Float, x1: Float, y1: Float, r: Float) {
        addRoundRect(RectF(x0, y0, x1, y1), r, r, Path.Direction.CW)
    }

    /** Centre line of digit [ch] in a w x h box (origin top-left) for a stroke of thickness [s]. */
    fun build(ch: Char, w: Float, h: Float, s: Float): Path {
        val p = Path()
        // Corner radius of the centre line: 1.6 x stroke for a normal digit. A taller (narrower) digit gets rounder corners,
        // up to half circles; a wider digit gets squarer corners.
        val aspect = h / maxOf(w, 1f)
        val nat = 1.25f
        val base = 1.6f * s
        val rc = if (aspect >= nat) {
            val t = minOf(1f, (aspect - nat) / 1.2f)
            base + (maxOf(w / 2, base) - base) * t
        } else base * maxOf(0.4f, aspect / nat)
        when (ch) {
            '0' -> p.box(0f, 0f, w, h, minOf(rc, w / 2, h / 2))
            '1' -> { p.moveTo(0f, minOf(h * .16f, w * .9f)); p.lineTo(w, 0f); p.lineTo(w, h) }
            '2' -> {
                val r = minOf(rc, w / 2, h / 4); val ym = h * .5f
                p.moveTo(0f, r); p.arc(r, r, r, 180f); p.lineTo(w - r, 0f); p.arc(w - r, r, r, 270f)
                p.lineTo(w, ym - r); p.arc(w - r, ym - r, r, 0f); p.lineTo(r, ym); p.arc(r, ym + r, r, 270f, -90f)
                p.lineTo(0f, h); p.lineTo(w, h)
            }
            '3' -> {
                val r = minOf(rc, w / 2, h / 4); val ym = h * .5f
                p.moveTo(0f, r); p.arc(r, r, r, 180f); p.lineTo(w - r, 0f); p.arc(w - r, r, r, 270f)
                p.lineTo(w, ym - r); p.arc(w - r, ym - r, r, 0f); p.lineTo(w * .4f, ym)
                p.moveTo(w - r, ym); p.arc(w - r, ym + r, r, 270f); p.lineTo(w, h - r); p.arc(w - r, h - r, r, 0f)
                p.lineTo(r, h); p.arc(r, h - r, r, 90f)
            }
            '4' -> {
                val r = minOf(rc, w / 2, h / 4); val ym = h * .62f
                p.moveTo(0f, 0f); p.lineTo(0f, ym - r); p.arc(r, ym - r, r, 180f, -90f); p.lineTo(w, ym)
                p.moveTo(.72f * w, 0f); p.lineTo(.72f * w, h)
            }
            '5' -> {
                val r = minOf(rc, w / 2, h / 4); val yb = h * .45f
                p.moveTo(w, 0f); p.lineTo(0f, 0f); p.lineTo(0f, yb); p.lineTo(w - r, yb)
                p.arc(w - r, yb + r, r, 270f); p.lineTo(w, h - r); p.arc(w - r, h - r, r, 0f)
                p.lineTo(r, h); p.arc(r, h - r, r, 90f)
            }
            '6', '9' -> {
                val r = minOf(rc, w / 2, h * .28f); val lh = h * .55f
                p.box(0f, 0f, w, lh, minOf(r, lh / 2))
                p.moveTo(w, lh * .5f); p.lineTo(w, h - r); p.arc(w - r, h - r, r, 0f)
                p.lineTo(r, h); p.arc(r, h - r, r, 90f)
                if (ch == '6') { val m = Matrix(); m.postRotate(180f, w / 2, h / 2); p.transform(m) }
            }
            '7' -> { p.moveTo(0f, 0f); p.lineTo(w, 0f); p.lineTo(w * .32f, h) }
            '8' -> {
                p.box(0f, 0f, w, h * .48f, minOf(rc, w / 2, h * .24f))
                p.box(0f, h * .48f, w, h, minOf(rc, w / 2, h * .26f))
            }
        }
        return p
    }
}

/**
 * The special clock. Digits fill the box: a wide box gives wide digits, a tall box tall digits (see ClockGlyphs).
 * When the box is very tall the numbers stack (HH over MM) because that keeps the digits looking natural.
 */
class SpecialClockView(ctx: Context, cfg: ClockCfg) : View(ctx) {
    var cfg: ClockCfg = cfg
        private set
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER; strokeMiter = 3f
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            if (isAttachedToWindow) handler.postDelayed(this, nextDelay())
        }
    }

    private fun nextDelay(): Long {
        val now = System.currentTimeMillis()
        return if (this.cfg.seconds) 1000 - now % 1000 + 20 else 60000 - now % 60000 + 50
    }

    fun apply(c: ClockCfg) {
        cfg = c
        handler.removeCallbacks(tick)
        invalidate()
        if (isAttachedToWindow) handler.postDelayed(tick, nextDelay())
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.removeCallbacks(tick); handler.post(tick) }
    override fun onDetachedFromWindow() { handler.removeCallbacks(tick); super.onDetachedFromWindow() }

    private fun colorOf(kind: Int): Int {          // kind: 0 hour, 1 colon, 2 minute, 4 second
        val c = context
        return when (cfg.style) {
            0 -> when (kind) {
                0 -> Colors.iconFg(c)
                2 -> Colors.accent(c)
                4 -> (Colors.accent(c) and 0x00FFFFFF) or 0xB0000000.toInt()
                else -> if (Colors.night(c)) (Colors.accent(c) and 0x00FFFFFF) or 0x99000000.toInt() else Colors.iconBg(c)
            }
            1 -> ((cfg.alpha.coerceIn(5, 100) * 255 / 100) shl 24) or 0x00FFFFFF
            else -> cfg.color
        }
    }

    /** One item of a line: a digit, or ':' (two dots). kind = colour group. */
    private class Item(val ch: Char, val kind: Int) { fun f() = if (ch == ':') 0.3f else ClockGlyphs.factor(ch) }

    private fun units(line: List<Item>, nominal: Boolean) =
        line.sumOf { (if (nominal) (if (it.ch == ':') 0.3f else 1f) else it.f()).toDouble() }.toFloat() + 0.16f * (line.size - 1)

    override fun onDraw(canvas: Canvas) {
        val pad = context.dp(4).toFloat()
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        if (aw <= 8 || ah <= 8) return
        val v = lineParts(DateFormat.is24HourFormat(context), Calendar.getInstance())
        fun two(n: Int) = "%02d".format(n)
        fun digits(s: String, kind: Int) = s.map { Item(it, kind) }

        val row = ArrayList<Item>()
        row += digits(v[0].toString(), 0); row += Item(':', 1); row += digits(two(v[1]), 2)
        if (cfg.seconds) { row += Item(':', 1); row += digits(two(v[2]), 4) }
        val stack = ArrayList<List<Item>>()
        stack += digits(two(v[0]), 0); stack += digits(two(v[1]), 2)
        if (cfg.seconds) stack += digits(two(v[2]), 4)

        // choose the layout whose digits are closest to natural proportions (width : height = 0.8)
        fun score(lines: List<List<Item>>): Float {
            val n = lines.size
            val ch = ah / (n + 0.2f * (n - 1))
            val cw = aw / lines.maxOf { units(it, true) }
            return Math.abs(Math.log((cw / ch / 0.8f).toDouble())).toFloat()
        }
        val lines: List<List<Item>> = if (score(listOf(row)) <= score(stack)) listOf(row) else stack
        val n = lines.size
        val ch = ah / (n + 0.2f * (n - 1))
        val vgap = ch * 0.2f
        val cw = aw / lines.maxOf { units(it, false) }
        // one stroke thickness for every part of every digit; the weight slider changes it
        val k = 0.10f + 0.20f * (cfg.weight.coerceIn(100, 900) - 100) / 800f
        val sw = minOf(k * minOf(cw, ch * 0.8f), cw * 0.3f).coerceAtLeast(context.dp(1).toFloat())
        stroke.strokeWidth = sw
        edge.strokeWidth = maxOf(context.dp(1).toFloat(), sw * 0.05f)

        var y = pad
        for (line in lines) {
            var x = pad + (aw - units(line, false) * cw) / 2f
            for (it in line) {
                val w = it.f() * cw
                if (it.ch == ':') {
                    for (yy in floatArrayOf(0.33f, 0.67f)) dot(canvas, x + w / 2f, y + yy * ch, sw * 0.6f, it.kind, y, ch)
                } else {
                    canvas.save()
                    canvas.clipRect(x, y, x + w, y + ch)
                    val p = ClockGlyphs.build(it.ch, maxOf(1f, w - sw), maxOf(1f, ch - sw), sw)
                    p.offset(x + sw / 2f, y + sw / 2f)
                    paintPath(canvas, p, it.kind, y, ch)
                    canvas.restore()
                }
                x += w + 0.16f * cw
            }
            y += ch + vgap
        }
    }

    private fun paintPath(canvas: Canvas, p: Path, kind: Int, top: Float, h: Float) {
        if (cfg.style == 2) {
            val out = Path()
            stroke.color = 0xFFFFFFFF.toInt()
            stroke.getFillPath(p, out)
            try { out.op(out, Path.Op.UNION) } catch (_: Throwable) {}
            fill.style = Paint.Style.FILL
            fill.shader = LinearGradient(0f, top, 0f, top + h, 0xB0FFFFFF.toInt(), 0x30FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawPath(out, fill)
            fill.shader = null
            edge.color = 0xE6FFFFFF.toInt()
            canvas.drawPath(out, edge)
        } else {
            stroke.color = colorOf(kind)
            canvas.drawPath(p, stroke)
        }
    }

    private fun dot(canvas: Canvas, cx: Float, cy: Float, r: Float, kind: Int, top: Float, h: Float) {
        fill.style = Paint.Style.FILL
        if (cfg.style == 2) {
            fill.shader = LinearGradient(0f, top, 0f, top + h, 0xB0FFFFFF.toInt(), 0x30FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, fill)
            fill.shader = null
            edge.color = 0xE6FFFFFF.toInt()
            canvas.drawCircle(cx, cy, r, edge)
        } else {
            fill.shader = null
            fill.color = colorOf(kind)
            canvas.drawCircle(cx, cy, r, fill)
        }
    }
}

/**
 * Container of the special clock on the desktop. Unlike the grid widgets it has a free pixel position
 * and size (stored as fractions of the desktop), no limits, and it sits BEHIND the icons.
 * Long-press shows the resize frame, long-press again shows Edit / Remove.
 */
class FreeBox(ctx: Context, val wid: Int, val clock: SpecialClockView, private val owner: DesktopView) : FrameLayout(ctx) {
    var fx = 0.1f
    var fy = 0.1f
    var fw = 0.8f
    var fh = 0.3f

    private var resizeMode = false
    private var mode = 0                // 0 none, 1 move, 2 resize
    private var edges = 0
    private var moved = false
    private var longDone = false
    private var sx = 0f
    private var sy = 0f
    private var sl = 0f
    private var st = 0f
    private var sr = 0f
    private var sb = 0f
    private var hoverEdges = 0

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.accent(ctx); style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat() }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.accent(ctx); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = ctx.dp(4).toFloat()
    }

    init {
        addView(clock, LayoutParams(MATCH, MATCH))
        setWillNotDraw(false)
    }

    companion object { const val L = 1; const val R = 2; const val T = 4; const val B = 8 }

    fun startEdit() { resizeMode = true; invalidate() }
    fun endEdit() { if (resizeMode) { resizeMode = false; invalidate() } }

    private fun edgesAt(x: Float, y: Float): Int {
        val m = minOf(width, height).toFloat()
        val cz = minOf(context.dp(44).toFloat(), m / 3f)
        val ez = minOf(context.dp(22).toFloat(), m / 4f)
        val l = x <= cz; val r = x >= width - cz; val t = y <= cz; val b = y >= height - cz
        if ((l || r) && (t || b)) return (if (l) L else R) or (if (t) T else B)
        var f = 0
        if (x <= ez) f = f or L
        if (x >= width - ez) f = f or R
        if (y <= ez) f = f or T
        if (y >= height - ez) f = f or B
        return f
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (mode == 2) return
            longDone = true
            if (!resizeMode) { resizeMode = true; owner.endWidgetEdit(this@FreeBox); invalidate() }
            else { mode = 0; owner.freeMenu(this@FreeBox, e.rawX.toInt(), e.rawY.toInt()) }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    })

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE || ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
            val h = edgesAt(ev.x, ev.y)
            if (h != hoverEdges) { hoverEdges = h; invalidate() }
        } else if (ev.actionMasked == MotionEvent.ACTION_HOVER_EXIT && hoverEdges != 0) { hoverEdges = 0; invalidate() }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val pw = (parent as? View)?.width?.toFloat() ?: return true
        val ph = (parent as? View)?.height?.toFloat() ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = ev.rawX; sy = ev.rawY
                sl = left.toFloat(); st = top.toFloat(); sr = right.toFloat(); sb = bottom.toFloat()
                moved = false; longDone = false; mode = 0
                if ((ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                    longDone = true; owner.freeMenu(this, ev.rawX.toInt(), ev.rawY.toInt()); return true
                }
                val mouse = ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
                val e = edgesAt(ev.x, ev.y)
                if ((resizeMode || mouse) && e != 0) {
                    if (!resizeMode) { resizeMode = true; owner.endWidgetEdit(this); invalidate() }
                    edges = e; mode = 2
                } else if (resizeMode) mode = 1
                gd.onTouchEvent(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode != 2) gd.onTouchEvent(ev)
                if (mode == 0) return true
                val dx = ev.rawX - sx
                val dy = ev.rawY - sy
                if (!moved && Math.abs(dx) < context.dp(6) && Math.abs(dy) < context.dp(6)) return true
                moved = true
                val minW = context.dp(64).toFloat()
                val keep = context.dp(48).toFloat()
                var l = sl; var t = st; var r = sr; var b = sb
                if (mode == 1) {
                    val w = sr - sl; val h = sb - st
                    l = (sl + dx).coerceIn(keep - w, pw - keep); t = (st + dy).coerceIn(keep - h, ph - keep)
                    r = l + w; b = t + h
                } else {
                    if (edges and L != 0) l = (sl + dx).coerceAtMost(sr - minW)
                    if (edges and R != 0) r = (sr + dx).coerceAtLeast(sl + minW)
                    if (edges and T != 0) t = (st + dy).coerceAtMost(sb - minW)
                    if (edges and B != 0) b = (sb + dy).coerceAtLeast(st + minW)
                    // keep a visible part on the screen, nothing else is limited
                    if (l > pw - keep) l = pw - keep
                    if (r < keep) r = keep
                    if (t > ph - keep) t = ph - keep
                    if (b < keep) b = keep
                }
                fx = l / pw; fy = t / ph; fw = (r - l) / pw; fh = (b - t) / ph
                owner.requestLayout()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gd.onTouchEvent(ev)
                val wasMoved = moved
                val m = mode
                mode = 0
                if (wasMoved) owner.freeSave()
                else if (!longDone && m != 2 && ev.actionMasked == MotionEvent.ACTION_UP) owner.emptyTap()
            }
        }
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val dens = context.dp(1).toFloat()
        if (resizeMode) {
            val inset = 2 * dens
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, 12 * dens, 12 * dens, edgePaint)
            // edge grips
            val len = minOf(14 * dens, minOf(width, height) / 6f)
            val m = 6 * dens
            canvas.drawLine(width / 2f - len, m, width / 2f + len, m, handlePaint)
            canvas.drawLine(width / 2f - len, height - m, width / 2f + len, height - m, handlePaint)
            canvas.drawLine(m, height / 2f - len, m, height / 2f + len, handlePaint)
            canvas.drawLine(width - m, height / 2f - len, width - m, height / 2f + len, handlePaint)
        }
        for (i in 0..3) {
            val right = i % 2 == 1; val bottom = i >= 2
            val want = (if (right) R else L) or (if (bottom) B else T)
            if (!resizeMode && hoverEdges != want) continue
            val rad = 16 * dens
            val m = 8 * dens
            val cx = if (!right) m + rad else width - m - rad
            val cy = if (!bottom) m + rad else height - m - rad
            val start = when (i) { 0 -> 185f; 1 -> 275f; 2 -> 95f; else -> 5f }
            canvas.drawArc(RectF(cx - rad, cy - rad, cx + rad, cy + rad), start, 80f, false, handlePaint)
        }
    }
}

/** Material You digital clock, big "H:MM" with a round colon and the date below. [outline] draws the minutes as an outline. */
class BigDigitalClock(ctx: Context, private val outline: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val b = Rect()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            if (isAttachedToWindow) handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000 + 50)
        }
    }

    init {
        p.typeface = Typeface.create(Typeface.DEFAULT, 900, false)
        dp.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        dp.color = 0xFFFFFFFF.toInt()
        dp.setShadowLayer(ctx.dp(3).toFloat(), 0f, ctx.dp(1).toFloat(), 0x80000000.toInt())
        p.strokeJoin = Paint.Join.ROUND
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.removeCallbacks(tick); handler.post(tick) }
    override fun onDetachedFromWindow() { handler.removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val pad = context.dp(6).toFloat()
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        if (aw <= 4 || ah <= 4) return
        val v = lineParts(DateFormat.is24HourFormat(context), Calendar.getInstance())
        val hs = v[0].toString()
        val ms = "%02d".format(v[1])
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMMd")
        val date = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

        p.textSize = 100f
        p.style = Paint.Style.FILL
        p.getTextBounds("0", 0, 1, b)
        val dh = b.height().toFloat().coerceAtLeast(1f)
        val top0 = b.top.toFloat()
        val colonW = dh * 0.30f
        val gap = dh * 0.04f
        val wh = p.measureText(hs)
        val wm = p.measureText(ms)
        val timeW = wh + gap + colonW + gap + wm
        dp.textSize = dh * 0.17f
        val dateW = dp.measureText(date)
        val dateGap = dh * 0.16f
        val total = dh + dateGap + dh * 0.17f
        val scale = minOf(aw / maxOf(timeW, dateW), ah / total)

        val night = Colors.night(context)
        val hourC = Colors.iconFg(context)
        val minC = Colors.accent(context)
        val dotC = if (night) (Colors.accent(context) and 0x00FFFFFF) or 0x99000000.toInt() else Colors.iconBg(context)

        val blockH = total * scale
        val topY = pad + (ah - blockH) / 2f
        val base = topY - top0 * scale
        val x0 = pad + (aw - timeW * scale) / 2f
        p.textSize = 100f * scale
        p.color = hourC
        canvas.drawText(hs, x0, base, p)
        val cx = x0 + (wh + gap) * scale + colonW * scale / 2f
        p.color = dotC
        val r = dh * 0.075f * scale
        canvas.drawCircle(cx, topY + dh * scale * 0.33f, r, p)
        canvas.drawCircle(cx, topY + dh * scale * 0.67f, r, p)
        val mx = x0 + (wh + gap + colonW + gap) * scale
        p.color = minC
        if (outline) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = dh * scale * 0.045f
            canvas.drawText(ms, mx, base, p)
            p.style = Paint.Style.FILL
        } else canvas.drawText(ms, mx, base, p)
        dp.textSize = dh * 0.17f * scale
        canvas.drawText(date, pad + (aw - dateW * scale) / 2f, topY + (dh + dateGap) * scale + dh * 0.17f * scale * 0.85f, dp)
    }
}
