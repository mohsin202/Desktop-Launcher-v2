package com.android.desktopfiles

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale

/** What a menu needs from the player window. */
class MenuCtx(val act: Activity, val p: Pal, val rtl: Boolean, val close: () -> Unit, val pickSubtitle: () -> Unit)

/** A round radio mark. */
private class RadioDot(c: android.content.Context, private val on: Boolean, private val accent: Int) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = context.dpf(10f)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dpf(2f); paint.color = if (on) lighten(accent, 0.5f) else 0xFFB8C4CC.toInt()
        cv.drawCircle(cx, cy, r, paint)
        if (on) { paint.style = Paint.Style.FILL; cv.drawCircle(cx, cy, r * 0.5f, paint) }
    }
}

/** The bodies of the four menus (speed, play list, audio track, subtitle). The window puts them in a [PlayerPanel]. */
object PlayerMenus {
    private const val SUB = 0xFFB8C4CC.toInt()

    private fun accent(m: MenuCtx) = lighten(m.p.accent, 0.5f)

    private fun circleBtn(m: MenuCtx, icon: String, click: () -> Unit): View {
        val v = ImageView(m.act); v.setImageDrawable(PlayerIcon(icon, Color.WHITE, m.act.dp(20))); v.scaleType = ImageView.ScaleType.CENTER
        v.background = Ui.rounded(0xFF2A363E.toInt(), m.act.dpf(24f)); v.foreground = hoverBg(m.act, 0f, true); v.isClickable = true; v.setOnClickListener { click() }
        v.layoutParams = LinearLayout.LayoutParams(m.act.dp(44), m.act.dp(44))
        return v
    }

    private fun box(m: MenuCtx, fill: Int, stroke: Int, r: Float = 24f) = Ui.rounded(fill, m.act.dpf(r), stroke, m.act.dp(2).coerceAtLeast(2))

    private fun field(m: MenuCtx, init: String, signed: Boolean): EditText {
        val e = EditText(m.act)
        e.setText(init); e.setTextColor(Color.WHITE); e.textSize = 17f; e.setSingleLine(); e.background = null
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
        e.imeOptions = EditorInfo.IME_ACTION_DONE
        e.setPaddingRelative(m.act.dp(8), m.act.dp(6), m.act.dp(8), m.act.dp(6))
        return e
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    // ------------------------------------------------------------ speed

    fun speed(m: MenuCtx): View {
        val c = m.act; val col = LinearLayout(c); col.orientation = LinearLayout.VERTICAL
        col.setPaddingRelative(c.dp(18), c.dp(4), c.dp(18), c.dp(18))
        var updating = false
        val ed = field(m, fmt(PlayerEngine.speed.toDouble()), false); ed.gravity = Gravity.CENTER; ed.textAlignment = View.TEXT_ALIGNMENT_CENTER
        val slider = DotSlider(c, m.p.accent, 0.2f, 4f, m.rtl); slider.value = PlayerEngine.speed
        val chips = ArrayList<Pair<Float, TextView>>()
        fun paintChips() { for ((v, t) in chips) { val on = Math.abs(v - PlayerEngine.speed) < 0.001f; t.background = box(m, if (on) accent(m) else Color.TRANSPARENT, accent(m), 20f); t.setTextColor(if (on) 0xFF10202A.toInt() else accent(m)) } }
        fun set(v: Float, fromField: Boolean = false) {
            val x = Math.round(v.coerceIn(0.2f, 4f) * 100f) / 100f
            PlayerEngine.setSpeed(x); slider.value = x
            if (!fromField) { updating = true; ed.setText(fmt(x.toDouble())); updating = false }
            paintChips()
        }
        ed.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { if (!updating) s.toString().toFloatOrNull()?.let { if (it in 0.2f..4f) set(it, true) } }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        })
        slider.onChange = { set(it) }

