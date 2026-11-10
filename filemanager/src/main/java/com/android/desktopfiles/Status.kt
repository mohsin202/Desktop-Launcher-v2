package com.android.desktopfiles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView

/** A thin progress bar. */
class Prog(c: Context, private val pal: Pal) : View(c) {
    var fraction = 0f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat(); val r = h / 2
        p.color = pal.line; canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), r, r, p)
        p.color = pal.accent
        val fw = maxOf(h, width * fraction.coerceIn(0f, 1f))
        if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.drawRoundRect(RectF(width - fw, 0f, width.toFloat(), h), r, r, p)
        else canvas.drawRoundRect(RectF(0f, 0f, fw, h), r, r, p)
    }
}

/** The toolbar button of the Status Center: a ring with the combined progress, or a green tick when nothing is running. */
class StatusBtn(c: Context, private val pal: Pal, private val onTap: () -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { invalidate(); if (Jobs.active() && isAttachedToWindow) h.postDelayed(this, 250) }
    }
    private val listener: () -> Unit = { invalidate(); h.removeCallbacks(tick); h.post(tick) }

    init {
        isClickable = true
        background = Ui.ripple(c, pal, null, c.dpf(8f))
        setOnClickListener { onTap() }
        contentDescription = "Status Center"
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Jobs.listen(listener); h.post(tick) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); Jobs.unlisten(listener); h.removeCallbacks(tick) }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = context.dpf(10f)
        if (Jobs.active()) {
            p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(3f); p.color = pal.line
            val box = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawCircle(cx, cy, r, p)
            p.color = pal.accent; p.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(box, -90f, 360f * maxOf(0.03f, Jobs.fraction()), false, p)
        } else {
            p.style = Paint.Style.FILL; p.color = 0xFF2E9E57.toInt()
            canvas.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(2f); p.color = 0xFFFFFFFF.toInt(); p.strokeCap = Paint.Cap.ROUND
            val t = android.graphics.Path()
            t.moveTo(cx - r * .45f, cy + r * .02f); t.lineTo(cx - r * .1f, cy + r * .38f); t.lineTo(cx + r * .5f, cy - r * .32f)
            canvas.drawPath(t, p)
        }
    }
}

/** The panel with one line per job: progress, details, × (cancel or dismiss), − (hide the panel). */
object StatusPanel {
    private var win: PopupWindow? = null
    private val h = Handler(Looper.getMainLooper())

    fun showing() = win?.isShowing == true

    fun hide() { try { win?.dismiss() } catch (_: Exception) {}; win = null }

    fun show(c: Context, anchor: View) {
        hide()
        val p = Ui.pal(c)
        val box = LinearLayout(c); box.orientation = LinearLayout.VERTICAL
        box.background = Ui.rounded(p.card, c.dpf(12f), p.line, 1)
        box.setPaddingRelative(c.dp(14), c.dp(10), c.dp(14), c.dp(12))
        box.elevation = c.dpf(8f)
        val head = LinearLayout(c); head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Ui.text(c, "Status Center", 15f, p.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(Ui.glyphButton(c, p, "−", 18f, 36, 36) { hide() })
        box.addView(head)
        val list = LinearLayout(c); list.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(c); sv.addView(list)
        box.addView(DeskScroll.wrap(sv, p), LinearLayout.LayoutParams(MATCH, WRAP))

        fun fill() {
            list.removeAllViews()
            if (Jobs.list.isEmpty()) {
                val t = Ui.text(c, "No running operations", 13f, p.sub); t.setPaddingRelative(0, c.dp(12), 0, c.dp(4)); list.addView(t)
                return
            }
            for (j in Jobs.list) {
                val row = LinearLayout(c); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
                row.setPaddingRelative(0, c.dp(8), 0, c.dp(2))
                val col = LinearLayout(c); col.orientation = LinearLayout.VERTICAL
                val t = Ui.text(c, j.title, 13f, p.text, true); t.maxLines = 1; t.ellipsize = android.text.TextUtils.TruncateAt.END
                col.addView(t)
                val pr = Prog(c, p); pr.fraction = j.fraction
                col.addView(pr, LinearLayout.LayoutParams(MATCH, c.dp(5)).also { it.topMargin = c.dp(6); it.bottomMargin = c.dp(4) })
                val d = Ui.text(c, when {
                    j.cancelled -> "Cancelled"
                    j.finished && j.failed > 0 -> "Done, ${j.failed} could not be processed"
                    j.finished -> "Done"
                    else -> (if (j.total > 0) Fs.size(c, j.done) + " of " + Fs.size(c, j.total) + "  ·  " else "") + j.detail
                }, 11f, p.sub)
                d.maxLines = 1; d.ellipsize = android.text.TextUtils.TruncateAt.END
                col.addView(d)
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(Ui.glyphButton(c, p, "×", 20f, 36, 36) { if (j.finished) { Jobs.list.remove(j); Jobs.changed() } else { j.cancelled = true; Jobs.changed() } })
                list.addView(row)
            }
        }
        fill()
        box.layoutDirection = Dir.value()
        val w = PopupWindow(box, c.dp(340), WRAP, true)
        w.isOutsideTouchable = true
        w.setBackgroundDrawable(null)
        win = w
        val al = IntArray(2); anchor.getLocationOnScreen(al)
        val sw = c.resources.displayMetrics.widthPixels
        val px = (if (T.rtl) al[0] + anchor.width - c.dp(340) + c.dp(300) else al[0] - c.dp(300)).coerceIn(0, maxOf(0, sw - c.dp(340)))
        w.showAtLocation(anchor.rootView, android.view.Gravity.NO_GRAVITY, px, al[1] + anchor.height + c.dp(4))
        val tick = object : Runnable {
            override fun run() { if (win === w && w.isShowing) { fill(); h.postDelayed(this, 350) } }
        }
        h.postDelayed(tick, 350)
    }
}
