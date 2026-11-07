package com.android.desktopfiles

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/** Saved cloud / WebDAV accounts (Nextcloud, ownCloud, many NAS boxes). The password is kept in this app's private storage. */
object DavAccts {
    private fun e(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun d(s: String) = URLDecoder.decode(s, "UTF-8")

    fun all(c: Context): List<Dav.Acct> = Store.list(c, "dav").mapNotNull { line ->
        val f = line.split("&")
        if (f.size < 6) null else try { Dav.Acct(d(f[0]), d(f[1]), d(f[2]), d(f[3]), d(f[4]), f[5] == "1") } catch (_: Exception) { null }
    }

    fun byId(c: Context, id: String): Dav.Acct? = all(c).firstOrNull { it.id == id }

    private fun save(c: Context, l: List<Dav.Acct>) =
        Store.setList(c, "dav", l.map { listOf(e(it.id), e(it.name), e(it.url), e(it.user), e(it.pass), if (it.trustAll) "1" else "0").joinToString("&") })

    fun add(c: Context, a: Dav.Acct) = save(c, all(c) + a)
    fun remove(c: Context, id: String) = save(c, all(c).filter { it.id != id })

    /** The form for a new account. [done] is called with the saved account after the connection was tested. */
    fun addDialog(act: android.app.Activity, done: (Dav.Acct) -> Unit) {
        val p = Ui.pal(act)
        fun field(hint: String, pw: Boolean = false): EditText {
            val et = EditText(act); et.hint = T.t(hint); et.setSingleLine()
            et.inputType = if (pw) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                           else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            return et
        }
        val name = field("Name (for example My Nextcloud)"); name.inputType = android.text.InputType.TYPE_CLASS_TEXT
        val url = field("Address (https://server/remote.php/dav/files/user/)")
        val user = field("User name"); user.inputType = android.text.InputType.TYPE_CLASS_TEXT
        val pass = field("Password", true)
        val trust = CheckBox(act); trust.text = T.t("Trust this server's certificate (self-signed)")
        val msg = Ui.text(act, "", 12f, 0xFFE53935.toInt())
        val box = LinearLayout(act); box.orientation = LinearLayout.VERTICAL; box.setPaddingRelative(act.dp(20), act.dp(8), act.dp(20), 0)
        for (v in listOf(name, url, user, pass, trust, msg)) box.addView(v)
        val dlg = AlertDialog.Builder(act).setTitle(T.t("Add a cloud account (WebDAV)")).setView(ScrollViewOf(act, box))
            .setPositiveButton(T.t("Add"), null).setNegativeButton(T.t("Cancel"), null).create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { b ->
            var u = url.text.toString().trim()
            if (u.isEmpty()) { msg.text = T.t("Type the address"); return@setOnClickListener }
            if (!u.contains("://")) u = "https://$u"
            val a = Dav.Acct(System.currentTimeMillis().toString(), name.text.toString().trim().ifEmpty { u.substringAfter("://").substringBefore('/') },
                u, user.text.toString().trim(), pass.text.toString(), trust.isChecked)
            b.isEnabled = false; msg.setTextColor(p.sub); msg.text = T.t("Connecting…")
            Thread {
                val err = try { Dav.test(a); "" } catch (ex: Exception) { ex.message ?: "Could not connect" }
                act.runOnUiThread {
                    if (err.isEmpty()) { add(act, a); dlg.dismiss(); done(a) }
                    else { b.isEnabled = true; msg.setTextColor(0xFFE53935.toInt()); msg.text = T.t(err) }
                }
            }.start()
        }
    }

    private fun ScrollViewOf(c: Context, v: View): View = android.widget.ScrollView(c).also { it.addView(v) }
}

/** A cloud (WebDAV) folder: browse, open, copy to this device, send files up (Paste), new folder, rename, delete. */
class DavPage(private val host: Host, private val loc: Loc.Dav) : Page {
    private val c: Context = host.act
    private val p = Ui.pal(c)
    private val acct = DavAccts.byId(c, loc.acct)
    private var items: List<Dav.Entry> = emptyList()
    private var lastX = 0; private var lastY = 0

