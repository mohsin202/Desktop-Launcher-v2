package com.android.desktopfiles

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

object Dialogs {
    fun ask(act: android.app.Activity, title: String, initial: String, okLabel: String, cb: (String) -> Unit) {
        val et = EditText(act); et.setText(initial); et.setSingleLine()
        val base = initial.substringBeforeLast('.', initial)
        et.setSelection(0, base.length)
        val box = FrameLayout(act); box.setPaddingRelative(act.dp(20), act.dp(8), act.dp(20), 0); box.addView(et)
        val d = AlertDialog.Builder(act).setTitle(T.t(title)).setView(box)
            .setPositiveButton(T.t(okLabel)) { _, _ -> val s = et.text.toString().trim(); if (s.isNotEmpty()) cb(s) }
            .setNegativeButton(T.t("Cancel"), null).create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    fun confirm(act: android.app.Activity, title: String, msg: String, okLabel: String, cb: () -> Unit) {
        AlertDialog.Builder(act).setTitle(T.t(title)).setMessage(T.t(msg))
            .setPositiveButton(T.t(okLabel)) { _, _ -> cb() }.setNegativeButton(T.t("Cancel"), null).show()
    }
}

/** A folder (or search results) as Details list or Grid. */
class FilePage(private val host: Host, private val loc: Loc, private val pane: Int = 0) : Page {
    private val c: Context = host.act
    private val p = Ui.pal(c)
    private val dir: File? = (loc as? Loc.Dir)?.let { File(it.path) }

    private var items: List<File> = emptyList()
    private val sel = LinkedHashSet<String>()
    private var anchor = -1
    private var loading = true
    private var message = ""
    private var lastMeta = 0; private var lastX = 0; private var lastY = 0
    private var armed = false
    private var showDate = true; private var showType = true

    private val cmd = LinearLayout(c)
    private val headerBox = FrameLayout(c)
    private val frame = FrameLayout(c)
    private val status = Ui.text(c, "", 12f, p.sub)
    private val emptyView = Ui.text(c, "", 14f, p.sub)
    private val list = ListView(c)
    private val grid = GridView(c)

    private val root = object : LinearLayout(c) {
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            val wd = (w / resources.displayMetrics.density).toInt()
            val d = wd >= 440; val t = wd >= 620
            if (d != showDate || t != showType) { showDate = d; showType = t; buildHeader(); adapter.notifyDataSetChanged() }
        }
    }
    override val view: View get() = root

    private fun sortKey() = Store.str(c, "sortKey", "name")
    private fun asc() = Store.bool(c, "sortAsc", true)
    private fun isGrid() = Store.str(c, "view", "details") == "grid"

    /** A line of the list: a group header (header != null) or the item number idx. */
    private class Row(val header: String?, val idx: Int, val count: Int = 0)
    private var rows: List<Row> = emptyList()
    private val closedGroups = HashSet<String>()

    private fun groupMode() = Store.str(c, "group", "none")
    private fun grouping() = groupMode() != "none" && !isGrid()

    /** Order number and title of the group a file belongs to (folders always form the first group). */
    private fun gKey(f: File): Pair<Int, String> {
        if (f.isDirectory) return -1 to "Folders"
        return when (groupMode()) {
            "name" -> { val ch = f.name.firstOrNull()?.uppercaseChar(); if (ch != null && ch.isLetter()) ch.code to ch.toString() else 0 to "#" }
            "type" -> 0 to Fs.typeName(f)
            "date" -> {
                val d = (System.currentTimeMillis() - f.lastModified()) / 86_400_000L
                when { d < 1 -> 5 to "Today"; d < 2 -> 4 to "Yesterday"; d < 7 -> 3 to "Earlier this week"; d < 30 -> 2 to "Earlier this month"; d < 365 -> 1 to "Earlier this year"; else -> 0 to "A long time ago" }
            }
            "size" -> {
                val n = f.length()
                when { n == 0L -> 0 to "Empty"; n < 16_384L -> 1 to "Tiny (under 16 KB)"; n < 1_048_576L -> 2 to "Small (under 1 MB)"; n < 134_217_728L -> 3 to "Medium (under 128 MB)"; n < 1_073_741_824L -> 4 to "Large (under 1 GB)"; else -> 5 to "Huge (1 GB and more)" }
            }
            else -> 0 to ""
        }
    }