        // the whole menu follows the language's direction, like the rest of the player's menus: - on the start side, + on the end side
        val top = LinearLayout(c); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL
        top.addView(circleBtn(m, "minus") { set(PlayerEngine.speed - 0.05f) })
        ed.background = box(m, Color.TRANSPARENT, accent(m))
        top.addView(ed, LinearLayout.LayoutParams(0, c.dp(48), 1f).also { it.setMargins(c.dp(40), 0, c.dp(40), 0) })
        top.addView(circleBtn(m, "plus") { set(PlayerEngine.speed + 0.05f) })
        col.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        val sl = LinearLayout(c); sl.orientation = LinearLayout.HORIZONTAL; sl.gravity = Gravity.CENTER_VERTICAL
        sl.addView(slider, LinearLayout.LayoutParams(0, c.dp(40), 1f))
        val reset = ImageView(c); reset.setImageDrawable(PlayerIcon("reset", SUB, c.dp(22))); reset.scaleType = ImageView.ScaleType.CENTER; reset.isClickable = true
        reset.foreground = hoverBg(c, 0f, true); reset.setOnClickListener { set(1f) }
        sl.addView(reset, LinearLayout.LayoutParams(c.dp(40), c.dp(40)))
        col.addView(sl, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = c.dp(10) })

        fun chipRow(vals: List<Float>) {
            val row = LinearLayout(c); row.orientation = LinearLayout.HORIZONTAL
            for (v in vals) {
                val t = Ui.text(c, fmt(v.toDouble()).let { if (it.contains('.')) it else "$it.0" }, 14f, accent(m), false, false); t.gravity = Gravity.CENTER; t.textAlignment = View.TEXT_ALIGNMENT_CENTER; t.isClickable = true
                t.foreground = hoverBg(c, c.dpf(20f), false)
                t.setOnClickListener { set(v) }
                chips.add(v to t)
                row.addView(t, LinearLayout.LayoutParams(0, c.dp(40), 1f).also { it.setMargins(c.dp(3), 0, c.dp(3), 0) })
            }
            col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = c.dp(10) })
        }
        chipRow(listOf(0.2f, 0.5f, 0.75f, 1.0f, 1.5f))
        chipRow(listOf(2.0f, 2.5f, 3.0f, 3.5f, 4.0f))
        paintChips()
        // the keyboard's left / right arrows call this: +1 moves the bar one step towards its right end
        col.tag = { dir: Int -> set(PlayerEngine.speed + dir * 0.05f * (if (m.rtl) -1 else 1)) }
        return col
    }

    // ------------------------------------------------------------ play list

    fun playlist(m: MenuCtx): View {
        val c = m.act
        val col = LinearLayout(c); col.orientation = LinearLayout.VERTICAL
        col.setPaddingRelative(c.dp(10), c.dp(2), c.dp(10), c.dp(14))
        val rowH = c.dp(72)

        fun fill() {
            col.removeAllViews()
            val e = PlayerEngine
            for ((i, it) in e.items.withIndex()) {
                val row = LinearLayout(c); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL; row.layoutDirection = View.LAYOUT_DIRECTION_LTR
                if (i == e.index) row.background = Ui.rounded((lighten(m.p.accent, 0.2f) and 0x00FFFFFF) or 0x55000000, c.dpf(14f))
                val x = ImageView(c); x.setImageDrawable(PlayerIcon("close", Color.WHITE, c.dp(20))); x.scaleType = ImageView.ScaleType.CENTER; x.isClickable = true
                x.setOnClickListener { e.removeAt(i); if (e.items.isEmpty()) m.close() else fill() }
                row.addView(x, LinearLayout.LayoutParams(c.dp(44), rowH))
                val title = Ui.text(c, it.title, 15f, Color.WHITE, false, false); title.maxLines = 2; title.ellipsize = android.text.TextUtils.TruncateAt.END
                title.gravity = if (m.rtl) Gravity.RIGHT else Gravity.LEFT
                row.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f).also { p -> p.setMargins(c.dp(4), 0, c.dp(8), 0) })
                val th = FrameLayout(c)
                val iv = ImageView(c); iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.setBackgroundColor(0xFF000000.toInt())
                if (it.path != null) Thumbs.bind(iv, File(it.path), c.dp(160)) else iv.setImageDrawable(PlayerIcon("note", Color.WHITE, c.dp(24)))
                th.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
                val dur = Ui.text(c, "", 11f, Color.WHITE, false, false); dur.setBackgroundColor(0x99000000.toInt()); dur.setPadding(c.dp(4), 0, c.dp(4), 0)
                th.addView(dur, FrameLayout.LayoutParams(WRAP, WRAP).also { p -> p.gravity = Gravity.BOTTOM or Gravity.LEFT; p.setMargins(c.dp(3), 0, 0, c.dp(3)) })
                e.duration(it) { ms -> if (ms > 0) dur.text = clock(ms) }
                row.addView(th, LinearLayout.LayoutParams(c.dp(96), c.dp(54)))
                val hd = ImageView(c); hd.setImageDrawable(PlayerIcon("handle", SUB, c.dp(22))); hd.scaleType = ImageView.ScaleType.CENTER
                row.addView(hd, LinearLayout.LayoutParams(c.dp(44), rowH))
                row.foreground = hoverBg(c, c.dpf(14f), false)
                row.setOnClickListener { e.play(i); m.close() }
                drag(m, col, row, hd, rowH)
                col.addView(row, LinearLayout.LayoutParams(MATCH, rowH))
            }
        }
        fill()
        col.tag = Runnable { fill() }     // the window calls this when the playing item changes (↑ ↓ keys)
        return col
    }

    /** Hold the ≡ of a row and move it up or down; the list follows. */
    private fun drag(m: MenuCtx, col: LinearLayout, row: View, handle: View, rowH: Int) {
        var startY = 0f; var startIdx = 0; var cur = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    col.parent?.requestDisallowInterceptTouchEvent(true); (col.parent?.parent)?.requestDisallowInterceptTouchEvent(true)
                    startY = e.rawY; startIdx = col.indexOfChild(row); cur = startIdx; row.elevation = 8f; row.alpha = 0.85f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    val target = (startIdx + Math.round(dy / rowH)).coerceIn(0, col.childCount - 1)
                    if (target != cur) {
                        col.removeView(row); col.addView(row, target, LinearLayout.LayoutParams(MATCH, rowH))
                        PlayerEngine.move(cur, target); cur = target
                    }
                    row.translationY = dy - (cur - startIdx) * rowH
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    row.animate().translationY(0f).setDuration(120).start(); row.elevation = 0f; row.alpha = 1f
                }
            }
            true
        }
    }

    fun clock(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val mi = (s % 3600) / 60; val se = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, mi, se) else String.format(Locale.US, "%02d:%02d", mi, se)
    }

    // ------------------------------------------------------------ audio track

    private fun radioRow(m: MenuCtx, label: String, on: Boolean, translate: Boolean = true, click: () -> Unit): View {
        val c = m.act
        val row = LinearLayout(c); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
        row.setPaddingRelative(c.dp(22), 0, c.dp(22), 0)
        val t = Ui.text(c, label, 15f, Color.WHITE, false, translate).atStart()
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(RadioDot(c, on, m.p.accent), LinearLayout.LayoutParams(c.dp(32), c.dp(32)))
        row.isClickable = true; row.foreground = hoverBg(c, c.dpf(10f), false); row.setOnClickListener { click() }
        row.layoutParams = LinearLayout.LayoutParams(MATCH, c.dp(52))
        return row
    }

    fun audio(m: MenuCtx): View {
        val col = LinearLayout(m.act); col.orientation = LinearLayout.VERTICAL
        fun fill() {
            col.removeAllViews()
            val tracks = PlayerEngine.audioTracks()
            val sel = PlayerEngine.selectedAudio()
            for ((n, t) in tracks.withIndex()) col.addView(radioRow(m, t.label, !PlayerEngine.audioOff && (sel == t.index || (sel < 0 && n == 0)), false) { PlayerEngine.selectAudio(t.index); fill() })
            if (tracks.isEmpty()) col.addView(Ui.text(m.act, "No other audio track in this file", 14f, SUB).atStart().also { it.setPaddingRelative(m.act.dp(22), m.act.dp(6), m.act.dp(22), m.act.dp(10)) })
            col.addView(radioRow(m, "Disable", PlayerEngine.audioOff) { PlayerEngine.muteAudio(); fill() })
        }
        fill()
        return col
    }

    // ------------------------------------------------------------ subtitle

    fun subtitle(m: MenuCtx): View {
        val c = m.act
        val col = LinearLayout(c); col.orientation = LinearLayout.VERTICAL
        fun fill() {
            col.removeAllViews()
            col.addView(radioRow(m, "Disable", SubState.cues.isEmpty()) { SubState.clear(); fill() })
            if (SubState.cues.isNotEmpty()) col.addView(radioRow(m, SubState.name, true, false) {})
            val open = Ui.text(c, "Open local subtitle", 14f, Color.WHITE, true); open.gravity = Gravity.CENTER; open.textAlignment = View.TEXT_ALIGNMENT_CENTER; open.isClickable = true; open.foreground = hoverBg(c, c.dpf(24f), false)
            open.background = Ui.rounded(0xFF3B4D57.toInt(), c.dpf(24f)); open.setOnClickListener { m.pickSubtitle() }
            col.addView(open, LinearLayout.LayoutParams(MATCH, c.dp(44)).also { it.setMargins(c.dp(18), c.dp(8), c.dp(18), c.dp(14)) })
            col.addView(adjust(m, "Delay", "sec", SubState.delayMs / 1000.0, 0.5, true) { SubState.delayMs = (it * 1000).toLong() })
            col.addView(adjust(m, "Speed", "x", SubState.speed, 0.05, false) { SubState.speed = it.coerceIn(0.1, 10.0) })
        }
        fill()
        return col
    }

    /** A labelled number with + and − on either side. */
    private fun adjust(m: MenuCtx, label: String, unit: String, init: Double, step: Double, signed: Boolean, set: (Double) -> Unit): View {
        val c = m.act
        val outer = LinearLayout(c); outer.orientation = LinearLayout.VERTICAL; outer.setPaddingRelative(c.dp(18), c.dp(2), c.dp(18), c.dp(8))
        outer.addView(Ui.text(c, label, 12f, SUB).atStart().also { it.setPaddingRelative(c.dp(66), 0, 0, c.dp(3)) })
        val row = LinearLayout(c); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
        val ed = field(m, fmt(init), signed); ed.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        var updating = false
        fun put(v: Double) { updating = true; ed.setText(fmt(v)); updating = false; set(v) }
        ed.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { if (!updating) s.toString().toDoubleOrNull()?.let { set(it) } }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        })
        row.addView(circleBtn(m, "minus") { put((ed.text.toString().toDoubleOrNull() ?: init) - step) })
        val fb = LinearLayout(c); fb.orientation = LinearLayout.HORIZONTAL; fb.gravity = Gravity.CENTER_VERTICAL; fb.background = box(m, Color.TRANSPARENT, 0xFF6B7A84.toInt(), 6f)
        fb.setPadding(c.dp(12), 0, c.dp(8), 0)
        fb.addView(Ui.text(c, unit, 14f, SUB, false, false))
        fb.addView(ed, LinearLayout.LayoutParams(0, c.dp(46), 1f))
        row.addView(fb, LinearLayout.LayoutParams(0, c.dp(50), 1f).also { it.setMargins(c.dp(10), 0, c.dp(10), 0) })
        row.addView(circleBtn(m, "plus") { put((ed.text.toString().toDoubleOrNull() ?: init) + step) })
        outer.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        return outer
    }
}
