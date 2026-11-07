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
    private var lastX = 0; private var lastY = 0

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
            val icon = if (item.dir) Glyph(if (item.storage) Glyph.Kind.PHONE else Glyph.Kind.FOLDER) else Thumbs.icon(File(item.name))
            val r: LinearLayout
            if (Tiles.isGrid(c)) r = Tiles.tile(c, p, icon, item.name)
            else {
                r = LinearLayout(c); r.gravity = Gravity.CENTER_VERTICAL
                r.layoutParams = AbsListView.LayoutParams(MATCH, c.dp(46)); r.setPaddingRelative(c.dp(12), 0, c.dp(12), 0)
                r.background = Ui.ripple(c, p, null, c.dpf(8f)); r.isClickable = true
                val iv = ImageView(c); iv.setImageDrawable(icon)
                r.addView(iv, LinearLayout.LayoutParams(c.dp(30), c.dp(30)).also { l -> l.marginEnd = c.dp(10) })
                val nm = Ui.text(c, item.name, 14f, p.text, false, false); nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                r.addView(nm, LinearLayout.LayoutParams(0, WRAP, 1f))
                if ((!item.dir) || (item.storage && item.size > 0)) r.addView(Ui.text(c, Fs.size(c, item.size), 12f, p.sub), LinearLayout.LayoutParams(c.dp(80), WRAP))
            }
            r.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { lastX = e.rawX.toInt(); lastY = e.rawY.toInt() }; false }
            r.setOnClickListener { open(item) }
            r.setOnLongClickListener { menu(item); true }
            r.setOnContextClickListener { menu(item); true }
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
        empty.gravity = Gravity.CENTER; fr.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(fr, LinearLayout.LayoutParams(MATCH, 0, 1f))
        buildBar(); start(); applyView()
    }

    private fun applyView() {
        val g = Tiles.isGrid(c)
        list.visibility = if (g) View.GONE else View.VISIBLE
        grid.visibility = if (g) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun inStorage() = storage != -1

    private fun btn(label: String, on: Boolean = true, click: (View) -> Unit) {
        val t = Ui.text(c, label, 13f, p.text); t.setPaddingRelative(c.dp(12), 0, c.dp(12), 0); t.gravity = Gravity.CENTER; t.alpha = if (on) 1f else 0.45f
        if (on) { t.isClickable = true; t.background = Ui.ripple(c, p, null, c.dpf(8f)); t.setOnClickListener { click(t) } }
        cmd.addView(t, LinearLayout.LayoutParams(WRAP, c.dp(36)))
    }

    private fun buildBar() {
        cmd.removeAllViews()
        val ready = dev != null && inStorage()
        btn("Paste", ready && Clip.has(c)) { paste() }
        btn("New folder", ready) { Dialogs.ask(host.act, "New folder", "New folder", "Create") { n -> work { Mtp.newFolder(dev!!, storage, loc.parent, n) != 0 } } }
        btn("Refresh", dev != null) { load() }
        btn("View ▾") { v -> Pop(c).showBelow(v, Tiles.viewItems(c) { applyView() }) }
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
            c.mainExecutor.execute {
                storage = st; items = sorted; adapter.notifyDataSetChanged()
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
        Thread { val ok = try { then() } catch (_: Exception) { false }; c.mainExecutor.execute { if (!ok) c.toast("The phone did not accept that"); load() } }.start()
    }

    private fun save(it: Mtp.Item, dest: File) {
        val m = dev ?: return
        val job = Job("Copying " + it.name + " to " + dest.name)
        Jobs.start(job, { j -> j.total = Mtp.sizeOf(m, storage, it); if (!Mtp.download(m, storage, it, dest, j)) j.failed++ }) { host.refreshAll() }
        host.showStatus()
    }

    private fun paste() {
        val m = dev ?: return
        val cl = Clip.read(c) ?: run { c.toast("Nothing to paste"); return }
        val n = cl.files.size
        val job = Job("Copying " + n + (if (n == 1) " item" else " items") + " to " + (Mtp.find(c, loc.dev)?.let { Mtp.label(it) } ?: "Phone"))
        val st = storage; val par = loc.parent
        Jobs.start(job, { j ->
            j.total = cl.files.sumOf { Ops.sizeOf(it) }
            for (f in cl.files) { if (j.cancelled) break; if (!Mtp.upload(m, st, par, f, j)) j.failed++ else if (cl.cut) f.deleteRecursively() }
        }) { if (cl.cut && job.failed == 0) Clip.clear(c); load() }
        host.showStatus()
    }

    private fun menu(it: Mtp.Item) {
        val l = ArrayList<MenuItem?>()
        l += MenuItem("Open") { open(it) }
        if (!it.storage) {
            l += null
            l += MenuItem("Copy to Downloads") { save(it, File(Fs.ext(), "Download")) }
            l += MenuItem("Copy to Desktop") { save(it, Fs.desktop()) }
            host.otherPaneDir(0)?.let { d -> l += MenuItem("Copy to other pane") { save(it, d) } }
            l += null
            l += MenuItem("Delete", danger = true) {
                Dialogs.confirm(host.act, "Delete permanently", "Delete 1 item permanently? This cannot be undone.", "Delete") { work { Mtp.delete(dev!!, it.handle) } }
            }
        }
        Pop(c).showAt(root, lastX, lastY, l)
    }
}
