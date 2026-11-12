package com.android.desktopfiles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** The player's own icons; anything not drawn here comes from [MenuIcon]. Same 24 x 24 grid, a little thicker so they read on video. */
class PlayerIcon(private val kind: String, private val color: Int, private val sizePx: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 1.9f; color = this@PlayerIcon.color }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = this@PlayerIcon.color }
    private val inner = MenuIcon(kind, color, sizePx, 1.9f)

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun onBoundsChange(b: android.graphics.Rect) { inner.bounds = b }

    private fun line(c: Canvas, a: Float, b: Float, x: Float, y: Float) = c.drawLine(a, b, x, y, stroke)
    private fun arrow(c: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        // a small arrow head at (x, y) pointing along (dx, dy)
        val l = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat(); val ux = dx / l; val uy = dy / l
        line(c, x, y, x - ux * 3.2f - uy * 2.2f, y - uy * 3.2f + ux * 2.2f); line(c, x, y, x - ux * 3.2f + uy * 2.2f, y - uy * 3.2f - ux * 2.2f)
    }

    private fun loop(c: Canvas) {
        val p = Path(); p.moveTo(5f, 11f); p.lineTo(5f, 9.5f); p.quadTo(5f, 7f, 7.5f, 7f); p.lineTo(18.5f, 7f); c.drawPath(p, stroke); arrow(c, 18.5f, 7f, 1f, 0f)
        val q = Path(); q.moveTo(19f, 13f); q.lineTo(19f, 14.5f); q.quadTo(19f, 17f, 16.5f, 17f); q.lineTo(5.5f, 17f); c.drawPath(q, stroke); arrow(c, 5.5f, 17f, -1f, 0f)
    }

    private fun replay(c: Canvas, mirror: Boolean) {
        c.save()
        if (mirror) { c.translate(24f, 0f); c.scale(-1f, 1f) }
        c.drawArc(RectF(4.5f, 5.5f, 19.5f, 20.5f), -90f, 270f, false, stroke)
        line(c, 12f, 5.5f, 15.2f, 3.3f); line(c, 12f, 5.5f, 15.2f, 7.7f)
        c.restore()
        fillP.textSize = 7.2f; fillP.textAlign = Paint.Align.CENTER; fillP.isFakeBoldText = true
        c.drawText("10", 12f, 15.6f, fillP)
    }

    override fun draw(c: Canvas) {
        val own = when (kind) {
            "shuffle", "repeat_all", "repeat_off", "repeat_one", "rew10", "fwd10", "fit_best", "fit_stretch", "fit_crop", "fit_100",
            "playlist", "note", "handle", "plus", "minus", "bright", "volume", "check", "pause_f", "play_f", "reset", "rot", "rot_lock" -> true
            else -> false
        }
        if (!own) { inner.draw(c); return }
        val b = bounds
        c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(b.width() / 24f, b.height() / 24f)
        when (kind) {
            "shuffle" -> {
                val a = Path(); a.moveTo(3.5f, 17f); a.lineTo(7f, 17f); a.cubicTo(11f, 17f, 12f, 7f, 16f, 7f); a.lineTo(19.5f, 7f); c.drawPath(a, stroke); arrow(c, 19.5f, 7f, 1f, 0f)
                val d = Path(); d.moveTo(3.5f, 7f); d.lineTo(7f, 7f); d.cubicTo(8.5f, 7f, 9.3f, 8.2f, 10f, 9.4f); c.drawPath(d, stroke)
                val e = Path(); e.moveTo(14f, 14.6f); e.cubicTo(14.7f, 15.8f, 15.5f, 17f, 17f, 17f); e.lineTo(19.5f, 17f); c.drawPath(e, stroke); arrow(c, 19.5f, 17f, 1f, 0f)
            }
            "repeat_all" -> loop(c)
            "repeat_off" -> { loop(c); line(c, 4f, 20f, 20f, 4f) }
            "repeat_one" -> { loop(c); fillP.textSize = 8f; fillP.textAlign = Paint.Align.CENTER; fillP.isFakeBoldText = true; c.drawText("1", 12f, 14.6f, fillP) }
            "rew10" -> replay(c, false)
            "fwd10" -> replay(c, true)
            "fit_best" -> { c.drawRoundRect(3f, 5.5f, 21f, 18.5f, 2f, 2f, stroke); c.drawRoundRect(8f, 9f, 16f, 15f, 1f, 1f, stroke) }
            "fit_stretch" -> {
                c.drawRoundRect(3f, 5.5f, 21f, 18.5f, 2f, 2f, stroke)
                line(c, 7f, 12f, 17f, 12f); arrow(c, 17f, 12f, 1f, 0f); arrow(c, 7f, 12f, -1f, 0f)
            }
            "fit_crop" -> {
                val a = Path(); a.moveTo(7f, 3f); a.lineTo(7f, 17f); a.lineTo(21f, 17f); c.drawPath(a, stroke)
                val d = Path(); d.moveTo(3f, 7f); d.lineTo(17f, 7f); d.lineTo(17f, 21f); c.drawPath(d, stroke)
            }
            "fit_100" -> { c.drawRoundRect(3f, 5.5f, 21f, 18.5f, 2f, 2f, stroke); fillP.textSize = 6.6f; fillP.textAlign = Paint.Align.CENTER; fillP.isFakeBoldText = true; c.drawText("1:1", 12f, 14.3f, fillP) }
            "playlist" -> {
                line(c, 4f, 7f, 20f, 7f); line(c, 4f, 12f, 12f, 12f); line(c, 4f, 17f, 12f, 17f)
                val t = Path(); t.moveTo(15f, 12.5f); t.lineTo(15f, 20f); t.lineTo(21f, 16.2f); t.close(); c.drawPath(t, fillP)
            }
            "note" -> {
                c.drawCircle(8f, 17.5f, 3f, fillP); line(c, 10.9f, 17.5f, 10.9f, 4.5f)
                val f = Path(); f.moveTo(10.9f, 4.5f); f.cubicTo(13f, 6.5f, 17f, 6f, 18f, 10.5f); c.drawPath(f, stroke)
            }
            "handle" -> { line(c, 5f, 9f, 19f, 9f); line(c, 5f, 15f, 19f, 15f) }
            "plus" -> { line(c, 5f, 12f, 19f, 12f); line(c, 12f, 5f, 12f, 19f) }
            "minus" -> line(c, 5f, 12f, 19f, 12f)
            "check" -> { val p = Path(); p.moveTo(5f, 12.5f); p.lineTo(10f, 17.5f); p.lineTo(19f, 7f); c.drawPath(p, stroke) }
            "pause_f" -> { c.drawRoundRect(6f, 4.5f, 10.5f, 19.5f, 1.4f, 1.4f, fillP); c.drawRoundRect(13.5f, 4.5f, 18f, 19.5f, 1.4f, 1.4f, fillP) }
            "play_f" -> { val t = Path(); t.moveTo(7f, 4.5f); t.lineTo(7f, 19.5f); t.lineTo(19.5f, 12f); t.close(); c.drawPath(t, fillP) }
            "bright" -> {
                c.drawCircle(12f, 12f, 3.8f, fillP)
                for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); line(c, (12 + 6.6 * Math.cos(a)).toFloat(), (12 + 6.6 * Math.sin(a)).toFloat(), (12 + 9 * Math.cos(a)).toFloat(), (12 + 9 * Math.sin(a)).toFloat()) }
            }
            "volume" -> {
                val s = Path(); s.moveTo(4f, 9.5f); s.lineTo(8f, 9.5f); s.lineTo(12.5f, 5.5f); s.lineTo(12.5f, 18.5f); s.lineTo(8f, 14.5f); s.lineTo(4f, 14.5f); s.close(); c.drawPath(s, fillP)
                c.drawArc(RectF(11f, 8f, 17f, 16f), -50f, 100f, false, stroke); c.drawArc(RectF(10f, 4.5f, 21f, 19.5f), -50f, 100f, false, stroke)
            }
            "rot", "rot_lock" -> {
                // a tilted phone between two curved arrows; with the lock, the phone is held in place by the system's rotation lock
                c.save(); c.rotate(38f, 12f, 12f)
                c.drawRoundRect(8.3f, 6f, 15.7f, 18f, 1.8f, 1.8f, stroke); c.drawCircle(10.4f, 8.2f, 0.7f, fillP)
                c.restore()
                c.drawArc(RectF(2.5f, 2.5f, 21.5f, 21.5f), 180f, -85f, false, stroke)
                val a1 = Math.toRadians(95.0); arrow(c, (12 + 9.5 * Math.cos(a1)).toFloat(), (12 + 9.5 * Math.sin(a1)).toFloat(), 1f, 0.1f)
                if (kind == "rot") {
                    c.drawArc(RectF(2.5f, 2.5f, 21.5f, 21.5f), 0f, -85f, false, stroke)
                    val a2 = Math.toRadians(275.0); arrow(c, (12 + 9.5 * Math.cos(a2)).toFloat(), (12 + 9.5 * Math.sin(a2)).toFloat(), -1f, -0.1f)
                } else {
                    c.drawRoundRect(15.6f, 3.6f, 21.2f, 8.4f, 1f, 1f, fillP)
                    val sh = Path(); sh.moveTo(16.9f, 3.8f); sh.lineTo(16.9f, 2.6f); sh.quadTo(16.9f, 1.2f, 18.4f, 1.2f); sh.quadTo(19.9f, 1.2f, 19.9f, 2.6f); sh.lineTo(19.9f, 3.8f); c.drawPath(sh, stroke)
                }
            }
            "reset" -> {
                c.drawArc(RectF(4.5f, 4.5f, 19.5f, 19.5f), 160f, 280f, false, stroke)
                line(c, 3.8f, 8f, 4.4f, 12.6f); line(c, 3.8f, 8f, 8.4f, 8.8f)
                line(c, 12f, 8f, 12f, 12.4f); line(c, 12f, 12.4f, 15f, 14f)
            }
        }
        c.restore()
    }

    override fun setAlpha(a: Int) { stroke.alpha = a; fillP.alpha = a; inner.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) { stroke.colorFilter = cf; fillP.colorFilter = cf; inner.colorFilter = cf }
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Centres and scales its one child (the video) according to a fit mode, given the video's own pixel size. */
class AspectBox(c: Context) : FrameLayout(c) {
    enum class Mode { BEST, STRETCH, CROP, ORIGINAL }
    var videoW = 0; var videoH = 0
    var mode = Mode.BEST
        set(v) { field = v; requestLayout() }

