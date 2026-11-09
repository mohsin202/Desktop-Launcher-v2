package com.android.desktopfiles

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** The Recycle Bin: deleted files and folders (kept 30 days). */
class TrashPage(private val host: Host) : Page {
    private val c: Context = host.act
    private val p = Ui.pal(c)
    private var items: List<TrashStore.Item> = emptyList()
    private val sel = LinkedHashSet<String>()
    private var loading = true
    private var lastX = 0; private var lastY = 0

    private val root = LinearLayout(c)
    private val cmd = LinearLayout(c)
    private val list = ListView(c)
    private val empty = Ui.text(c, "", 14f, p.sub)
    private val status = Ui.text(c, "", 12f, p.sub)
    override val view: View get() = root

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int): Any = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
            val r = LinearLayout(c); r.gravity = Gravity.CENTER_VERTICAL
            r.layoutParams = AbsListView.LayoutParams(MATCH, c.dp(50)); r.setPaddingRelative(c.dp(8), 0, c.dp(8), 0)
            val it = items[i]; val on = it.file.absolutePath in sel
            r.background = Ui.ripple(c, p, if (on) Ui.rounded(p.sel, c.dpf(8f)) else null, c.dpf(8f))
            val ck = Ui.text(c, if (on) "✓" else "", 13f, 0xFFFFFFFF.toInt(), true); ck.gravity = Gravity.CENTER
            ck.background = if (on) Ui.rounded(p.accent, c.dpf(11f)) else Ui.rounded(0, c.dpf(11f), p.sub, c.dp(1))
            r.addView(ck, LinearLayout.LayoutParams(c.dp(22), c.dp(22)).also { it.marginEnd = c.dp(8) })
            val iv = ImageView(c)
            iv.setImageDrawable(Thumbs.icon(it.file))
            r.addView(iv, LinearLayout.LayoutParams(c.dp(30), c.dp(30)).also { it.marginEnd = c.dp(8) })
            val box = LinearLayout(c); box.orientation = LinearLayout.VERTICAL
            val nm = Ui.text(c, it.name, 14f, p.text, false, false); nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            val loc = Ui.text(c, "Deleted from " + (it.file.parent ?: ""), 10f, p.sub); loc.maxLines = 1; loc.ellipsize = android.text.TextUtils.TruncateAt.START
            box.addView(nm); box.addView(loc)
            r.addView(box, LinearLayout.LayoutParams(0, WRAP, 1f))
            val left = Ui.text(c, it.daysLeft.toString() + (if (it.daysLeft == 1) " day left" else " days left"), 12f, p.sub)
            r.addView(left, LinearLayout.LayoutParams(c.dp(90), WRAP))
            r.isClickable = true
            r.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { lastX = e.rawX.toInt(); lastY = e.rawY.toInt() }; false }
            r.setOnClickListener { toggle(i) }
            r.setOnLongClickListener { menu(i); true }
            r.setOnContextClickListener { menu(i); true }
            return r
        }
    }

    init {
        root.orientation = LinearLayout.VERTICAL
        val sv = HorizontalScrollView(c); sv.isHorizontalScrollBarEnabled = false
        cmd.gravity = Gravity.CENTER_VERTICAL; cmd.setPaddingRelative(c.dp(6), 0, c.dp(6), 0); sv.addView(cmd)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, c.dp(46)))
        list.divider = null; list.adapter = adapter; list.selector = android.graphics.drawable.ColorDrawable(0)
        val fr = FrameLayout(c)
        fr.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        DeskScroll.attach(fr, p, list)
        empty.gravity = Gravity.CENTER
        fr.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(fr, LinearLayout.LayoutParams(MATCH, 0, 1f))
        status.setPaddingRelative(c.dp(14), c.dp(6), c.dp(14), c.dp(6))
        root.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
        buildBar(); refresh()
    }

    private fun btn(label: String, on: Boolean = true, danger: Boolean = false, click: () -> Unit) {
        val t = Ui.text(c, label, 13f, if (!on) p.sub else if (danger) p.warn else p.text)
        t.setPaddingRelative(c.dp(12), 0, c.dp(12), 0); t.gravity = Gravity.CENTER; t.alpha = if (on) 1f else 0.45f
        if (on) { t.isClickable = true; t.background = Ui.ripple(c, p, null, c.dpf(8f)); t.setOnClickListener { click() } }
        cmd.addView(t, LinearLayout.LayoutParams(WRAP, c.dp(36)))
    }

    private fun buildBar() {
        cmd.removeAllViews()
        btn("Restore", sel.isNotEmpty()) { restore(chosen()) }
        btn("Delete permanently", sel.isNotEmpty(), true) { forever(chosen()) }
        btn("Select all", items.isNotEmpty()) { items.forEach { sel.add(it.file.absolutePath) }; changed() }
        btn("Empty Trash", items.isNotEmpty(), true) { forever(items) }
    }

    private fun chosen() = items.filter { it.file.absolutePath in sel }

    private fun changed() { adapter.notifyDataSetChanged(); buildBar(); updateStatus() }

    private fun toggle(i: Int) { val k = items[i].file.absolutePath; if (!sel.add(k)) sel.remove(k); changed() }

    private fun updateStatus() {
        status.text = items.size.toString() + (if (items.size == 1) " item" else " items") + (if (sel.isNotEmpty()) "   |   " + sel.size + " selected" else "") + "   |   kept for " + TrashStore.DAYS + " days"
        empty.text = if (loading) "Loading…" else "The Trash is empty"
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun refresh() {
        loading = true; updateStatus()
        Thread {
            val l = TrashStore.list(c)
            c.mainExec.execute {
                items = l; loading = false
                val keep = l.map { it.file.absolutePath }.toSet(); sel.retainAll(keep)
                adapter.notifyDataSetChanged(); buildBar(); updateStatus()
            }
        }.start()
    }

    private fun restore(l: List<TrashStore.Item>) {
        if (l.isEmpty()) return
        Jobs.start(Job("Restoring " + l.size + " item(s)"), { j -> j.total = l.size.toLong(); for (i in l) { if (j.cancelled) break; j.detail = i.name; if (!TrashStore.restore(c, i)) j.failed++; j.done++ } }) { sel.clear(); host.refreshAll() }
    }

    private fun forever(l: List<TrashStore.Item>) {
        if (l.isEmpty()) return
        Dialogs.confirm(host.act, "Delete permanently", "Delete " + l.size + " item(s) permanently? This cannot be undone.", "Delete") {
            Jobs.start(Job("Deleting " + l.size + " item(s) permanently"), { j -> j.total = l.size.toLong(); for (i in l) { if (j.cancelled) break; j.detail = i.name; if (!TrashStore.deleteForever(c, i)) j.failed++; j.done++ } }) { sel.clear(); host.refreshAll() }
            host.showStatus()
        }
    }

    private fun menu(i: Int) {
        val it = items[i]
        if (it.file.absolutePath !in sel) { sel.clear(); sel.add(it.file.absolutePath); changed() }
        Pop(c).showAt(root, lastX, lastY, listOf(
            MenuItem("Restore") { restore(chosen()) },
            MenuItem("Delete permanently", danger = true) { forever(chosen()) }))
    }
}
