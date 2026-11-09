package com.android.desktopfiles

import android.content.Context
import android.mtp.MtpDevice
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
import android.widget.GridView
import android.widget.ListView
import java.io.File

/**
 * The storage of a phone connected by USB (MTP): browse (each folder is its own place in the back/forward history), open, copy to this device,
 * send files to the phone (Paste), new folder, delete.
 */
class MtpPage(private val host: Host, private val loc: Loc.Mtp) : Page {
    private val c: Context = host.act
    private val p = Ui.pal(c)
    private var dev: MtpDevice? = null
    private var items: List<Mtp.Item> = emptyList()
    private var storage = loc.storage      // the storage shown (when the phone has only one, it is chosen by itself)
    private var lastX = 0; private var lastY = 0; private var lastMeta = 0
    private val sel = LinkedHashSet<Int>()
    private var anchor = -1

    private val root = LinearLayout(c)
    private val cmd = LinearLayout(c)
    private val list = ListView(c)
    private val grid = Tiles.gridView(c)
    private val empty = Ui.text(c, "", 14f, p.sub)
    override val view: View get() = root

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int): Any = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
            val item = items[i]
            val on = item.handle in sel && !item.storage
            val icon = if (item.dir) Glyph(if (item.storage) Glyph.Kind.PHONE else Glyph.Kind.FOLDER) else Thumbs.icon(File(item.name))
            val r: LinearLayout
            if (Tiles.isGrid(c)) r = Tiles.tile(c, p, icon, item.name)
            else {
                r = LinearLayout(c); r.gravity = Gravity.CENTER_VERTICAL
                r.layoutParams = AbsListView.LayoutParams(MATCH, c.dp(46)); r.setPaddingRelative(c.dp(12), 0, c.dp(12), 0)
                r.isClickable = true
                val iv = ImageView(c); iv.setImageDrawable(icon)
                r.addView(iv, LinearLayout.LayoutParams(c.dp(30), c.dp(30)).also { l -> l.marginEnd = c.dp(10) })
                val nm = Ui.text(c, item.name, 14f, p.text, false, false); nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                r.addView(nm, LinearLayout.LayoutParams(0, WRAP, 1f))
                if ((!item.dir) || (item.storage && item.size > 0)) r.addView(Ui.text(c, Fs.size(c, item.size), 12f, p.sub), LinearLayout.LayoutParams(c.dp(80), WRAP))
            }
            r.background = Ui.ripple(c, p, if (on) Ui.rounded(p.sel, c.dpf(8f)) else null, c.dpf(8f))
            r.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { lastX = e.rawX.toInt(); lastY = e.rawY.toInt(); lastMeta = e.metaState }; false }
            r.setOnClickListener { tap(i) }
            r.setOnLongClickListener { ctx(item, lastX, lastY); true }
            r.setOnContextClickListener { ctx(item, lastX, lastY); true }
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
        grid.adapter = adapter; fr.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        DeskScroll.attach(fr, p, list, grid)
        empty.gravity = Gravity.CENTER; fr.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        // a click on the empty part clears the selection; a right click there opens the page menu
        fr.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { lastX = e.rawX.toInt(); lastY = e.rawY.toInt() }; false }
        list.setOnItemClickListener { _, _, _, _ -> }
        fr.setOnContextClickListener { ctx(null, lastX, lastY); true }
        list.setOnTouchListener { _, e -> emptyTouch(e, list) }
        grid.setOnTouchListener { _, e -> emptyTouch(e, grid) }
        root.addView(fr, LinearLayout.LayoutParams(MATCH, 0, 1f))
        buildBar(); start(); applyView()
    }

    /** A click or right click below the last item (nothing under the pointer). */
    private fun emptyTouch(e: MotionEvent, v: AbsListView): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            lastX = e.rawX.toInt(); lastY = e.rawY.toInt()
            if (v.pointToPosition(e.x.toInt(), e.y.toInt()) < 0) {
                if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) ctx(null, lastX, lastY)
                else if (sel.isNotEmpty()) { sel.clear(); changed() }
            }
        }
        return false
    }

    private fun applyView() {
        val g = Tiles.isGrid(c)
        list.visibility = if (g) View.GONE else View.VISIBLE
        grid.visibility = if (g) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun inStorage() = storage != -1

    // ---------- the command bar ----------
    private fun btn(label: String, on: Boolean = true, click: (View) -> Unit) {
        val t = Ui.text(c, label, 13f, p.text); t.setPaddingRelative(c.dp(12), 0, c.dp(12), 0); t.gravity = Gravity.CENTER; t.alpha = if (on) 1f else 0.45f
        if (on) { t.isClickable = true; t.background = Ui.ripple(c, p, null, c.dpf(8f)); t.setOnClickListener { click(t) } }
        cmd.addView(t, LinearLayout.LayoutParams(WRAP, c.dp(36)))
    }

    private fun ibtn(icon: String, label: String, on: Boolean, click: (View) -> Unit) {
        val v = ImageView(c)
        v.setImageDrawable(MenuIcon(icon, p.text, c.dp(20))); v.scaleType = ImageView.ScaleType.CENTER
        v.alpha = if (on) 1f else 0.35f
        v.contentDescription = T.t(label); v.tip(T.t(label))
        if (on) { v.isClickable = true; v.background = Ui.ripple(c, p, null, c.dpf(8f)); v.setOnClickListener(click) }
        cmd.addView(v, LinearLayout.LayoutParams(c.dp(40), c.dp(36)))
    }

    private fun sep() {
        val v = View(c); v.setBackgroundColor(p.line)
        cmd.addView(v, LinearLayout.LayoutParams(1, c.dp(20)).also { it.marginStart = c.dp(4); it.marginEnd = c.dp(4) })
    }

    private fun selItems(): List<Mtp.Item> = items.filter { it.handle in sel && !it.storage }
    private fun canPaste() = dev != null && inStorage() && (MtpClip.has() || Clip.has(c))

    private fun buildBar() {
        cmd.removeAllViews()
        val ready = dev != null && inStorage()
        val n = sel.size
        btn("+ New ▾", ready) { v -> Pop(c).showBelow(v, newItems()) }
        sep()
        ibtn("cut", "Cut", n > 0) { doCopy(selItems(), true) }
        ibtn("copy", "Copy", n > 0) { doCopy(selItems(), false) }
        ibtn("paste", "Paste", canPaste()) { paste() }
        ibtn("rename", "Rename", n == 1) { rename(selItems()[0]) }
        ibtn("share", "Share", n > 0 && selItems().any { !it.dir }) { share(selItems()) }
        ibtn("delete", "Delete", n > 0) { delete(selItems()) }
        sep()
        btn("View ▾") { v -> Pop(c).showBelow(v, Tiles.viewItems(c) { applyView() }) }
    }

    private fun newItems(): List<MenuItem> = listOf(MenuItem("Folder") {
        Dialogs.ask(host.act, "New folder", "New folder", "Create") { n -> work { Mtp.newFolder(dev!!, storage, loc.parent, n) != 0 } }
    })

    // ---------- selection ----------
    private fun changed() { adapter.notifyDataSetChanged(); buildBar() }

    private fun toggle(i: Int) { val h = items[i].handle; if (!sel.add(h)) sel.remove(h); anchor = i; changed() }

    private fun tap(i: Int) {
        val it = items[i]
        if (it.storage) { open(it); return }
        val ctrl = lastMeta and android.view.KeyEvent.META_CTRL_ON != 0
        val shift = lastMeta and android.view.KeyEvent.META_SHIFT_ON != 0
        when {
            ctrl -> toggle(i)
            shift && anchor >= 0 -> { sel.clear(); for (j in minOf(anchor, i)..maxOf(anchor, i)) if (!items[j].storage) sel.add(items[j].handle); changed() }
            sel.isNotEmpty() && !(sel.size == 1 && it.handle in sel && !Store.bool(c, "openSingle", true)) -> toggle(i)
            Store.bool(c, "openSingle", true) -> open(it)
            else -> { if (sel.size == 1 && it.handle in sel) open(it) else { sel.clear(); sel.add(it.handle); anchor = i; changed() } }
        }
    }

    // ---------- the menus ----------
    private fun ctx(it: Mtp.Item?, x: Int, y: Int) {
        val l = ArrayList<MenuItem?>()
        if (it != null && !it.storage) {
            if (it.handle !in sel) { sel.clear(); sel.add(it.handle); anchor = items.indexOf(it); changed() }
            val fs = selItems(); val one = fs.size == 1
            l += MenuItem("", row = listOf(
                MenuItem("Cut", icon = "cut") { doCopy(fs, true) },
                MenuItem("Copy", icon = "copy") { doCopy(fs, false) },
                MenuItem("Rename", one, icon = "rename") { rename(fs[0]) },
                MenuItem("Share", fs.any { f -> !f.dir }, icon = "share") { share(fs) },
                MenuItem("Delete", danger = true, icon = "delete") { delete(fs) }))
            l += null
            l += MenuItem("Open", one, hint = "Enter") { open(fs[0]) }
            if (one && fs[0].dir) {
                l += MenuItem("Open in new tab") { host.newTab(Loc.Mtp(loc.dev, storage, fs[0].handle, fs[0].name)) }
                l += MenuItem("Open in new window") { host.newWindow(Loc.Mtp(loc.dev, storage, fs[0].handle, fs[0].name)) }
                if (canPaste()) l += MenuItem("Paste into folder") { pasteInto(fs[0].handle) }
            }
            l += MenuItem("Properties", one) { props(fs[0]) }
            l += null
            val send = ArrayList<MenuItem>()
            send += MenuItem("Copy to Downloads") { save(fs, File(Fs.ext(), "Download")) }
            send += MenuItem("Copy to Desktop") { save(fs, Fs.desktop()) }
            host.otherPaneDir(0)?.let { d -> send += MenuItem("Copy to other pane") { save(fs, d) } }
            l += MenuItem("Send to", sub = send)
            l += null
            l += MenuItem("Select all") { selectAll() }
        } else if (it != null) {
            l += MenuItem("Open") { open(it) }
        } else {
            l += MenuItem("", row = listOf(MenuItem("Paste", canPaste(), icon = "paste") { paste() }))
            l += null
            l += MenuItem("New", dev != null && inStorage(), sub = newItems())
            l += MenuItem("Select all") { selectAll() }
            l += MenuItem("Refresh") { load() }
        }
        Pop(c).showAt(root, x, y, l, outside = { px, py ->
            val v: AbsListView = if (Tiles.isGrid(c)) grid else list
            val xy = IntArray(2); v.getLocationOnScreen(xy)
            val lx = px - xy[0]; val ly = py - xy[1]
            if (lx in 0 until v.width && ly in 0 until v.height && v.pointToPosition(lx, ly) < 0 && sel.isNotEmpty()) { sel.clear(); changed() }
        })
    }

    private fun selectAll() { sel.clear(); items.filter { !it.storage }.forEach { sel.add(it.handle) }; changed() }

    private fun props(it: Mtp.Item) {
        val m = dev
        val size = if (m != null && it.dir) null else it.size
        val msg = T.t("Name") + ": " + it.name + "\n" + T.t("Type") + ": " + T.t(if (it.dir) "File folder" else Fs.typeName(File(it.name))) +
            (if (size != null) "\n" + T.t("Size") + ": " + Fs.size(c, size) else "") + (if (it.mtime > 0) "\n" + T.t("Modified") + ": " + Fs.date(it.mtime) else "")
        android.app.AlertDialog.Builder(host.act).setTitle(T.t("Properties")).setMessage(msg).setPositiveButton(T.t("OK"), null).showDir()
    }

    // ---------- actions ----------
    private fun doCopy(fs: List<Mtp.Item>, cut: Boolean) {
        if (fs.isEmpty()) return
        Clip.clear(c)
        MtpClip.set(MtpClip.Data(loc.dev, storage, fs, cut))
        c.toast((if (cut) "Cut " else "Copied ") + fs.size)
        buildBar()
    }

    private fun rename(it: Mtp.Item) {
        val m = dev ?: return
        Dialogs.ask(host.act, "Rename", it.name, "Rename") { n ->
            if (n != it.name) MtpClip.rename(host, m, storage, loc.parent, it, n) { sel.clear(); load() }
        }
    }

    private fun share(fs: List<Mtp.Item>) {
        val m = dev ?: return
        val dir = File(Fs.ext(), ".deskshare").also { d -> d.mkdirs() }
        val files = fs.filter { !it.dir }
        val job = Job("Preparing " + files.size + " files")
        val out = ArrayList<File>()
        Jobs.start(job, { j ->
            j.total = files.sumOf { it.size }
            for (it in files) { if (j.cancelled) break; File(dir, it.name).delete(); if (Mtp.download(m, storage, it, dir, j)) out.add(File(dir, it.name)) else j.failed++ }
        }) { if (out.isNotEmpty()) Fs.share(c, out) else c.toast("Could not read the file") }
        host.showStatus()
    }

    private fun delete(fs: List<Mtp.Item>) {
        if (fs.isEmpty()) return
        Dialogs.confirm(host.act, "Delete permanently", "Delete " + fs.size + " item(s) permanently? This cannot be undone.", "Delete") {
            work { var ok = true; for (f in fs) ok = Mtp.delete(dev!!, f.handle) && ok; sel.clear(); ok }
        }
    }

    private fun pasteInto(parent: Int) {
        val m = dev ?: return
        val md = MtpClip.data
        if (md != null) { MtpClip.toPhone(host, m, storage, parent) { load() }; return }
        val cl = Clip.read(c) ?: run { c.toast("Nothing to paste"); return }
        val n = cl.files.size
        val job = Job("Copying " + n + (if (n == 1) " item" else " items") + " to " + (Mtp.find(c, loc.dev)?.let { Mtp.label(it) } ?: "Phone"))
        val st = storage
        Jobs.start(job, { j ->
            j.total = cl.files.sumOf { Ops.sizeOf(it) }
            for (f in cl.files) { if (j.cancelled) break; if (!Mtp.upload(m, st, parent, f, j)) j.failed++ else if (cl.cut) f.deleteRecursively() }
        }) { if (cl.cut && job.failed == 0) Clip.clear(c); load() }
        host.showStatus()
    }

    private fun paste() = pasteInto(loc.parent)

    override fun onKey(code: Int, ctrl: Boolean, shift: Boolean): Boolean {
        val g = Tiles.isGrid(c)
        when {
            ctrl && code == android.view.KeyEvent.KEYCODE_A -> selectAll()
            ctrl && code == android.view.KeyEvent.KEYCODE_C -> doCopy(selItems(), false)
            ctrl && code == android.view.KeyEvent.KEYCODE_X -> doCopy(selItems(), true)
            ctrl && code == android.view.KeyEvent.KEYCODE_V -> if (canPaste()) paste()
            code == android.view.KeyEvent.KEYCODE_FORWARD_DEL -> delete(selItems())
            code == android.view.KeyEvent.KEYCODE_F2 && sel.size == 1 -> rename(selItems()[0])
            (code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) && sel.size == 1 -> selItems().firstOrNull()?.let { open(it) }
            code == android.view.KeyEvent.KEYCODE_ESCAPE && sel.isNotEmpty() -> { sel.clear(); changed() }
            !ctrl && arrow(code, shift, g) -> {}
            else -> return false
        }
        return true
    }

    private fun arrow(code: Int, shift: Boolean, g: Boolean): Boolean {
        val K = android.view.KeyEvent::class.java
        val cols = if (g) maxOf(1, grid.numColumns) else 1
        val t: AbsListView = if (g) grid else list
        val rows = maxOf(1, t.height / maxOf(1, (t.getChildAt(0)?.height ?: c.dp(46))))
        val h = if (T.rtl) -1 else 1
        val d = when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> cols
            android.view.KeyEvent.KEYCODE_DPAD_UP -> -cols
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (g) h else return true
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (g) -h else return true
            android.view.KeyEvent.KEYCODE_PAGE_DOWN -> rows * cols
            android.view.KeyEvent.KEYCODE_PAGE_UP -> -rows * cols
            android.view.KeyEvent.KEYCODE_MOVE_HOME -> -items.size
            android.view.KeyEvent.KEYCODE_MOVE_END -> items.size
            else -> return false
        }
        if (items.isEmpty()) return true
        val from = if (sel.isNotEmpty()) items.indexOfFirst { it.handle in sel }.takeIf { it >= 0 } ?: -1 else -1
        val n = (if (from < 0) (if (d > 0) 0 else items.size - 1) else (from + d)).coerceIn(0, items.size - 1)
        if (items[n].storage) { anchor = n; return true }
        if (shift && anchor in items.indices) { sel.clear(); for (k in minOf(anchor, n)..maxOf(anchor, n)) if (!items[k].storage) sel.add(items[k].handle) }
        else { sel.clear(); sel.add(items[n].handle); anchor = n }
        changed()
        if (n < t.firstVisiblePosition || n > t.lastVisiblePosition) t.smoothScrollToPosition(n)
        return true
    }

    private fun say(s: String) { items = emptyList(); adapter.notifyDataSetChanged(); empty.text = T.t(s); empty.visibility = View.VISIBLE; buildBar() }

    override fun refresh() { if (dev == null) start() else load() }

    private fun start() {
        val have = Mtp.device(loc.dev)
        if (have != null) { dev = have; load(); return }
        val d = Mtp.find(c, loc.dev)
        if (d == null) { say("The phone is not connected"); return }
        say("Connecting…")
        Mtp.connect(c, d) { m, err ->
            if (m == null) say(err.ifEmpty { "Could not connect" }) else { dev = m; load() }
        }
    }

    private fun load() {
        val m = dev ?: return
        empty.text = T.t("Loading…"); empty.visibility = View.VISIBLE
        Thread {
            var st = storage
            var l: List<Mtp.Item> = emptyList()
            try {
                if (st == -1) {
                    val all = Mtp.storages(m)
                    if (all.size == 1) st = all[0].handle else l = all
                }
                if (st != -1) l = Mtp.children(m, st, loc.parent)
            } catch (_: Exception) {}
            val sorted = l.sortedWith(compareBy<Mtp.Item>({ !it.dir }, { it.name.lowercase() }))
            c.mainExec.execute {
                storage = st; items = sorted; sel.clear(); adapter.notifyDataSetChanged()
                empty.text = T.t("This folder is empty"); empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                buildBar()
            }
        }.start()
    }

    private fun open(it: Mtp.Item) {
        if (it.storage) { host.go(Loc.Mtp(loc.dev, it.handle, -1, it.name)); return }
        if (it.dir) { host.go(Loc.Mtp(loc.dev, storage, it.handle, it.name)); return }
        val m = dev ?: return
        val dir = File(Fs.ext(), ".deskphone").also { d -> d.mkdirs() }
        val job = Job("Preparing " + it.name)
        Jobs.start(job, { j -> j.total = it.size; File(dir, it.name).delete(); if (!Mtp.download(m, storage, it, dir, j)) j.failed++ }) {
            val f = File(dir, it.name)
            if (f.exists()) host.openFile(f) else c.toast("Could not read the file")
        }
        host.showStatus()
    }

    private fun work(then: () -> Boolean) {
        Thread { val ok = try { then() } catch (_: Exception) { false }; c.mainExec.execute { if (!ok) c.toast("The phone did not accept that"); load() } }.start()
    }

    private fun save(fs: List<Mtp.Item>, dest: File) {
        val m = dev ?: return
        val job = Job("Copying " + fs.size + " items to " + dest.name)
        Jobs.start(job, { j -> j.total = fs.sumOf { Mtp.sizeOf(m, storage, it) }; for (it in fs) { if (j.cancelled) break; if (!Mtp.download(m, storage, it, dest, j)) j.failed++ } }) { host.refreshAll() }
        host.showStatus()
    }
}
