package com.android.desktopfiles

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Screens of "send to a nearby device": the devices row, the accept dialog, the transfer list and the picker pages. */
object NearUi {
    private var front: Activity? = null

    fun install(act: Activity) {
        front = act
        Nearby.askAccept = { from, n, size, reply ->
            val a = front
            if (a == null || a.isFinishing) reply(false, false) else {
                val box = LinearLayout(a); box.orientation = LinearLayout.VERTICAL; box.setPaddingRelative(a.dp(24), a.dp(8), a.dp(24), 0)
                val always = CheckBox(a); always.text = T.t("Always accept from this device")
                box.addView(always)
                var answered = false
                val b = android.app.AlertDialog.Builder(a).setTitle(T.t("Incoming files"))
                    .setMessage(from + "  →  " + n + " " + T.t("item(s)") + "  (" + Fs.size(a, size) + ")")
                    .setView(box).setCancelable(false)
                    .setPositiveButton(T.t("Accept")) { _, _ -> if (!answered) { answered = true; reply(true, always.isChecked) } }
                    .setNegativeButton(T.t("Decline")) { _, _ -> if (!answered) { answered = true; reply(false, false) } }
                b.showDir()
            }
        }
    }

    /** A small menu with the connected devices; [pick] gets the chosen one. */
    fun pick(act: Activity, anchor: View, pick: (Nearby.Peer) -> Unit) {
        val ps = Nearby.peers()
        if (ps.isEmpty()) { act.toast("No device is connected"); return }
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val items = ArrayList<MenuItem?>()
        items += MenuItem("Send to", enabled = false); items += null
        for (pr in ps) items += MenuItem(pr.name) { pick(pr) }
        Pop(act).showAt(anchor, loc[0] + anchor.width / 2, loc[1] + anchor.height, items)
    }

    fun sendFiles(act: Activity, anchor: View, files: List<File>) {
        if (files.isEmpty()) return
        pick(act, anchor) { peer -> Nearby.send(act, peer, "Sending " + files.size + " item(s)") { Nearby.itemsOf(files) }; (act as? MainActivity)?.showStatus() }
    }

    fun sendContacts(act: Activity, anchor: View) = Nearby.withPermission(act, android.Manifest.permission.READ_CONTACTS) {
        pick(act, anchor) { peer -> Nearby.send(act, peer, "Sending contacts") { j ->
            val f = File(act.cacheDir, "Contacts.vcf"); val n = NearExport.contacts(act, f); j.detail = "$n contacts"
            if (n == 0) emptyList() else listOf(Nearby.Item("contact", "Contacts.vcf", f.length()) { f.inputStream() })
        } }
    }

    fun sendMessages(act: Activity, anchor: View) = Nearby.withPermission(act, android.Manifest.permission.READ_SMS) {
        pick(act, anchor) { peer -> Nearby.send(act, peer, "Sending messages") { j ->
            val f = File(act.cacheDir, "Messages.xml"); val n = NearExport.messages(act, f); j.detail = "$n messages"
            if (n == 0) emptyList() else listOf(Nearby.Item("sms", "Messages.xml", f.length()) { f.inputStream() })
        } }
    }

    /** Android/data and Android/obb are closed to ordinary file access: the person allows the folder in Android's own picker. */
    fun sendTree(act: Activity, anchor: View, sub: String) {
        val m = act as? MainActivity ?: return
        val init = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Android/$sub")
        act.toast("Choose the folder inside Android/$sub and allow it")
        m.pickTree(init) { uri -> pick(act, anchor) { peer -> Nearby.send(act, peer, "Sending folder") { Nearby.itemsOfTree(act, uri) }; m.showStatus() } }
    }