    fun setVideoSize(w: Int, h: Int) { videoW = w; videoH = h; requestLayout() }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        super.onMeasure(wSpec, hSpec)
        val cw = MeasureSpec.getSize(wSpec); val ch = MeasureSpec.getSize(hSpec)
        val kid = getChildAt(0) ?: return
        var w = cw; var h = ch
        if (videoW > 0 && videoH > 0) when (mode) {
            Mode.STRETCH -> {}
            Mode.ORIGINAL -> { w = videoW; h = videoH }
            Mode.CROP -> { val s = max(cw.toFloat() / videoW, ch.toFloat() / videoH); w = (videoW * s).toInt(); h = (videoH * s).toInt() }
            Mode.BEST -> { val s = min(cw.toFloat() / videoW, ch.toFloat() / videoH); w = (videoW * s).toInt(); h = (videoH * s).toInt() }
        }
        kid.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val kid = getChildAt(0) ?: return
        val w = kid.measuredWidth; val h = kid.measuredHeight
        val x = (r - l - w) / 2; val y = (b - t - h) / 2
        kid.layout(x, y, x + w, y + h)
    }
}

/** A background / foreground that lightens under the mouse pointer and ripples when pressed. */
internal fun hoverBg(c: Context, radius: Float, oval: Boolean): Drawable {
    fun shape(col: Int): GradientDrawable { val g = GradientDrawable(); if (oval) g.shape = GradientDrawable.OVAL else g.cornerRadius = radius; g.setColor(col); return g }
    val hov = android.graphics.drawable.StateListDrawable()
    hov.addState(intArrayOf(android.R.attr.state_hovered), shape(0x33FFFFFF))
    hov.addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
    return android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x44FFFFFF), hov, shape(Color.BLACK))
}