    private fun buildRows() {
        if (!grouping()) { rows = items.indices.map { Row(null, it) }; return }
        val counts = items.groupingBy { gKey(it).second }.eachCount()
        val out = ArrayList<Row>(); var last: String? = null
        for ((i, f) in items.withIndex()) {
            val g = gKey(f).second
            if (g != last) { out.add(Row(g, -1, counts[g] ?: 0)); last = g }
            if (g !in closedGroups) out.add(Row(null, i))
        }
        rows = out
    }

    private val adapter = object : BaseAdapter() {
        override fun notifyDataSetChanged() { buildRows(); super.notifyDataSetChanged() }
        override fun getCount() = rows.size
        override fun getItem(i: Int): Any = rows[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(i: Int) = if (rows[i].header != null) 1 else 0
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
            val r = rows[i]
            if (r.header != null) return groupHeader(r)
            val h = (cv?.tag as? RowH)?.takeIf { it.grid == isGrid() } ?: (if (isGrid()) gridCell() else detailsRow())
            bindRow(h, r.idx)
            return h.root
        }
    }

    private fun groupHeader(r: Row): View {
        val open = r.header !in closedGroups
        val t = Ui.text(c, (if (open) "▾  " else "▸  ") + T.t(r.header!!) + "  (" + r.count + ")", 13f, p.accent, true, false)
        t.gravity = Gravity.CENTER_VERTICAL; t.setPaddingRelative(c.dp(14), 0, c.dp(8), 0)
        t.layoutParams = AbsListView.LayoutParams(MATCH, c.dp(36)); t.isClickable = true
        t.setOnClickListener { if (!closedGroups.add(r.header!!)) closedGroups.remove(r.header); adapter.notifyDataSetChanged() }
        return t
    }

    class RowH(val root: View, val grid: Boolean, val check: TextView?, val icon: ImageView, val name: TextView,
               val sub: TextView?, val date: TextView?, val type: TextView?, val size: TextView?)