    fun emoji(c: Context, e: String, color: Int): Drawable = object : Drawable() {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        override fun draw(cv: Canvas) {
            val b = bounds; cv.drawRoundRect(RectF(b), b.width() * .22f, b.width() * .22f, bg)
            tp.textSize = b.width() * .55f; cv.drawText(e, b.exactCenterX(), b.exactCenterY() + tp.textSize * .35f, tp)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = c.dp(36)
        override fun getIntrinsicHeight() = c.dp(36)
    }

    fun kindGlyph(kind: String): Glyph = Glyph(when (kind) { "photo" -> Glyph.Kind.PICS; "video" -> Glyph.Kind.VIDEOS; "music" -> Glyph.Kind.MUSIC; "app" -> Glyph.Kind.APPS; "folder" -> Glyph.Kind.FOLDER; else -> Glyph.Kind.FILE })

    fun dayLabel(ms: Long): String {
        val now = Calendar.getInstance(); val t = Calendar.getInstance(); t.timeInMillis = ms
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (same(now, t)) return T.t("Today")
        now.add(Calendar.DAY_OF_YEAR, -1)
        if (same(now, t)) return T.t("Yesterday")
        return SimpleDateFormat("d MMM yyyy", if (T.rtl) Locale("ur") else Locale.getDefault()).format(java.util.Date(ms))
    }
}

/** The round device icons above the toolbar: tap one to see what is being sent to / received from it. The ring shows the progress. */
class PeerBar(private val c: Context, private val onTap: (Nearby.Peer) -> Unit) : LinearLayout(c) {
    private val p = Ui.pal(c)
    private val h = Handler(Looper.getMainLooper())
    private val bubbles = ArrayList<Bubble>()
    private val tick = object : Runnable { override fun run() { bubbles.forEach { it.invalidate() }; if (isAttachedToWindow && bubbles.isNotEmpty()) h.postDelayed(this, 300) } }

    private inner class Bubble(val peer: Nearby.Peer) : View(c) {
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) - c.dpf(3f)
            pt.style = Paint.Style.FILL; pt.color = 0xFFFFFFFF.toInt(); cv.drawCircle(cx, cy, r, pt)
            pt.style = Paint.Style.STROKE; pt.strokeWidth = c.dpf(2f); pt.color = 0xFF2B2F36.toInt(); pt.strokeCap = Paint.Cap.ROUND
            if (peer.type == "pc") { val w = r * .78f; val hh = r * .5f; cv.drawRoundRect(cx - w, cy - hh, cx + w, cy + hh, c.dpf(5f), c.dpf(5f), pt) }
            else { val w = r * .36f; val hh = r * .62f; cv.drawRoundRect(cx - w, cy - hh, cx + w, cy + hh, c.dpf(5f), c.dpf(5f), pt) }
            val f = Nearby.fraction(peer.id)
            pt.strokeWidth = c.dpf(3f)
            if (f != null) {
                pt.color = p.line; cv.drawCircle(cx, cy, r + c.dpf(1f), pt)
                pt.color = p.accent; cv.drawArc(RectF(cx - r - c.dpf(1f), cy - r - c.dpf(1f), cx + r + c.dpf(1f), cy + r + c.dpf(1f)), -90f, 360f * maxOf(0.03f, f), false, pt)
            } else { pt.color = p.accent; cv.drawCircle(cx, cy, r + c.dpf(1f), pt) }
        }
    }

    init { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPaddingRelative(c.dp(12), c.dp(2), c.dp(12), c.dp(2)) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); h.post(tick) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); h.removeCallbacks(tick) }

    fun rebuild() {
        removeAllViews(); bubbles.clear()
        val ps = Nearby.peers()
        visibility = if (ps.isEmpty()) GONE else VISIBLE
        for (pr in ps) {
            val col = LinearLayout(c); col.orientation = VERTICAL; col.gravity = Gravity.CENTER_HORIZONTAL
            col.isClickable = true; col.background = Ui.ripple(c, p, null, c.dpf(10f)); col.setOnClickListener { onTap(pr) }
            val b = Bubble(pr); bubbles.add(b)
            col.addView(b, LayoutParams(c.dp(52), c.dp(52)))
            val t = Ui.text(c, pr.name, 12f, p.text, false, false); t.maxLines = 1; t.ellipsize = android.text.TextUtils.TruncateAt.END; t.maxWidth = c.dp(96)
            col.addView(t)
            addView(col, LayoutParams(WRAP, WRAP).also { it.marginStart = c.dp(10) })
        }
        h.removeCallbacks(tick); h.post(tick)
    }
}