internal fun lighten(c: Int, f: Float): Int = Color.rgb(
    (Color.red(c) + (255 - Color.red(c)) * f).toInt(), (Color.green(c) + (255 - Color.green(c)) * f).toInt(), (Color.blue(c) + (255 - Color.blue(c)) * f).toInt())

/** The upright brightness / volume bar: a rounded strip with the number on top, the icon underneath and a fill from the bottom. */
class VSlider(c: Context, accent: Int, private val icon: String) : View(c) {
    var value = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1C262D.toInt() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A464E.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lighten(accent, 0.5f) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = c.dpf(15f) }
    private val ic = PlayerIcon(icon, Color.WHITE, c.dp(24))

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        cv.drawRoundRect(0f, 0f, w, h, w / 2, w / 2, bg)
        val tx = w * 0.2f; val ty = context.dpf(44f); val by = h - context.dpf(46f)
        val r = (w - 2 * tx) / 2
        cv.drawRoundRect(tx, ty, w - tx, by, r, r, track)
        val top = by - (by - ty) * value / 100f
        if (value > 0) { cv.save(); cv.clipRect(tx, top, w - tx, by); cv.drawRoundRect(tx, ty, w - tx, by, r, r, fill); cv.restore() }
        cv.drawText(value.toString(), w / 2, context.dpf(28f), txt)
        val s = context.dp(24); ic.setBounds(((w - s) / 2).toInt(), (h - context.dpf(38f)).toInt(), ((w + s) / 2).toInt(), (h - context.dpf(38f)).toInt() + s); ic.draw(cv)
    }
}

