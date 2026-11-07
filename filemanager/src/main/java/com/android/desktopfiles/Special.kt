package com.android.desktopfiles

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import java.io.File

/** Which files belong to Documents / Pictures / Music / Videos. */
object Kinds {
    val docs = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "ods", "odp", "csv", "rtf", "epub", "md")
    val music = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "wma", "amr")

    fun match(kind: String, f: File): Boolean {
        val e = f.extension.lowercase()
        return when (kind) {
            "pictures" -> Fs.isImage(f)
            "videos" -> Fs.isVideo(f)
            "music" -> e in music
            "documents" -> e in docs
            else -> true
        }
    }

    fun title(kind: String) = when (kind) { "pictures" -> "Pictures"; "videos" -> "Videos"; "music" -> "Music"; else -> "Documents" }
}

/** Documents / Pictures / Music / Videos: every folder on the device that holds such files. Opening one shows only that kind. */
class MediaPage(private val host: Host, private val kind: String) : Page {
    private val c: Context = host.act
    private val p = Ui.pal(c)
    private class Group(val dir: File, var count: Int)
    private var groups: List<Group> = emptyList()
    private var loading = true
    private val root = LinearLayout(c)
    private val list = ListView(c)
    private val empty = Ui.text(c, "", 14f, p.sub)
    private var lastX = 0; private var lastY = 0
    override val view: View get() = root

    private val adapter = object : BaseAdapter() {
        override fun getCount() = groups.size
        override fun getItem(i: Int): Any = groups[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
            val g = groups[i]
            val r = LinearLayout(c); r.gravity = Gravity.CENTER_VERTICAL
            r.layoutParams = AbsListView.LayoutParams(MATCH, c.dp(56)); r.setPaddingRelative(c.dp(12), 0, c.dp(12), 0)
            r.background = Ui.ripple(c, p, null, c.dpf(8f)); r.isClickable = true
            val iv = ImageView(c); iv.setImageDrawable(Glyph(Glyph.Kind.FOLDER))
            r.addView(iv, LinearLayout.LayoutParams(c.dp(34), c.dp(34)).also { it.marginEnd = c.dp(12) })
            val box = LinearLayout(c); box.orientation = LinearLayout.VERTICAL
            val nm = Ui.text(c, g.dir.name, 14f, p.text, true, false); nm.maxLines = 1
            val pt = Ui.text(c, g.dir.parent ?: "", 11f, p.sub); pt.maxLines = 1; pt.ellipsize = android.text.TextUtils.TruncateAt.START
            box.addView(nm); box.addView(pt)
            r.addView(box, LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(Ui.text(c, g.count.toString() + (if (g.count == 1) " item" else " items"), 12f, p.sub))
            r.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { lastX = e.rawX.toInt(); lastY = e.rawY.toInt() }; false }
            r.setOnClickListener { host.go(Loc.Dir(g.dir.absolutePath, kind)) }
            val menu = {
                Pop(c).showAt(root, lastX, lastY, listOf(
                    MenuItem("Open") { host.go(Loc.Dir(g.dir.absolutePath, kind)) },
                    MenuItem("Open all files") { host.go(Loc.Dir(g.dir.absolutePath)) },
                    MenuItem("Open in new tab") { host.newTab(Loc.Dir(g.dir.absolutePath, kind)) },
                    MenuItem("Open in new window") { host.newWindow(Loc.Dir(g.dir.absolutePath)) }))
            }
            r.setOnLongClickListener { menu(); true }
            r.setOnContextClickListener { menu(); true }
            return r
        }
    }

    init {
        root.orientation = LinearLayout.VERTICAL
        val title = Ui.text(c, Kinds.title(kind), 22f, p.text, true); title.setPaddingRelative(c.dp(16), c.dp(14), c.dp(16), c.dp(2))
        root.addView(title)
        val sub = Ui.text(c, "Folders on this device that contain " + Kinds.title(kind).lowercase(), 12f, p.sub); sub.setPaddingRelative(c.dp(16), 0, c.dp(16), c.dp(8))
        root.addView(sub)
        list.divider = null; list.adapter = adapter; list.selector = android.graphics.drawable.ColorDrawable(0)
        val fr = FrameLayout(c)
        fr.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        empty.gravity = Gravity.CENTER; fr.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(fr, LinearLayout.LayoutParams(MATCH, 0, 1f))
        refresh()
    }