/** The list of transfers with one device (sent and received, live), or the history of everything received. */
class XferView(private val c: Context, private val peerId: String?, private val history: Boolean, private val open: (File) -> Unit) : LinearLayout(c) {
    private val p = Ui.pal(c)
    private val h = Handler(Looper.getMainLooper())
    private var sig = ""
    private class Live(val x: Nearby.Xfer, val bar: Prog, val info: TextView)
    private var live = ArrayList<Live>()
    private val l: () -> Unit = { update() }
    private val tick = object : Runnable { override fun run() { update(); if (isAttachedToWindow) h.postDelayed(this, 400) } }

    init { orientation = VERTICAL; setPaddingRelative(c.dp(12), c.dp(8), c.dp(12), c.dp(12)) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Nearby.listen(l); h.post(tick) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); Nearby.unlisten(l); h.removeCallbacks(tick) }

    private fun stateText(x: Nearby.Xfer): Pair<String, Int> = when (x.state) {
        0 -> T.t("Waiting for the other device") to 0xFFD9534F.toInt()
        1 -> Fs.size(c, x.done) + " / " + Fs.size(c, x.size) to p.sub
        2 -> Fs.size(c, x.size) + "  ·  " + T.t("Done") to p.sub
        4 -> T.t("Declined") to 0xFFD9534F.toInt()
        else -> T.t("Failed") to 0xFFD9534F.toInt()
    }

    fun update() {
        if (history) { if (sig != "h" + Nearby.history(c).size) rebuildHistory(); return }
        val list = Nearby.xfers.filter { it.peerId == peerId }.sortedByDescending { it.id }.take(150)
        val s = list.joinToString(",") { it.id.toString() }
        if (s != sig) { rebuildLive(list); sig = s }
        for (lv in live) {
            lv.bar.fraction = if (lv.x.size > 0) lv.x.done.toFloat() / lv.x.size else if (lv.x.state == 2) 1f else 0f
            val (t, col) = stateText(lv.x); lv.info.text = t; lv.info.setTextColor(col)
        }
    }

    private fun title(text: String) { addView(Ui.text(c, text, 12f, p.sub, false, false).also { it.setPaddingRelative(0, c.dp(8), 0, c.dp(4)) }) }

    private fun row(kind: String, name: String, f: File?, click: (() -> Unit)?): LinearLayout {
        val r = LinearLayout(c); r.gravity = Gravity.CENTER_VERTICAL; r.setPaddingRelative(c.dp(10), c.dp(8), c.dp(10), c.dp(8))
        r.background = Ui.ripple(c, p, Ui.rounded(p.card, c.dpf(8f)), c.dpf(8f))
        val iv = ImageView(c)
        if (f != null && f.exists()) Thumbs.bind(iv, f, c.dp(80)) else iv.setImageDrawable(NearUi.kindGlyph(kind))
        r.addView(iv, LayoutParams(c.dp(40), c.dp(40)).also { it.marginEnd = c.dp(10) })
        val box = LinearLayout(c); box.orientation = VERTICAL; box.tag = "box"
        val nm = Ui.text(c, name, 13f, p.text, false, false); nm.maxLines = 2; nm.ellipsize = android.text.TextUtils.TruncateAt.END
        box.addView(nm)
        r.addView(box, LayoutParams(0, WRAP, 1f))
        if (click != null) { r.isClickable = true; r.setOnClickListener { click() } }
        return r
    }

    private fun rebuildLive(list: List<Nearby.Xfer>) {
        removeAllViews(); live = ArrayList()
        if (list.isEmpty()) { addView(Ui.text(c, "Nothing has been sent or received yet", 13f, p.sub)); return }
        var last = ""
        for (x in list) {
            val head = (if (x.sent) T.t("Sent to") else T.t("Received from")) + " " + x.peerName
            if (head != last) { title(head); last = head }
            val f = if (x.path.isNotEmpty()) File(x.path) else null
            val r = row(x.kind, x.name, f, if (f != null && x.state == 2) ({ open(f) }) else null)
            val box = r.findViewWithTag<LinearLayout>("box")
            val info = Ui.text(c, "", 11f, p.sub, false, false); box.addView(info)
            val bar = Prog(c, p); box.addView(bar, LayoutParams(MATCH, c.dp(4)).also { it.topMargin = c.dp(4) })
            live.add(Live(x, bar, info))
            addView(r, LayoutParams(MATCH, WRAP).also { it.bottomMargin = c.dp(6) })
        }
    }

    private fun rebuildHistory() {
        removeAllViews()
        val hist = Nearby.history(c); sig = "h" + hist.size
        val head = LinearLayout(c); head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Ui.text(c, "Received", 18f, p.text, true), LayoutParams(0, WRAP, 1f))
        if (hist.isNotEmpty()) {
            val clr = Ui.text(c, "Clear", 13f, p.accent, true); clr.setPaddingRelative(c.dp(12), c.dp(8), c.dp(12), c.dp(8)); clr.isClickable = true
            clr.background = Ui.ripple(c, p, null, c.dpf(8f)); clr.setOnClickListener { Nearby.clearHistory(c); update() }
            head.addView(clr)
        }
        addView(head)
        if (hist.isEmpty()) { addView(Ui.text(c, "Nothing has been received yet", 13f, p.sub).also { it.setPaddingRelative(0, c.dp(10), 0, 0) }); return }
        var last = ""
        for (e in hist.take(300)) {
            val day = NearUi.dayLabel(e.time)
            if (day != last) { title(day); last = day }
            val f = File(e.path)
            val r = row(e.kind, e.name, f, { if (f.exists()) open(f) else c.toast("The file is no longer there") })
            val box = r.findViewWithTag<LinearLayout>("box")
            box.addView(Ui.text(c, T.t("From") + " " + e.peer + "  ·  " + Fs.size(c, e.size), 11f, p.sub, false, false))
            addView(r, LayoutParams(MATCH, WRAP).also { it.bottomMargin = c.dp(6) })
        }
    }
}

