package com.android.desktoplauncher

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/** One desktop folder. Members are Entry keys ("app:<component>" or "file:<path>"). */
class FolderData(val id: String, var name: String, var sx: Int, var sy: Int, val members: MutableList<String>)

object FolderStore {
    private const val F = "\u001f"
    private const val M = "\u001e"

    fun load(c: Context): MutableList<FolderData> {
        val out = ArrayList<FolderData>()
        for (s in Prefs.list(c, Prefs.K_FOLDERS)) {
            val p = s.split(F)
            if (p.size < 5) continue
            out += FolderData(p[0], p[1], (p[2].toIntOrNull() ?: 1).coerceIn(1, 3), (p[3].toIntOrNull() ?: 1).coerceIn(1, 3),
                p[4].split(M).filter { it.isNotEmpty() }.toMutableList())
        }
        return out
    }

    fun save(c: Context, list: List<FolderData>) {
        Prefs.setList(c, Prefs.K_FOLDERS, list.map {
            listOf(it.id, it.name.replace("|", " ").replace(F, " ").replace(M, " "), it.sx, it.sy,
                it.members.filter { m -> !m.contains("|") }.joinToString(M)).joinToString(F)
        }, fire = false)
    }

    fun update(c: Context, id: String, change: (FolderData) -> Unit) {
        val l = load(c)
        l.firstOrNull { it.id == id }?.let(change)
        l.removeAll { it.members.isEmpty() }
        save(c, l)
    }
}

/**
 * The folder as it appears on the desktop. 1x1: a container with up to 4 small icons.
 * Bigger (up to 3x3): icons are shown at full size and can be opened directly; when there are more members
 * than cells, the last cell shows the remaining ones as small icons.
 */
class FolderView(ctx: Context, val folder: Entry.FolderE) : ViewGroup(ctx) {
    var editing = false
        set(v) { field = v; invalidate() }

    private class Slot(val r: Rect, val entry: Entry, val hit: String?)

    private val label = TextView(ctx)
    private var slots: List<Slot> = emptyList()
    private var sig = ""
    private val pad = ctx.dp(6)
    private val labelH = ctx.dp(26)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = ctx.dp(4).toFloat()
    }

    init {
        setWillNotDraw(false)
        label.textSize = 12f
        label.setTextColor(android.graphics.Color.WHITE)
        label.setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK)
        label.maxLines = 1
        label.gravity = android.view.Gravity.CENTER
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.isClickable = false
        addView(label)
    }

    private fun container() = Rect(pad, pad, width - pad, height - labelH)

    fun inHandle(x: Float, y: Float): Boolean {
        val c = container()
        val z = context.dp(48)
        return x >= c.right - z && y >= c.bottom - z && x <= width && y <= height
    }

    /** Member under this point (view coordinates) when it is a full size icon; otherwise null (= open the folder). */
    fun hit(x: Int, y: Int): String? = slots.firstOrNull { it.hit != null && it.r.contains(x, y) }?.hit

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val h = MeasureSpec.getSize(hs)
        setMeasuredDimension(w, h)
        label.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(labelH, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        label.text = UiText.text(context, folder.name)
        label.layout(0, h - labelH, w, h)
        rebuild(w, h)
    }

    private fun miniSlots(box: Rect, list: List<Entry>, out: MutableList<Slot>) {
        val inset = (box.width() * 0.12f).toInt()
        val inner = Rect(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset)
        val cw = inner.width() / 2
        val ch = inner.height() / 2
        val s = (minOf(cw, ch) * 0.86f).toInt()
        for ((i, e) in list.take(4).withIndex()) {
            val cx = inner.left + (i % 2) * cw + cw / 2
            val cy = inner.top + (i / 2) * ch + ch / 2
            out += Slot(Rect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), e, null)
        }
    }

    private fun rebuild(w: Int, h: Int) {
        val res = folder.resolved
        val s = "${folder.sx}x${folder.sy}:${w}x$h:" + res.joinToString(",") { it.key }
        if (s == sig) return
        sig = s
        removeViewsInLayout(1, childCount - 1)
        val out = ArrayList<Slot>()
        val cont = container()
        val cells = folder.sx * folder.sy
        val n = res.size
        if (cells == 1) miniSlots(cont, res, out)
        else {
            val cw = cont.width() / folder.sx
            val ch = cont.height() / folder.sy
            val full = if (n <= cells) n else cells - 1
            for (i in 0 until full) {
                val cx = cont.left + (i % folder.sx) * cw + cw / 2
                val cy = cont.top + (i / folder.sx) * ch + ch / 2
                val sz = (minOf(cw, ch) * 0.66f).toInt()
                out += Slot(Rect(cx - sz / 2, cy - sz / 2, cx + sz / 2, cy + sz / 2), res[i], res[i].key)
            }
            if (n > cells) {
                val i = cells - 1
                val box = Rect(cont.left + (i % folder.sx) * cw, cont.top + (i / folder.sx) * ch,
                    cont.left + (i % folder.sx) * cw + cw, cont.top + (i / folder.sx) * ch + ch)
                miniSlots(box, res.subList(full, n), out)
            }
        }
        slots = out
        for (sl in out) {
            val iv = ImageView(context)
            iv.isClickable = false
            val e = sl.entry
            val px = sl.r.width()
            if (e is Entry.AppE) iv.setImageDrawable(Icons.app(context, e.info.comp, false))
            else if (e is Entry.FileE) Thumbs.bind(iv, e.file, px)
            addViewInLayout(iv, -1, LayoutParams(px, sl.r.height()), true)
            iv.measure(MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sl.r.height(), MeasureSpec.EXACTLY))
            iv.layout(sl.r.left, sl.r.top, sl.r.right, sl.r.bottom)
        }
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val c = container()
        val rc = RectF(c)
        val r = context.dp(22).toFloat()
        fill.color = (Colors.surfaceHigh(context) and 0x00FFFFFF) or 0xB4000000.toInt()
        canvas.drawRoundRect(rc, r, r, fill)
        stroke.color = Colors.accent(context)
        stroke.alpha = if (editing) 255 else 110
        stroke.strokeWidth = context.dp(if (editing) 3 else 1).toFloat()
        canvas.drawRoundRect(rc, r, r, stroke)
        if (editing) {
            handle.color = Colors.accent(context)
            val rad = context.dp(16).toFloat()
            val cx = rc.right - rad - context.dp(6)
            val cy = rc.bottom - rad - context.dp(6)
            canvas.drawArc(RectF(cx - rad, cy - rad, cx + rad, cy + rad), 5f, 80f, false, handle)
        }
    }
}