    override fun refresh() {
        loading = true; empty.text = "Loading…"; empty.visibility = View.VISIBLE
        Thread {
            val map = LinkedHashMap<String, Group>()
            try {
                val uri: Uri; var sel: String? = null
                when (kind) {
                    "pictures" -> uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "videos" -> uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "music" -> { uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; sel = "is_music != 0" }
                    else -> { uri = MediaStore.Files.getContentUri("external"); sel = Kinds.docs.joinToString(" OR ") { "_data LIKE '%." + it + "'" } }
                }
                c.contentResolver.query(uri, arrayOf("_data"), sel, null, null)?.use {
                    while (it.moveToNext()) {
                        val path = it.getString(0) ?: continue
                        val f = File(path); val d = f.parentFile ?: continue
                        if (path.contains("/.") || path.contains("/Android/data/") || path.contains("/Android/obb/")) continue
                        map.getOrPut(d.absolutePath) { Group(d, 0) }.count++
                    }
                }
            } catch (_: Exception) {}
            val out = map.values.filter { it.dir.exists() }.sortedWith(compareByDescending<Group> { it.count }.thenBy { it.dir.name.lowercase() })
            c.mainExecutor.execute {
                groups = out; loading = false; adapter.notifyDataSetChanged()
                empty.text = T.t("No " + Kinds.title(kind).lowercase() + " found")
                empty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }
}

/** Apps: every installed app, including system apps, with a menu (open, App info, share / copy APK, uninstall). */
class AppsPage(private val host: Host) : Page {
    private val c: Context = host.act
    private val p = Ui.pal(c)
    private class App(val pkg: String, val label: String, val info: ApplicationInfo, val version: String, val system: Boolean)
    private var all: List<App> = emptyList()
    private var shown: List<App> = emptyList()
    private val icons = HashMap<String, Drawable>()
    private val root = LinearLayout(c)
    private val list = ListView(c)
    private val search = EditText(c)
    private val count = Ui.text(c, "", 12f, p.sub)
    private val sysBtn = Ui.text(c, "", 13f, p.accent, true)
    private var lastX = 0; private var lastY = 0
    override val view: View get() = root

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int): Any = shown[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
            val a = shown[i]
            val r = LinearLayout(c); r.gravity = Gravity.CENTER_VERTICAL
            r.layoutParams = AbsListView.LayoutParams(MATCH, c.dp(54)); r.setPaddingRelative(c.dp(12), 0, c.dp(12), 0)
            r.background = Ui.ripple(c, p, null, c.dpf(8f)); r.isClickable = true
            val iv = ImageView(c)
            iv.setImageDrawable(icons.getOrPut(a.pkg) { try { a.info.loadIcon(c.packageManager) } catch (_: Exception) { Glyph(Glyph.Kind.ANDROID) } })
            r.addView(iv, LinearLayout.LayoutParams(c.dp(36), c.dp(36)).also { it.marginEnd = c.dp(12) })
            val box = LinearLayout(c); box.orientation = LinearLayout.VERTICAL
            val nm = Ui.text(c, a.label, 14f, p.text, true, false); nm.maxLines = 1
            val pk = Ui.text(c, a.pkg + (if (a.system) "  ·  system" else ""), 11f, p.sub); pk.maxLines = 1
            box.addView(nm); box.addView(pk)
            r.addView(box, LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(Ui.text(c, a.version, 12f, p.sub))
            r.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { lastX = e.rawX.toInt(); lastY = e.rawY.toInt() }; false }
            r.setOnClickListener { launch(a) }
            r.setOnLongClickListener { menu(a); true }
            r.setOnContextClickListener { menu(a); true }
            return r
        }
    }