/** The thin progress bar: a hairline track, the played part in the accent, a round thumb. Always drawn left to right. */
class ThinSeek(c: Context, accent: Int) : View(c) {
    var max = 1
        set(v) { field = max(1, v); invalidate() }
    private var shown = 0
    /** The position being played; ignored while a finger is on the bar. */
    var progress: Int
        get() = shown
        set(v) { if (!dragging) { shown = v.coerceIn(0, max); invalidate() } }
    var onStart: () -> Unit = {}
    var onMove: (Int) -> Unit = {}
    var onEnd: (Int) -> Unit = {}
    private var dragging = false
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88B0B8BE.toInt() }
    private val done = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lighten(accent, 0.45f) }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pad = c.dpf(10f)

    private fun x(): Float = pad + (width - 2 * pad) * shown / max

    override fun onDraw(cv: Canvas) {
        val cy = height / 2f; val th = context.dpf(2f)
        cv.drawRoundRect(pad, cy - th, width - pad, cy + th, th, th, track)
        cv.drawRoundRect(pad, cy - th, x(), cy + th, th, th, done)
        cv.drawCircle(x(), cy, context.dpf(if (dragging) 9f else 7f), thumb)
    }

    private fun at(e: MotionEvent): Int = (((e.x - pad) / max(1f, width - 2 * pad)).coerceIn(0f, 1f) * max).toInt()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; onStart(); shown = at(e); invalidate(); onMove(shown) }
            MotionEvent.ACTION_MOVE -> { shown = at(e); invalidate(); onMove(shown) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { shown = at(e); dragging = false; invalidate(); onEnd(shown) }
        }
        return true
    }
}