    private val root = LinearLayout(c)
    private val cmd = LinearLayout(c)
    private val list = ListView(c)
    private val empty = Ui.text(c, "", 14f, p.sub)
    override val view: View get() = root

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int): Any = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
            val item = items[i]
            val r = LinearLayout(c); r.gravity = Gravity.CENTER_VERTICAL
            r.layoutParams = AbsListView.LayoutParams(MATCH, c.dp(46)); r.setPaddingRelative(c.dp(12), 0, c.dp(12), 0)
            r.background = Ui.ripple(c, p, null, c.dpf(8f)); r.isClickable = true
            val iv = ImageView(c)
            iv.setImageDrawable(if (item.dir) Glyph(Glyph.Kind.FOLDER) else Thumbs.icon(File(item.name)))
            r.addView(iv, LinearLayout.LayoutParams(c.dp(30), c.dp(30)).also { l -> l.marginEnd = c.dp(10) })
            val nm = Ui.text(c, item.name, 14f, p.text, false, false); nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            r.addView(nm, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (!item.dir) r.addView(Ui.text(c, Fs.size(c, item.size), 12f, p.sub), LinearLayout.LayoutParams(c.dp(80), WRAP))
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
        empty.gravity = Gravity.CENTER; fr.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(fr, LinearLayout.LayoutParams(MATCH, 0, 1f))
        buildBar(); load()
    }

    private fun btn(label: String, on: Boolean = true, click: () -> Unit) {
        val t = Ui.text(c, label, 13f, p.text); t.setPaddingRelative(c.dp(12), 0, c.dp(12), 0); t.gravity = Gravity.CENTER; t.alpha = if (on) 1f else 0.45f
        if (on) { t.isClickable = true; t.background = Ui.ripple(c, p, null, c.dpf(8f)); t.setOnClickListener { click() } }
        cmd.addView(t, LinearLayout.LayoutParams(WRAP, c.dp(36)))
    }

    private fun buildBar() {
        cmd.removeAllViews()
        val ok = acct != null
        btn("Paste", ok && Clip.has(c)) { paste() }
        btn("New folder", ok) { Dialogs.ask(host.act, "New folder", "New folder", "Create") { n -> work { Dav.mkcol(acct!!, join(loc.path, n)) } } }
        btn("Refresh", ok) { load() }
    }

    private fun join(dir: String, name: String) = dir.trimEnd('/') + "/" + name

    override fun refresh() = load()

    private fun load() {
        val a = acct ?: run { items = emptyList(); adapter.notifyDataSetChanged(); empty.text = T.t("This account was removed"); empty.visibility = View.VISIBLE; return }
        empty.text = T.t("Loading…"); empty.visibility = View.VISIBLE
        Thread {
            var err = ""
            var l: List<Dav.Entry> = emptyList()
            try { l = Dav.list(a, loc.path) } catch (e: Exception) { err = e.message ?: "Could not connect" }
            val sorted = l.sortedWith(compareBy<Dav.Entry>({ !it.dir }, { it.name.lowercase() }))
            c.mainExecutor.execute {
                items = sorted; adapter.notifyDataSetChanged()
                empty.text = if (err.isNotEmpty()) T.t(err) else T.t("This folder is empty")
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                buildBar()
            }
        }.start()
    }

    private fun open(e: Dav.Entry) {
        val a = acct ?: return
        if (e.dir) { host.go(Loc.Dav(loc.acct, e.path, e.name)); return }
        val dir = File(Fs.ext(), ".deskcloud").also { d -> d.mkdirs() }
        val f = File(dir, e.name)
        val job = Job("Preparing " + e.name)
        Jobs.start(job, { j ->
            j.total = e.size; j.detail = e.name
            try { f.outputStream().use { o -> Dav.get(a, e.path, o, { n -> j.done += n }, { j.cancelled }) } }
            catch (_: Exception) { j.failed++; f.delete() }
        }) { if (f.exists() && job.failed == 0 && !job.cancelled) host.openFile(f) else c.toast("Could not read the file") }
        host.showStatus()
    }

