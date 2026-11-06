package com.android.desktopfiles

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import java.io.File

/**
 * Columns view: the folder in the first column; choosing a folder opens its contents in the next column, choosing a file shows
 * its preview in the last one. Choosing an already chosen item opens it.
 */
class ColumnsPage(private val host: Host, private val start: File) : Page {
    private val c: Context = host.act
    private val p = Ui.pal(c)
    private val root = LinearLayout(c)
    private val hs = HorizontalScrollView(c)
    private val strip = LinearLayout(c)
    private val cols = ArrayList<Column>()
    private var preview: View? = null
    private var lastX = 0; private var lastY = 0
    override val view: View get() = root

    private inner class Column(val dir: File) {
        val list = ListView(c)
        var items: List<File> = emptyList()
        var selected: File? = null
        var loading = true
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(i: Int): Any = items[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                val f = items[i]
                val r = LinearLayout(c); r.gravity = Gravity.CENTER_VERTICAL
                r.layoutParams = AbsListView.LayoutParams(MATCH, c.dp(40)); r.setPadding(c.dp(10), 0, c.dp(8), 0)
                r.background = Ui.ripple(c, p, if (f == selected) Ui.rounded(p.sel, c.dpf(8f)) else null, c.dpf(8f))
                r.isClickable = true
                val iv = ImageView(c); Thumbs.bind(iv, f, c.dp(64))
                r.addView(iv, LinearLayout.LayoutParams(c.dp(26), c.dp(26)).also { it.rightMargin = c.dp(10) })
                val nm = Ui.text(c, Fs.shown(f), 13f, p.text, false, false); nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                r.addView(nm, LinearLayout.LayoutParams(0, WRAP, 1f))
                val real = Fs.target(f) ?: f
                if (real.isDirectory) r.addView(Ui.text(c, "›", 16f, p.sub))
                r.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { lastX = e.rawX.toInt(); lastY = e.rawY.toInt() }; false }
                r.setOnClickListener { choose(this@Column, f) }
                r.setOnLongClickListener { menu(this@Column, f); true }
                r.setOnContextClickListener { menu(this@Column, f); true }
                return r
            }
        }

        init { list.divider = null; list.adapter = adapter; list.selector = android.graphics.drawable.ColorDrawable(0) }

        fun load() {
            loading = true
            val hidden = Store.bool(c, "showHidden", false)
            val key = Store.str(c, "sortKey", "name"); val asc = Store.bool(c, "sortAsc", true)
            Thread {
                val k = try { dir.listFiles()?.toList() } catch (_: Exception) { null } ?: (if (RootSu.ensure()) RootSu.list(dir.absolutePath) else null) ?: emptyList()
                val vis = k.filter { (hidden || !it.name.startsWith(".")) && !it.name.startsWith(".trashed-") && it.name != ".deskclip" }
                val cmp: Comparator<File> = when (key) {
                    "date" -> compareBy<File> { it.lastModified() }
                    "size" -> compareBy<File> { if (it.isDirectory) -1L else it.length() }
                    "type" -> compareBy<File> { it.extension.lowercase() }.thenBy { it.name.lowercase() }
                    else -> compareBy<File> { it.name.lowercase() }
                }
                val s = vis.sortedWith(cmp).let { if (asc) it else it.reversed() }.sortedBy { if (it.isDirectory) 0 else 1 }
                c.mainExecutor.execute { items = s; loading = false; adapter.notifyDataSetChanged() }
            }.start()
        }
    }

    init {
        root.orientation = LinearLayout.VERTICAL
        val bar = LinearLayout(c); bar.gravity = Gravity.CENTER_VERTICAL; bar.setPadding(c.dp(6), 0, c.dp(6), 0)
        fun btn(label: String, click: (View) -> Unit) {
            val t = Ui.text(c, label, 13f, p.text); t.setPadding(c.dp(12), 0, c.dp(12), 0); t.gravity = Gravity.CENTER
            t.isClickable = true; t.background = Ui.ripple(c, p, null, c.dpf(8f)); t.setOnClickListener(click)
            bar.addView(t, LinearLayout.LayoutParams(WRAP, c.dp(36)))
        }
        btn("Paste") { v -> if (!Ops.paste(c, lastDir()) { refresh() }) c.toast("Nothing to paste") }
        btn("View ▾") { v -> Pop(c).showBelow(v, listOf(
            MenuItem("Details") { Store.setStr(c, "view", "details"); host.relayout() },
            MenuItem("Grid") { Store.setStr(c, "view", "grid"); host.relayout() },
            MenuItem("Columns", checked = true),
            null,
            MenuItem("Show hidden files", checked = Store.bool(c, "showHidden", false)) { Store.setBool(c, "showHidden", !Store.bool(c, "showHidden", false)); refresh() })) }
        root.addView(bar, LinearLayout.LayoutParams(MATCH, c.dp(46)))
        hs.isHorizontalScrollBarEnabled = true
        strip.orientation = LinearLayout.HORIZONTAL
        hs.addView(strip, ViewGroup.LayoutParams(WRAP, MATCH))
        root.addView(hs, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addColumn(start)
    }

    /** The folder the Paste button pastes into: the last folder column. */
    private fun lastDir(): File = cols.lastOrNull()?.dir ?: start

    private fun addColumn(d: File) {
        val col = Column(d); cols.add(col)
        strip.addView(col.list, LinearLayout.LayoutParams(c.dp(260), MATCH))
        val sep = View(c); sep.setBackgroundColor(p.line)
        strip.addView(sep, LinearLayout.LayoutParams(c.dp(1), MATCH))
        col.load()
        hs.post { hs.fullScroll(View.FOCUS_RIGHT) }
    }

    /** Removes the columns (and the preview) after column [i]. */
    private fun cut(i: Int) {
        preview = null
        while (cols.size > i + 1) cols.removeAt(cols.size - 1)
        strip.removeAllViews()
        for (col in cols) {
            strip.addView(col.list, LinearLayout.LayoutParams(c.dp(260), MATCH))
            val sep = View(c); sep.setBackgroundColor(p.line)
            strip.addView(sep, LinearLayout.LayoutParams(c.dp(1), MATCH))
        }
    }

    private fun choose(col: Column, f: File) {
        val i = cols.indexOf(col)
        val real = Fs.target(f) ?: f
        if (col.selected == f) {   // chosen again: open it
            if (real.isDirectory) host.go(Loc.Dir(real.absolutePath)) else if (real.exists()) host.openFile(real)
            return
        }
        cut(i)
        col.selected = f; col.adapter.notifyDataSetChanged()
        if (real.isDirectory) addColumn(real)
        else {
            val d = DetailsPane(c); d.show(listOf(f))
            preview = d.view; strip.addView(d.view, LinearLayout.LayoutParams(c.dp(300), MATCH))
            hs.post { hs.fullScroll(View.FOCUS_RIGHT) }
        }
        host.selectionChanged(listOf(f))
    }

    private fun menu(col: Column, f: File) {
        if (col.selected != f) choose(col, f)
        val real = Fs.target(f) ?: f
        val items = ArrayList<MenuItem?>()
        items += MenuItem("Open") { if (real.isDirectory) host.go(Loc.Dir(real.absolutePath)) else host.openFile(real) }
        if (real.isFile) items += MenuItem("Open with…") { Fs.view(c, real, true) }
        if (real.isDirectory) {
            items += MenuItem("Open in new tab") { host.newTab(Loc.Dir(real.absolutePath)) }
            items += MenuItem("Open in new window") { host.newWindow(Loc.Dir(real.absolutePath)) }
        }
        items += null
        items += MenuItem("Cut") { Clip.set(c, listOf(f), true); c.toast("Cut 1") }
        items += MenuItem("Copy") { Clip.set(c, listOf(f), false); c.toast("Copied 1") }
        if (real.isDirectory && Clip.has(c)) items += MenuItem("Paste into folder") { Ops.paste(c, real) { refresh() } }
        items += MenuItem("Delete", danger = true) {
            Jobs.start(Job("Moving " + f.name + " to Trash"), { j -> j.total = 1; j.detail = f.name; if (!TrashStore.trash(c, f)) j.failed++; j.done++ }) { cut(cols.indexOf(col)); refresh() }
        }
        items += null
        items += MenuItem("Create shortcut on Desktop") { c.toast(if (Fs.makeShortcut(real) != null) "Shortcut created on Desktop" else "Could not create the shortcut") }
        items += MenuItem("Copy to Desktop") { Jobs.start(Job("Copying " + f.name + " to Desktop"), { Ops.transfer(listOf(f), Fs.desktop(), false, it) }) { host.refreshAll() }; host.showStatus() }
        Pop(c).showAt(root, lastX, lastY, items)
    }

    override fun refresh() { for (col in cols) col.load() }
}