/** The speed bar: a row of dots with a thumb line, from [lo] to [hi]. */
class DotSlider(c: Context, private val accent: Int, private val lo: Float, private val hi: Float, private val rtl: Boolean) : View(c) {
    var value = 1f
        set(v) { field = v.coerceIn(lo, hi); invalidate() }
    var onChange: (Float) -> Unit = {}
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lighten(accent, 0.5f); strokeWidth = c.dpf(3f); strokeCap = Paint.Cap.ROUND }
    private val pad = c.dpf(8f)

    /** In Urdu the bar is mirrored like the rest of the menu: the lowest speed is at the right. */
    private fun frac(v: Float): Float { val f = (v - lo) / (hi - lo); return if (rtl) 1 - f else f }

    override fun onDraw(cv: Canvas) {
        val cy = height / 2f; val n = 38
        val tf = frac(value)
        val tx = pad + (width - 2 * pad) * tf
        for (i in 0..n) {
            val f = i.toFloat() / n
            val x = pad + (width - 2 * pad) * f
            val filled = if (rtl) f >= tf - 0.0001f else f <= tf + 0.0001f
            dot.color = if (filled) lighten(accent, 0.5f) else 0xFF5B6770.toInt()
            cv.drawCircle(x, cy, context.dpf(2.2f), dot)
        }
        cv.drawLine(tx, cy - context.dpf(11f), tx, cy + context.dpf(11f), line)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_UP) {
            var f = ((e.x - pad) / max(1f, width - 2 * pad)).coerceIn(0f, 1f)
            if (rtl) f = 1 - f
            val v = Math.round((lo + (hi - lo) * f) * 20f) / 20f      // steps of 0.05
            value = v; onChange(v)
        }
        return true
    }
}

/** The menu that slides over the video: from the side in landscape (the right in Urdu, the left otherwise), up from the bottom to half the screen in portrait. */
class PlayerPanel(private val c: Context, private val host: FrameLayout, private val p: Pal) {
    private var overlay: FrameLayout? = null
    var onClose: () -> Unit = {}
    val isOpen: Boolean get() = overlay != null

    fun hide() {
        overlay?.let { host.removeView(it) }; overlay = null
        onClose()
    }

    /** [inset] = left, top, right, bottom the panel's content must keep clear of (camera cut-out, navigation bar). */
    fun show(title: String, body: View, inset: IntArray, rtl: Boolean) {
        hide()
        val land = host.width > host.height
        val ov = FrameLayout(c)
        ov.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) hide(); true }
        val r = c.dpf(22f)
        val box = LinearLayout(c); box.orientation = LinearLayout.VERTICAL
        box.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        val bg = GradientDrawable(); bg.setColor(0xF2131B20.toInt())
        // the corners that face the video are rounded
        bg.cornerRadii = if (land) { if (rtl) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) } else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        box.background = bg
        box.isClickable = true
        val t = Ui.text(c, title, 20f, Color.WHITE, false).atStart()
        t.setPaddingRelative(c.dp(22), c.dp(18), c.dp(22), c.dp(10))
        box.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        val sv = ScrollView(c)
        sv.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        box.addView(DeskScroll.wrap(sv, p, false), LinearLayout.LayoutParams(MATCH, 0, 1f))
        val lp = if (land) FrameLayout.LayoutParams(min(c.dp(380), host.width * 55 / 100), MATCH).also { it.gravity = if (rtl) Gravity.RIGHT else Gravity.LEFT }
                 else FrameLayout.LayoutParams(MATCH, host.height / 2).also { it.gravity = Gravity.BOTTOM }
        if (land) { box.setPadding(if (rtl) 0 else inset[0], inset[1], if (rtl) inset[2] else 0, inset[3]) } else box.setPadding(inset[0], 0, inset[2], inset[3])
        ov.addView(box, lp)
        host.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ov
    }
}