    private fun work(then: () -> Unit) {
        Thread {
            var err = ""
            try { then() } catch (e: Exception) { err = e.message ?: "Failed" }
            c.mainExecutor.execute { if (err.isNotEmpty()) c.toast(err); load() }
        }.start()
    }

    private fun downloadTree(a: Dav.Acct, e: Dav.Entry, dest: File, j: Job): Boolean {
        if (j.cancelled) return false
        j.detail = e.name
        val target = Fs.unique(dest, e.name)
        if (e.dir) {
            target.mkdirs()
            var ok = true
            for (k in Dav.list(a, e.path)) ok = downloadTree(a, k, target, j) && ok
            return ok
        }
        return try { target.outputStream().use { o -> Dav.get(a, e.path, o, { n -> j.done += n }, { j.cancelled }) }; if (e.mtime > 0) target.setLastModified(e.mtime); true }
        catch (_: Exception) { target.delete(); false }
    }

    private fun sizeOf(a: Dav.Acct, e: Dav.Entry): Long = if (!e.dir) e.size else try { Dav.list(a, e.path).sumOf { sizeOf(a, it) } } catch (_: Exception) { 0L }

    private fun save(e: Dav.Entry, dest: File) {
        val a = acct ?: return
        val job = Job("Copying " + e.name + " to " + dest.name)
        Jobs.start(job, { j -> j.total = sizeOf(a, e); if (!downloadTree(a, e, dest, j)) j.failed++ }) { host.refreshAll() }
        host.showStatus()
    }

    private fun uploadTree(a: Dav.Acct, f: File, dirPath: String, j: Job): Boolean {
        if (j.cancelled) return false
        j.detail = f.name
        val path = join(dirPath, f.name)
        if (f.isDirectory) {
            try { Dav.mkcol(a, path) } catch (_: Exception) { }
            var ok = true
            for (k in f.listFiles() ?: emptyArray()) ok = uploadTree(a, k, path, j) && ok
            return ok
        }
        return try { Dav.put(a, path, f, { n -> j.done += n }, { j.cancelled }); true } catch (_: Exception) { false }
    }

    private fun paste() {
        val a = acct ?: return
        val cl = Clip.read(c) ?: run { c.toast("Nothing to paste"); return }
        val n = cl.files.size
        val job = Job("Copying " + n + (if (n == 1) " item" else " items") + " to " + a.name)
        val dir = loc.path
        Jobs.start(job, { j ->
            j.total = cl.files.sumOf { Ops.sizeOf(it) }
            for (f in cl.files) { if (j.cancelled) break; if (!uploadTree(a, f, dir, j)) j.failed++ else if (cl.cut) f.deleteRecursively() }
        }) { if (cl.cut && job.failed == 0) Clip.clear(c); load() }
        host.showStatus()
    }

    private fun menu(e: Dav.Entry) {
        val a = acct ?: return
        val l = ArrayList<MenuItem?>()
        l += MenuItem("Open") { open(e) }
        l += null
        l += MenuItem("Copy to Downloads") { save(e, File(Fs.ext(), "Download")) }
        l += MenuItem("Copy to Desktop") { save(e, Fs.desktop()) }
        host.otherPaneDir(0)?.let { d -> l += MenuItem("Copy to other pane") { save(e, d) } }
        l += null
        l += MenuItem("Rename") {
            Dialogs.ask(host.act, "Rename", e.name, "Rename") { n -> work { Dav.move(a, e.path, join(e.path.trimEnd('/').substringBeforeLast('/', ""), n)) } }
        }
        l += MenuItem("Delete", danger = true) {
            Dialogs.confirm(host.act, "Delete permanently", "Delete 1 item permanently? This cannot be undone.", "Delete") { work { Dav.delete(a, e.path) } }
        }
        Pop(c).showAt(root, lastX, lastY, l)
    }
}