/** Page wrapper: transfers with one device, or the history. */
class XferPage(private val host: Host, peerId: String?, history: Boolean) : Page {
    private val c: Context = host.act
    private val p = Ui.pal(c)
    private val wrap = FrameLayout(c)
    private val v = XferView(c, peerId, history) { f -> if (f.isDirectory) host.go(Loc.Dir(f.absolutePath)) else host.openFile(f) }
    override val view: View get() = wrap
    init {
        val sc = ScrollView(c); sc.addView(v, MATCH, WRAP)
        wrap.addView(sc, FrameLayout.LayoutParams(MATCH, MATCH))
        DeskScroll.attachScroll(wrap, p, sc)
        v.update()
    }
    override fun refresh() = v.update()
}

/** Picker pages: Pictures / Videos / Music grouped by day with a circle per day, and Apps. Select, then Send. */
class SendPage(private val host: Host, private val kind: String) : Page {
    private val c: Context = host.act
    private val p = Ui.pal(c)
    private val root = LinearLayout(c)
    private val list = ListView(c)
    private val info = Ui.text(c, "", 12f, p.sub)
    private val sendBtn = Ui.text(c, "", 13f, 0xFFFFFFFF.toInt(), true)
    private val empty = Ui.text(c, "Loading…", 14f, p.sub)
    private val sel = LinkedHashSet<String>()      // file path, or package name for apps
    private class Entry(val key: String, val file: File?, val label: String, val time: Long, val ai: android.content.pm.ApplicationInfo? = null, val version: String = "")
    private sealed class Row { class Head(val label: String, val keys: List<String>) : Row(); class Cells(val e: List<Entry>) : Row() }
    private var entries: List<Entry> = emptyList()
    private var rows: List<Row> = emptyList()
    private var cols = 4
    override val view: View get() = root