    init {
        root.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(c); head.gravity = Gravity.CENTER_VERTICAL; head.setPaddingRelative(c.dp(16), c.dp(14), c.dp(10), c.dp(6))
        head.addView(Ui.text(c, "Apps", 22f, p.text, true), LinearLayout.LayoutParams(WRAP, WRAP))
        head.addView(count, LinearLayout.LayoutParams(0, WRAP, 1f).also { it.marginStart = c.dp(10) })
        sysBtn.setPaddingRelative(c.dp(12), c.dp(8), c.dp(12), c.dp(8)); sysBtn.isClickable = true; sysBtn.background = Ui.ripple(c, p, null, c.dpf(8f))
        sysBtn.setOnClickListener { Store.setBool(c, "appsSystem", !Store.bool(c, "appsSystem", true)); filter() }
        head.addView(sysBtn)
        root.addView(head)
        // an app was installed or removed while another window was in front: look again when this window gets the focus back
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            val l = android.view.ViewTreeObserver.OnWindowFocusChangeListener { f -> if (f) refresh() }
            override fun onViewAttachedToWindow(v: View) { v.viewTreeObserver.addOnWindowFocusChangeListener(l) }
            override fun onViewDetachedFromWindow(v: View) { v.viewTreeObserver.removeOnWindowFocusChangeListener(l) }
        })
        search.hint = T.t("Filter apps"); search.setSingleLine(); search.textSize = 13f; search.setTextColor(p.text); search.setHintTextColor(p.sub)
        search.background = Ui.rounded(p.card, c.dpf(18f), p.line, 1); search.setPaddingRelative(c.dp(14), 0, c.dp(14), 0)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { filter() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        })
        root.addView(search, LinearLayout.LayoutParams(MATCH, c.dp(38)).also { it.marginStart = c.dp(16); it.marginEnd = c.dp(16); it.bottomMargin = c.dp(6) })
        list.divider = null; list.adapter = adapter; list.selector = android.graphics.drawable.ColorDrawable(0)
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        refresh()
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        val sys = Store.bool(c, "appsSystem", true)
        shown = all.filter { (sys || !it.system) && (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)) }
        sysBtn.text = T.t(if (sys) "Hide system apps" else "Show system apps")
        count.text = shown.size.toString()
        adapter.notifyDataSetChanged()
    }

    override fun refresh() {
        Thread {
            val pm = c.packageManager
            val l = try {
                pm.getInstalledApplications(0).map {
                    val v = try { pm.getPackageInfo(it.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
                    App(it.packageName, try { it.loadLabel(pm).toString() } catch (_: Exception) { it.packageName }, it, v, it.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                }.sortedBy { it.label.lowercase() }
            } catch (_: Exception) { emptyList() }
            c.mainExecutor.execute { all = l; filter() }
        }.start()
    }

    private fun launch(a: App) {
        val i = c.packageManager.getLaunchIntentForPackage(a.pkg)
        if (i == null) { c.toast("This app has no screen to open"); return }
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { c.toast("Could not open it") }
    }

    private fun apkCopy(a: App, dest: File, then: (File) -> Unit) {
        val src = File(a.info.sourceDir)
        val job = Job("Copying APK of " + a.label)
        dest.mkdirs()
        val out = Fs.unique(dest, a.label.replace('/', '_') + ".apk")
        Jobs.start(job, { j -> j.total = src.length(); src.inputStream().use { i -> out.outputStream().use { o ->
            val buf = ByteArray(256 * 1024)
            while (true) { if (j.cancelled) break; val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); j.done += n }
        } } }) { then(out) }
    }

    private fun menu(a: App) {
        Pop(c).showAt(root, lastX, lastY, listOf(
            MenuItem("Open") { launch(a) },
            MenuItem("App info") {
                try { c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            },
            null,
            MenuItem("Share APK") { apkCopy(a, File(Fs.ext(), ".deskshare")) { Fs.share(c, listOf(it)) } },
            MenuItem("Copy APK to Downloads") { apkCopy(a, File(Fs.ext(), "Download")) { c.toast("Saved: " + it.name) } },
            null,
            MenuItem("Uninstall", danger = true) {
                try { c.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { c.toast("Could not uninstall") }
            }))
    }
}