    init {
        root.orientation = LinearLayout.VERTICAL
        val sv = HorizontalScrollView(c); sv.isHorizontalScrollBarEnabled = false
        cmd.gravity = Gravity.CENTER_VERTICAL; cmd.setPaddingRelative(c.dp(6), 0, c.dp(6), 0)
        sv.addView(cmd)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, c.dp(46)))
        root.addView(headerBox, LinearLayout.LayoutParams(MATCH, WRAP))
        list.divider = null; list.adapter = adapter; list.selector = android.graphics.drawable.ColorDrawable(0)
        grid.adapter = adapter; grid.numColumns = GridView.AUTO_FIT; grid.columnWidth = c.dp(104)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH; grid.selector = android.graphics.drawable.ColorDrawable(0)
        grid.setPaddingRelative(c.dp(8), c.dp(4), c.dp(8), c.dp(4)); grid.clipToPadding = false
        emptyView.gravity = Gravity.CENTER
        frame.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
        for (v in listOf<View>(list, grid)) {
            if (dir != null) DnD.attach(host, v) { dir }
            v.setOnContextClickListener { ctxMenu(null, lastX, lastY); true }
            v.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { lastMeta = e.metaState; lastX = e.rawX.toInt(); lastY = e.rawY.toInt() }; false }
        }
        root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        status.setPaddingRelative(c.dp(14), c.dp(6), c.dp(14), c.dp(6))
        root.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
        buildHeader(); buildBar(); applyView()
        refresh()
    }

    // ---------- loading ----------
    override fun refresh() {
        loading = true; updateEmpty()
        val keep = HashSet(sel)
        val hidden = Store.bool(c, "showHidden", false)
        Thread {
            var msg = ""
            var found: List<File> = emptyList()
            try {
                when (loc) {
                    is Loc.Dir -> {
                        val d = File(loc.path)
                        var k: List<File>? = try { d.listFiles()?.toList() } catch (_: Exception) { null }
                        if (k == null && RootSu.ensure()) k = RootSu.list(d.absolutePath)
                        if (k != null && loc.only.isNotEmpty()) k = k.filter { Kinds.match(loc.only, it) }
                        if (k == null) msg = if (RootSu.state == 2 && d.absolutePath == "/") "Root access is needed to read this folder" else "This folder cannot be opened"
                        found = k ?: emptyList()
                    }
                    is Loc.Search -> {
                        val q = loc.q.lowercase(); val out = ArrayList<File>()
                        File(loc.base).walkTopDown().onEnter { !it.name.startsWith(".") || it.absolutePath == loc.base }.forEach {
                            if (out.size < 600 && it.absolutePath != loc.base && it.name.lowercase().contains(q) && !it.name.startsWith(".")) out.add(it)
                        }
                        found = out
                        if (out.isEmpty()) msg = "No results for \"" + loc.q + "\""
                    }
                    is Loc.Special -> if (loc.kind.startsWith("tag:")) {
                        found = Tags.files(loc.kind.substring(4))
                        if (found.isEmpty()) msg = "No items have this tag yet"
                    }
                    else -> {}
                }
            } catch (_: Exception) {}
            val vis = found.filter { hidden || !it.name.startsWith(".") }.filter { !it.name.startsWith(".trashed-") && it.name != ".deskclip" }
            val sorted = sortList(vis)
            c.mainExecutor.execute {
                items = sorted; message = if (msg.isEmpty() && sorted.isEmpty()) "This folder is empty" else msg
                loading = false
                sel.retainAll(items.map { it.absolutePath }.toSet().let { s -> keep.filter { it in s }.toSet() })
                adapter.notifyDataSetChanged(); updateEmpty(); updateStatus(); buildBar()
            }
        }.start()
    }

    private fun sortList(l: List<File>): List<File> {
        val k = sortKey()
        val cmp: Comparator<File> = when (k) {
            "date" -> compareBy<File> { it.lastModified() }
            "size" -> compareBy<File> { if (it.isDirectory) -1L else it.length() }
            "type" -> compareBy<File> { it.extension.lowercase() }.thenBy { it.name.lowercase() }
            else -> compareBy<File> { it.name.lowercase() }
        }
        val s = l.sortedWith(cmp)
        val ordered = if (asc()) s else s.reversed()
        if (!grouping()) return ordered.sortedBy { if (it.isDirectory) 0 else 1 }
        return ordered.sortedWith(Comparator { a, b ->
            if (a.isDirectory != b.isDirectory) return@Comparator if (a.isDirectory) -1 else 1
            if (a.isDirectory) return@Comparator 0
            val ka = gKey(a); val kb = gKey(b)
            val cm = if (ka.first != kb.first) ka.first.compareTo(kb.first) else ka.second.compareTo(kb.second)
            if (asc()) cm else -cm
        })
    }

    private fun updateEmpty() {
        emptyView.text = T.t(if (loading) "Loading…" else message)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateStatus() {
        val files = selFiles()
        var s = items.size.toString() + (if (items.size == 1) " item" else " items")
        if (files.isNotEmpty()) {
            s += "   |   " + files.size + " selected"
            val sz = files.filter { it.isFile }.sumOf { it.length() }
            if (files.any { it.isFile }) s += "  " + Fs.size(c, sz)
        }
        status.text = T.t(s)
    }

    private fun applyView() {
        list.visibility = if (isGrid()) View.GONE else View.VISIBLE
        grid.visibility = if (isGrid()) View.VISIBLE else View.GONE
        headerBox.visibility = if (isGrid()) View.GONE else View.VISIBLE
        items = sortList(items)
        adapter.notifyDataSetChanged()
    }

    // ---------- header + command bar ----------
    private fun buildHeader() {
        headerBox.removeAllViews()
        val row = LinearLayout(c); row.gravity = Gravity.CENTER_VERTICAL
        row.setPaddingRelative(c.dp(8), 0, c.dp(8), 0)
        fun col(label: String, key: String, w: Int): TextView {
            val arrow = if (sortKey() == key) (if (asc()) " ▲" else " ▼") else ""
            val t = Ui.text(c, T.t(label) + arrow, 12f, p.sub, sortKey() == key, false)
            t.gravity = Gravity.CENTER_VERTICAL; t.isClickable = true
            t.background = Ui.ripple(c, p, null, c.dpf(6f))
            t.setOnClickListener {
                if (sortKey() == key) Store.setBool(c, "sortAsc", !asc()) else { Store.setStr(c, "sortKey", key); Store.setBool(c, "sortAsc", true) }
                resort()
            }
            row.addView(t, LinearLayout.LayoutParams(if (w == 0) 0 else c.dp(w), c.dp(32), if (w == 0) 1f else 0f))
            return t
        }
        row.addView(View(c), LinearLayout.LayoutParams(c.dp(66), 1))
        col("Name", "name", 0)
        if (showDate) col("Date modified", "date", 150)
        if (showType) col("Type", "type", 110)
        col("Size", "size", 80)
        headerBox.addView(row)
        val line = View(c); line.setBackgroundColor(p.line)
        headerBox.addView(line, FrameLayout.LayoutParams(MATCH, 1, Gravity.BOTTOM))
    }

    private fun resort() {
        items = sortList(items); buildHeader(); adapter.notifyDataSetChanged(); buildBar()
    }

    private fun btn(label: String, on: Boolean = true, click: (View) -> Unit) {
        val t = Ui.text(c, label, 13f, if (on) p.text else p.sub)
        t.setPaddingRelative(c.dp(12), 0, c.dp(12), 0); t.gravity = Gravity.CENTER
        t.alpha = if (on) 1f else 0.45f
        if (on) { t.isClickable = true; t.background = Ui.ripple(c, p, null, c.dpf(8f)); t.setOnClickListener(click) }
        cmd.addView(t, LinearLayout.LayoutParams(WRAP, c.dp(36)))
    }

    private fun sep() {
        val v = View(c); v.setBackgroundColor(p.line)
        cmd.addView(v, LinearLayout.LayoutParams(1, c.dp(20)).also { it.marginStart = c.dp(4); it.marginEnd = c.dp(4) })
    }

    private fun buildBar() {
        cmd.removeAllViews()
        val n = sel.size
        val canEdit = dir != null
        btn("+ New ▾", canEdit) { v -> Pop(c).showBelow(v, newItems()) }
        sep()
        btn("Cut", n > 0) { doCut(selFiles()) }
        btn("Copy", n > 0) { doCopy(selFiles()) }
        btn("Paste", canEdit && Clip.has(c)) { doPaste(dir!!) }
        btn("Rename", n == 1) { rename(selFiles()[0]) }
        btn("Share", n > 0) { Fs.share(c, selFiles()) }
        btn("Delete", n > 0) { delete(selFiles(), false) }
        sep()
        if ((loc as? Loc.Dir)?.only?.isNotEmpty() == true) btn("Show all files") { host.go(Loc.Dir(loc.path)) }
        btn("Sort ▾") { v -> Pop(c).showBelow(v, sortItems()) }
        btn("View ▾") { v -> Pop(c).showBelow(v, viewItems()) }
    }

    private fun sortItems(): List<MenuItem?> {
        fun it(l: String, k: String) = MenuItem(l, checked = sortKey() == k) { Store.setStr(c, "sortKey", k); resort() }
        return listOf(it("Name", "name"), it("Date modified", "date"), it("Size", "size"), it("Type", "type"), null,
            MenuItem("Ascending", checked = asc()) { Store.setBool(c, "sortAsc", true); resort() },
            MenuItem("Descending", checked = !asc()) { Store.setBool(c, "sortAsc", false); resort() })
    }

    private fun viewItems(): List<MenuItem?> = listOf(
        MenuItem("Details", checked = !isGrid()) { Store.setStr(c, "view", "details"); applyView() },
        MenuItem("Grid", checked = isGrid()) { Store.setStr(c, "view", "grid"); applyView() },
        MenuItem("Columns", checked = false) { Store.setStr(c, "view", "columns"); host.relayout() },
        null,
        MenuItem("Group by", sub = listOf("none" to "None", "name" to "Name", "type" to "Type", "date" to "Date modified", "size" to "Size").map { g ->
            MenuItem(g.second, checked = groupMode() == g.first) { Store.setStr(c, "group", g.first); closedGroups.clear(); resort() }
        }),
        null,
        MenuItem("Show hidden files", checked = Store.bool(c, "showHidden", false)) { Store.setBool(c, "showHidden", !Store.bool(c, "showHidden", false)); host.refreshAll() })

    private fun newItems(): List<MenuItem> = listOf(
        MenuItem("Folder") { newEntry(true) },
        MenuItem("Text file") { newEntry(false) })

    // ---------- rows ----------
    private fun checkView(): TextView {
        val t = Ui.text(c, "", 13f, 0xFFFFFFFF.toInt(), true); t.gravity = Gravity.CENTER; return t
    }

    private fun detailsRow(): RowH {
        val r = LinearLayout(c); r.gravity = Gravity.CENTER_VERTICAL
        r.layoutParams = AbsListView.LayoutParams(MATCH, c.dp(44)); r.setPaddingRelative(c.dp(8), 0, c.dp(8), 0)
        val ck = checkView()
        r.addView(ck, LinearLayout.LayoutParams(c.dp(22), c.dp(22)).also { it.marginEnd = c.dp(6) })
        val iv = ImageView(c)
        r.addView(iv, LinearLayout.LayoutParams(c.dp(30), c.dp(30)).also { it.marginEnd = c.dp(8) })
        val nameBox = LinearLayout(c); nameBox.orientation = LinearLayout.VERTICAL
        val nm = Ui.text(c, "", 14f, p.text); nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        val sub = Ui.text(c, "", 10f, p.sub); sub.maxLines = 1; sub.ellipsize = android.text.TextUtils.TruncateAt.START
        nameBox.addView(nm); nameBox.addView(sub)
        r.addView(nameBox, LinearLayout.LayoutParams(0, WRAP, 1f))
        fun cell(w: Int): TextView { val t = Ui.text(c, "", 12f, p.sub); t.maxLines = 1; r.addView(t, LinearLayout.LayoutParams(c.dp(w), WRAP)); return t }
        val d = if (showDate) cell(150) else null
        val ty = if (showType) cell(110) else null
        val sz = cell(80)
        val h = RowH(r, false, ck, iv, nm, sub, d, ty, sz); r.tag = h
        return h
    }

    private fun gridCell(): RowH {
        val r = LinearLayout(c); r.orientation = LinearLayout.VERTICAL; r.gravity = Gravity.CENTER_HORIZONTAL
        r.layoutParams = AbsListView.LayoutParams(MATCH, c.dp(112)); r.setPaddingRelative(c.dp(4), c.dp(8), c.dp(4), c.dp(4))
        val iv = ImageView(c); iv.scaleType = ImageView.ScaleType.FIT_CENTER
        r.addView(iv, LinearLayout.LayoutParams(c.dp(60), c.dp(60)))
        val nm = Ui.text(c, "", 12f, p.text); nm.maxLines = 2; nm.gravity = Gravity.CENTER_HORIZONTAL
        nm.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        r.addView(nm, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = c.dp(4) })
        val h = RowH(r, true, null, iv, nm, null, null, null, null); r.tag = h
        return h
    }

    private fun bindRow(h: RowH, i: Int) {
        val f = items[i]
        val on = f.absolutePath in sel
        h.name.text = Tags.decorate(Fs.shown(f), f.absolutePath)
        Thumbs.bind(h.icon, f, c.dp(if (h.grid) 120 else 60))
        h.root.background = Ui.ripple(c, p, if (on) Ui.rounded(p.sel, c.dpf(8f)) else null, c.dpf(8f))
        h.sub?.let { it.text = if (loc is Loc.Search) (f.parent ?: "") else ""; it.visibility = if (loc is Loc.Search) View.VISIBLE else View.GONE }
        h.date?.text = Fs.date(f.lastModified())
        h.type?.text = T.t(Fs.typeName(f))
        h.size?.text = if (f.isDirectory) "" else Fs.size(c, f.length())
        h.check?.let {
            it.text = if (on) "✓" else ""
            it.background = if (on) Ui.rounded(p.accent, c.dpf(11f)) else Ui.rounded(0, c.dpf(11f), p.sub, c.dp(1))
            it.setOnClickListener { toggle(i) }
        }
        h.root.isClickable = true
        h.root.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastMeta = e.metaState; lastX = e.rawX.toInt(); lastY = e.rawY.toInt()
                    armed = e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE && (e.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
                }
                MotionEvent.ACTION_MOVE -> if (armed && Math.hypot((e.rawX - lastX).toDouble(), (e.rawY - lastY).toDouble()) > c.dp(10)) {
                    armed = false
                    val it = items[i]
                    val files = if (it.absolutePath in sel) selFiles() else listOf(it)
                    if (DnD.start(v, files) && it.absolutePath !in sel) { sel.clear(); sel.add(it.absolutePath); anchor = i; changed() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> armed = false
            }
            false
        }
        val folder = (Fs.target(f) ?: f).takeIf { it.isDirectory }
        if (folder != null) DnD.attach(host, h.root) { folder } else h.root.setOnDragListener(null)
        h.root.setOnClickListener { tap(i) }
        h.root.setOnLongClickListener { ctxMenu(items[i], lastX, lastY); true }
        h.root.setOnContextClickListener { ctxMenu(items[i], lastX, lastY); true }
    }

    // ---------- selection ----------
    private fun selFiles(): List<File> = items.filter { it.absolutePath in sel }

    private fun changed() { adapter.notifyDataSetChanged(); updateStatus(); buildBar(); host.selectionChanged(selFiles()) }

    private fun toggle(i: Int) {
        val k = items[i].absolutePath
        if (!sel.add(k)) sel.remove(k)
        anchor = i; changed()
    }

    private fun tap(i: Int) {
        val ctrl = lastMeta and KeyEvent.META_CTRL_ON != 0
        val shift = lastMeta and KeyEvent.META_SHIFT_ON != 0
        when {
            ctrl -> toggle(i)
            shift && anchor >= 0 -> {
                sel.clear()
                for (j in minOf(anchor, i)..maxOf(anchor, i)) sel.add(items[j].absolutePath)
                changed()
            }
            sel.isNotEmpty() && !(sel.size == 1 && items[i].absolutePath in sel && !Store.bool(c, "openSingle", true)) -> toggle(i)
            Store.bool(c, "openSingle", true) -> open(items[i])
            else -> {
                if (sel.size == 1 && items[i].absolutePath in sel) open(items[i])
                else { sel.clear(); sel.add(items[i].absolutePath); anchor = i; changed() }
            }
        }
    }

    private fun selectAll() { sel.clear(); items.forEach { sel.add(it.absolutePath) }; changed() }

    // ---------- actions ----------
    fun open(f: File) {
        val real = Fs.target(f) ?: f
        when {
            f.extension == "desktoplink" && !real.exists() -> c.toast("The shortcut's target no longer exists")
            real.isDirectory -> host.go(Loc.Dir(real.absolutePath))
            else -> host.openFile(real)
        }
    }

    private fun doCut(files: List<File>) { if (files.isEmpty()) return; Clip.set(c, files, true); c.toast("Cut " + files.size); buildBar() }
    private fun doCopy(files: List<File>) { if (files.isEmpty()) return; Clip.set(c, files, false); c.toast("Copied " + files.size); buildBar() }
    private fun doPaste(d: File) { if (!Ops.paste(c, d) { host.refreshAll() }) c.toast("Nothing to paste") }

    private fun rename(f: File) {
        Dialogs.ask(host.act, "Rename", f.name, "Rename") { n ->
            val t = File(f.parentFile, n)
            Thread {
                val msg = if (RootSu.exists(t)) "That name already exists"
                    else if (f.renameTo(t) || (RootSu.ensure() && RootSu.sh("mv " + RootSu.q(f) + " " + RootSu.q(t)))) { Tags.move(f.absolutePath, t.absolutePath); "" } else "Could not rename"
                c.mainExecutor.execute { if (msg.isNotEmpty()) c.toast(msg); sel.clear(); host.refreshAll() }
            }.start()
        }
    }

    private fun delete(files: List<File>, forever: Boolean) {
        if (files.isEmpty()) return
        val outside = files.any { !it.absolutePath.startsWith(Fs.ext().absolutePath) }
        if (forever || outside) {
            val why = if (outside && !forever) "These items are outside your storage and cannot go to the Trash.\n" else ""
            Dialogs.confirm(host.act, "Delete permanently", why + "Delete " + files.size + " item(s) permanently? This cannot be undone.", "Delete") {
                Jobs.start(Job("Deleting " + files.size + " items"), { Ops.deleteForever(files, it) }) { sel.clear(); host.refreshAll() }
                host.showStatus()
            }
            return
        }
        Jobs.start(Job("Moving " + files.size + (if (files.size == 1) " item" else " items") + " to Trash"), { j ->
            j.total = files.size.toLong()
            for (f in files) { if (j.cancelled) break; j.detail = f.name; if (!TrashStore.trash(c, f)) j.failed++; j.done++ }
        }) { sel.clear(); host.refreshAll() }
    }

    private fun toDesktop(files: List<File>, move: Boolean) {
        val d = Fs.desktop()
        Jobs.start(Job((if (move) "Moving " else "Copying ") + files.size + " item(s) to Desktop"), { Ops.transfer(files, d, move, it) }) { host.refreshAll() }
        host.showStatus()
    }

    private fun tagItems(fs: List<File>): List<MenuItem> {
        val paths = fs.map { it.absolutePath }
        val l = Tags.all().map { t ->
            MenuItem(t.name, checked = paths.all { pa -> Tags.of(pa).any { it.id == t.id } }) { Tags.toggle(paths, t.id); changed() }
        }
        return l + MenuItem("New tag…") { TagUi.create(host.act) { t -> Tags.toggle(paths, t.id); host.pinsChanged() } }
    }

    private fun transferTo(files: List<File>, d: File, move: Boolean) {
        Jobs.start(Job((if (move) "Moving " else "Copying ") + files.size + " item(s) to " + d.name), { Ops.transfer(files, d, move, it) }) { host.refreshAll() }
        host.showStatus()
    }

    private fun compress(files: List<File>) {
        val parent = files[0].parentFile ?: return
        val name = if (files.size == 1) files[0].nameWithoutExtension.ifEmpty { files[0].name } else "Archive"
        val out = Fs.unique(parent, "$name.zip")
        Jobs.start(Job("Compressing " + files.size + " item(s) to " + out.name), { Ops.zip(files, out, it) }) { sel.clear(); host.refreshAll() }
        host.showStatus()
    }

    private fun extract(zip: File, intoFolder: Boolean) {
        val parent = zip.parentFile ?: return
        val dest = if (intoFolder) Fs.unique(parent, zip.nameWithoutExtension) else parent
        Jobs.start(Job("Extracting " + zip.name), { Ops.unzip(zip, dest, it) }) { host.refreshAll() }
        host.showStatus()
    }

    private fun shortcuts(files: List<File>) {
        var n = 0
        for (f in files) if (Fs.makeShortcut(Fs.target(f) ?: f) != null) n++
        c.toast(if (n > 0) "Shortcut created on Desktop" else "Could not create the shortcut")
    }

    private fun newEntry(folder: Boolean) {
        val d = dir ?: return
        Dialogs.ask(host.act, if (folder) "New folder" else "New text file", if (folder) "New folder" else "New text file.txt", "Create") { n ->
            Thread {
                val t = if (d.canWrite()) Fs.unique(d, n) else File(d, n)
                var ok = try { if (folder) t.mkdirs() else t.createNewFile() } catch (_: Exception) { false }
                if (!ok && RootSu.ensure() && !RootSu.exists(t)) ok = RootSu.sh((if (folder) "mkdir -p " else "touch ") + RootSu.q(t))
                c.mainExecutor.execute { if (!ok) c.toast("Could not create it"); host.refreshAll() }
            }.start()
        }
    }

    private fun properties(files: List<File>) {
        val f = files.firstOrNull() ?: dir ?: return
        val single = files.size <= 1
        fun text(size: String) = if (single) "Type: " + Fs.typeName(f) + "\nLocation: " + (f.parent ?: "/") + "\nSize: " + size + "\nModified: " + Fs.date(f.lastModified())
            else files.size.toString() + " items\nSize: " + size
        val d = AlertDialog.Builder(host.act).setTitle(if (single) f.name else T.t(files.size.toString() + " items")).setMessage(T.t(text("calculating…")))
            .setPositiveButton(T.t("OK"), null).show()
        Thread {
            val total = if (single) Ops.sizeOf(f) else files.sumOf { Ops.sizeOf(it) }
            host.act.runOnUiThread { d.setMessage(T.t(text(Fs.size(c, total) + " (" + total + " bytes)"))) }
        }.start()
    }

    private fun ctxMenu(f: File?, x: Int, y: Int) {
        val menu = ArrayList<MenuItem?>()
        if (f != null) {
            if (f.absolutePath !in sel) { sel.clear(); sel.add(f.absolutePath); anchor = items.indexOf(f); changed() }
            val fs = selFiles(); val one = fs.size == 1
            val target = if (one) (Fs.target(fs[0]) ?: fs[0]) else null
            menu += MenuItem("Open", one) { open(fs[0]) }
            if (one && target != null && target.isFile) menu += MenuItem("Open with…") { Fs.view(c, target, true) }
            if (one && target != null && target.isDirectory) {
                menu += MenuItem("Open in new tab") { host.newTab(Loc.Dir(target.absolutePath)) }
                menu += MenuItem("Open in new window") { host.newWindow(Loc.Dir(target.absolutePath)) }
            }
            menu += null
            menu += MenuItem("Cut") { doCut(fs) }
            menu += MenuItem("Copy") { doCopy(fs) }
            if (one && target != null && target.isDirectory && Clip.has(c)) menu += MenuItem("Paste into folder") { doPaste(target) }
            menu += MenuItem("Rename", one) { rename(fs[0]) }
            menu += MenuItem("Delete", danger = true) { delete(fs, false) }
            menu += MenuItem("Delete permanently", danger = true) { delete(fs, true) }
            menu += null
            menu += MenuItem("Compress to ZIP", dir != null || loc is Loc.Search) { compress(fs) }
            if (one && target != null && target.isFile && target.extension.equals("zip", true)) {
                menu += MenuItem("Extract to folder") { extract(target, true) }
                menu += MenuItem("Extract here") { extract(target, false) }
            }
            val od = host.otherPaneDir(pane)
            if (od != null) {
                menu += MenuItem("Copy to other pane") { transferTo(fs, od, false) }
                menu += MenuItem("Move to other pane") { transferTo(fs, od, true) }
            }
            menu += null
            menu += MenuItem("Share", fs.any { it.isFile }) { Fs.share(c, fs) }
            if (one && target != null && target.isDirectory) menu += MenuItem("Pin to Quick access") { Store.pin(c, "dir:" + target.absolutePath); host.pinsChanged(); c.toast("Pinned") }
            menu += MenuItem("Tags", sub = tagItems(fs))
            menu += MenuItem("Create shortcut on Desktop") { shortcuts(fs) }
            menu += MenuItem("Copy to Desktop") { toDesktop(fs, false) }
            menu += MenuItem("Move to Desktop") { toDesktop(fs, true) }
            menu += null
            if (one && RootSu.state == 1 && !fs[0].absolutePath.startsWith(Fs.ext().absolutePath)) menu += MenuItem("Permissions…") { RootUi.permissions(host.act, fs[0]) { host.refreshAll() } }
            menu += MenuItem("Properties") { properties(fs) }
        } else {
            if (dir != null) {
                menu += MenuItem("New", sub = newItems())
                menu += MenuItem("Paste", Clip.has(c)) { doPaste(dir) }
                menu += null
            }
            menu += MenuItem("Select all") { selectAll() }
            menu += MenuItem("Refresh") { host.refreshAll() }
            if (dir != null) {
                menu += null
                menu += MenuItem("Pin this folder to Quick access") { Store.pin(c, "dir:" + dir.absolutePath); host.pinsChanged(); c.toast("Pinned") }
                if (RootSu.state == 1 && !dir.absolutePath.startsWith(Fs.ext().absolutePath)) menu += MenuItem("Permissions…") { RootUi.permissions(host.act, dir) { host.refreshAll() } }
                menu += MenuItem("Properties") { properties(emptyList()) }
            }
        }
        Pop(c).showAt(root, x, y, menu)
    }

    override fun onKey(code: Int, ctrl: Boolean, shift: Boolean): Boolean {
        val d = dir
        when {
            ctrl && code == KeyEvent.KEYCODE_A -> selectAll()
            ctrl && code == KeyEvent.KEYCODE_C -> doCopy(selFiles())
            ctrl && code == KeyEvent.KEYCODE_X -> doCut(selFiles())
            ctrl && code == KeyEvent.KEYCODE_V && d != null -> doPaste(d)
            ctrl && shift && code == KeyEvent.KEYCODE_N && d != null -> newEntry(true)
            code == KeyEvent.KEYCODE_FORWARD_DEL -> delete(selFiles(), shift)
            code == KeyEvent.KEYCODE_F2 && sel.size == 1 -> rename(selFiles()[0])
            (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) && sel.size == 1 -> open(selFiles()[0])
            code == KeyEvent.KEYCODE_ESCAPE && sel.isNotEmpty() -> { sel.clear(); changed() }
            else -> return false
        }
        return true
    }
}