    private class Dot(c: Context, private val pal: Pal) : View(c) {
        var on = false
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) - context.dpf(2f)
            if (on) {
                pt.style = Paint.Style.FILL; pt.color = pal.accent; cv.drawCircle(cx, cy, r, pt)
                pt.style = Paint.Style.STROKE; pt.strokeWidth = context.dpf(2f); pt.color = 0xFFFFFFFF.toInt(); pt.strokeCap = Paint.Cap.ROUND
                val t = android.graphics.Path(); t.moveTo(cx - r * .45f, cy); t.lineTo(cx - r * .1f, cy + r * .35f); t.lineTo(cx + r * .5f, cy - r * .3f); cv.drawPath(t, pt)
            } else { pt.style = Paint.Style.STROKE; pt.strokeWidth = context.dpf(1.5f); pt.color = 0xFF8A9099.toInt(); cv.drawCircle(cx, cy, r, pt) }
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(i: Int): Any = rows[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(i: Int) = if (rows[i] is Row.Head) 0 else 1
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View = when (val r = rows[i]) {
            is Row.Head -> head(r)
            is Row.Cells -> if (kind == "apps") appRow(r.e[0]) else cells(r)
        }
    }

    init {
        root.orientation = LinearLayout.VERTICAL
        val bar = LinearLayout(c); bar.gravity = Gravity.CENTER_VERTICAL; bar.setPaddingRelative(c.dp(16), c.dp(12), c.dp(12), c.dp(6))
        bar.addView(Ui.text(c, when (kind) { "photos" -> "Pictures"; "videos" -> "Videos"; "music" -> "Music"; else -> "Apps" }, 22f, p.text, true))
        bar.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f).also { it.marginStart = c.dp(10); it.marginEnd = c.dp(10) })
        val all = Ui.text(c, "Select all", 13f, p.accent, true); all.setPaddingRelative(c.dp(10), c.dp(8), c.dp(10), c.dp(8)); all.isClickable = true
        all.background = Ui.ripple(c, p, null, c.dpf(8f))
        all.setOnClickListener { if (sel.size == entries.size) sel.clear() else { sel.clear(); entries.forEach { sel.add(it.key) } }; changed() }
        bar.addView(all)
        sendBtn.gravity = Gravity.CENTER; sendBtn.setPaddingRelative(c.dp(16), 0, c.dp(16), 0); sendBtn.isClickable = true
        sendBtn.setOnClickListener { go(it) }
        bar.addView(sendBtn, LinearLayout.LayoutParams(WRAP, c.dp(36)).also { it.marginStart = c.dp(6) })
        root.addView(bar)
        val fr = FrameLayout(c)
        list.adapter = adapter; list.divider = null; list.setSelector(android.R.color.transparent); list.isVerticalScrollBarEnabled = false
        fr.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        DeskScroll.attach(fr, p, list)
        empty.gravity = Gravity.CENTER; fr.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(fr, LinearLayout.LayoutParams(MATCH, 0, 1f))
        changed()
        refresh()
    }

    private fun changed() {
        val n = sel.size
        info.text = if (n > 0) n.toString() + " " + T.t("selected") else ""
        sendBtn.text = T.t("Send") + if (n > 0) " ($n)" else ""
        sendBtn.background = Ui.rounded(if (n > 0) p.accent.let { if (p.dark) p.accentSoft else it } else 0xFF9AA0A6.toInt(), c.dpf(18f))
        sendBtn.alpha = if (n > 0) 1f else 0.6f
        adapter.notifyDataSetChanged()
    }

    private fun head(r: Row.Head): View {
        val h = LinearLayout(c); h.gravity = Gravity.CENTER_VERTICAL; h.setPaddingRelative(c.dp(16), c.dp(14), c.dp(16), c.dp(6))
        h.layoutParams = AbsListView.LayoutParams(MATCH, WRAP)
        val dot = Dot(c, p); dot.on = r.keys.isNotEmpty() && r.keys.all { it in sel }
        h.addView(Ui.text(c, r.label, 15f, p.text, true, false), LinearLayout.LayoutParams(0, WRAP, 1f))
        h.addView(dot, LinearLayout.LayoutParams(c.dp(24), c.dp(24)))
        h.isClickable = true
        h.setOnClickListener { if (r.keys.all { it in sel }) sel.removeAll(r.keys.toSet()) else sel.addAll(r.keys); changed() }
        return h
    }

    private fun cells(r: Row.Cells): View {
        val line = LinearLayout(c); line.layoutParams = AbsListView.LayoutParams(MATCH, WRAP); line.setPaddingRelative(c.dp(10), 0, c.dp(10), c.dp(6))
        for (e in r.e) {
            val cell = FrameLayout(c); cell.setPadding(c.dp(3), c.dp(3), c.dp(3), c.dp(3)); cell.isClickable = true
            val col = LinearLayout(c); col.orientation = LinearLayout.VERTICAL
            val iv = ImageView(c); iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.background = Ui.rounded(p.card, c.dpf(6f))
            e.file?.let { Thumbs.bind(iv, it, c.dp(240)) }
            col.addView(iv, LinearLayout.LayoutParams(MATCH, c.dp(92)))
            val nm = Ui.text(c, e.label, 11f, p.text, false, false); nm.maxLines = 2; nm.ellipsize = android.text.TextUtils.TruncateAt.END
            col.addView(nm)
            cell.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
            val on = e.key in sel
            if (on) { val ov = View(c); ov.background = Ui.rounded(0x332B7BFF, c.dpf(6f)); cell.addView(ov, FrameLayout.LayoutParams(MATCH, c.dp(92 + 0))) }
            val dot = Dot(c, p); dot.on = on
            cell.addView(dot, FrameLayout.LayoutParams(c.dp(24), c.dp(24), Gravity.TOP or Gravity.START).also { it.setMargins(c.dp(6), c.dp(6), 0, 0) })
            cell.setOnClickListener { if (!sel.add(e.key)) sel.remove(e.key); changed() }
            line.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        for (k in r.e.size until cols) line.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        return line
    }

    private fun appRow(e: Entry): View {
        val r = LinearLayout(c); r.gravity = Gravity.CENTER_VERTICAL
        r.layoutParams = AbsListView.LayoutParams(MATCH, c.dp(54)); r.setPaddingRelative(c.dp(16), 0, c.dp(16), 0)
        r.background = Ui.ripple(c, p, null, c.dpf(8f)); r.isClickable = true
        val dot = Dot(c, p); dot.on = e.key in sel
        r.addView(dot, LinearLayout.LayoutParams(c.dp(24), c.dp(24)).also { it.marginEnd = c.dp(12) })
        val iv = ImageView(c); iv.setImageDrawable(try { e.ai!!.loadIcon(c.packageManager) } catch (_: Exception) { Glyph(Glyph.Kind.ANDROID) })
        r.addView(iv, LinearLayout.LayoutParams(c.dp(36), c.dp(36)).also { it.marginEnd = c.dp(12) })
        val box = LinearLayout(c); box.orientation = LinearLayout.VERTICAL
        box.addView(Ui.text(c, e.label, 14f, p.text, true, false).also { it.maxLines = 1 })
        box.addView(Ui.text(c, e.key, 11f, p.sub, false, false).also { it.maxLines = 1 })
        r.addView(box, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(Ui.text(c, e.version, 12f, p.sub, false, false))
        r.setOnClickListener { if (!sel.add(e.key)) sel.remove(e.key); changed() }
        return r
    }

    override fun refresh() {
        cols = maxOf(3, host.contentWidthDp() / 120)
        Thread {
            val out = ArrayList<Entry>()
            try {
                if (kind == "apps") {
                    val pm = c.packageManager
                    for (ai in pm.getInstalledApplications(0)) {
                        if (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0) continue
                        val ver = try { pm.getPackageInfo(ai.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
                        out.add(Entry(ai.packageName, null, ai.loadLabel(pm).toString(), 0, ai, ver))
                    }
                    out.sortBy { it.label.lowercase() }
                } else {
                    val uri = when (kind) { "photos" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI; "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI; else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
                    val selq = if (kind == "music") "is_music != 0" else null
                    c.contentResolver.query(uri, arrayOf("_data", "date_modified"), selq, null, "date_modified DESC")?.use {
                        while (it.moveToNext()) {
                            val path = it.getString(0) ?: continue
                            if (path.contains("/.") || path.contains("/Android/data/") || path.contains("/Android/obb/")) continue
                            val f = File(path); if (!f.isFile) continue
                            out.add(Entry(path, f, f.name, it.getLong(1) * 1000))
                        }
                    }
                }
            } catch (_: Exception) {}
            val rs = ArrayList<Row>()
            if (kind == "apps") out.forEach { rs.add(Row.Cells(listOf(it))) }
            else {
                var i = 0
                while (i < out.size) {
                    val label = NearUi.dayLabel(out[i].time)
                    var j = i; while (j < out.size && NearUi.dayLabel(out[j].time) == label) j++
                    val grp = out.subList(i, j)
                    rs.add(Row.Head(label, grp.map { it.key }))
                    grp.chunked(cols).forEach { rs.add(Row.Cells(it)) }
                    i = j
                }
            }
            c.mainExecutor.execute {
                entries = out; rows = rs; sel.retainAll(out.map { it.key }.toSet())
                empty.text = T.t("Nothing found"); empty.visibility = if (out.isEmpty()) View.VISIBLE else View.GONE
                changed()
            }
        }.start()
    }

    private fun go(anchor: View) {
        if (sel.isEmpty()) { c.toast("Select what to send"); return }
        val keys = sel.toList()
        NearUi.pick(host.act, anchor) { peer ->
            if (kind == "apps") Nearby.send(c, peer, "Sending " + keys.size + " app(s)") { j -> packApps(keys, j) }
            else Nearby.send(c, peer, "Sending " + keys.size + " item(s)") { Nearby.itemsOf(keys.map { File(it) }) }
            sel.clear(); changed(); host.showStatus()
        }
    }

    /** An app made of one APK goes as it is; one made of several parts is packed into one installable .apk+ first. */
    private fun packApps(pkgs: List<String>, j: Job): List<Nearby.Item> {
        val dir = File(c.cacheDir, "nearapps"); dir.deleteRecursively(); dir.mkdirs()
        val out = ArrayList<Nearby.Item>()
        for (pkg in pkgs) {
            val e = entries.firstOrNull { it.key == pkg } ?: continue
            val ai = e.ai ?: continue
            if (j.cancelled) break
            j.detail = e.label
            val nm = e.label.replace('/', '_') + (if (e.version.isNotEmpty()) "_" + e.version else "")
            if (ai.splitSourceDirs.isNullOrEmpty()) { val f = File(ai.sourceDir); out.add(Nearby.Item("app", "$nm.apk", f.length()) { f.inputStream() }) }
            else { val f = File(dir, "$nm.apk+"); val sub = Job(e.label); Apks.pack(c, ai, e.label, e.version, f, sub); out.add(Nearby.Item("app", "$nm.apk+", f.length()) { f.inputStream() }) }
        }
        return out
    }
}
