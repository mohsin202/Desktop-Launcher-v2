package com.android.desktopfiles

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.window.OnBackInvokedDispatcher
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** A page that says something is coming later (Apps, Documents… grouped). */
class SoonPage(c: Context, text: String) : Page {
    private val t = Ui.text(c, text, 15f, Ui.pal(c).sub).also { it.gravity = Gravity.CENTER }
    override val view: View get() = t
    override fun refresh() {}
}

/**
 * Desktop Files window: tabs, toolbar (back / forward / up / refresh, address bar, search, Status Center, settings), sidebar and the page.
 * Every launch opens its own window (documentLaunchMode = always).
 */
open class MainActivity : Activity(), Host {
    override val act: Activity get() = this
    private val tabs = ArrayList<Tab>()
    private var cur = 0
    private var page: Page? = null
    private var page2: Page? = null
    private var details: DetailsPane? = null
    private var activePane = 0
    private var splitX = 0
    private lateinit var p: Pal
    private var content: FrameLayout? = null
    private var sidebar: View? = null
    private var focusSide = false                       // keys go to the sidebar (true) or to the page (false)
    private var sideCursor = -1
    private val sideRows = ArrayList<Pair<View, () -> Unit>>()
    private var sideOpen = false
    private var wide = true
    private var statusBtn: View? = null
    private var rwBtn: TextView? = null
    private var addrBox: FrameLayout? = null
    private var searchBox: EditText? = null
    private var navBack: View? = null
    private var navFwd: View? = null
    private var tabStrip: LinearLayout? = null
    private var editing = false
    private var hadAccess = false
    private val seen = HashSet<Job>()

    /** Where a window of this kind starts (the Trash icon starts in the Trash). */
    open fun startLoc(): Loc? = null

    private val jobListener: () -> Unit = {
        val fresh = Jobs.list.filter { !it.finished && seen.add(it) }
        if (fresh.isNotEmpty() && hasWindowFocus() && !StatusPanel.showing()) statusBtn?.post { showStatus() }
    }

    private var treeCb: ((Uri) -> Unit)? = null

    /** Opens Android's folder picker (Google Drive, OneDrive, Dropbox and other apps appear in it) and hands back the chosen folder. */
    fun pickTree(cb: (Uri) -> Unit) {
        treeCb = cb
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), 7731)
        } catch (_: Exception) { treeCb = null; toast("Could not open the picker") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7731) return
        val cb = treeCb; treeCb = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || cb == null) return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        cb(uri)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setDecorFitsSystemWindows(false)
        val path = intent.getStringExtra("path")
        val first: Loc = when {
            startLoc() != null -> startLoc()!!
            intent.getBooleanExtra("trash", false) -> Loc.Trash
            path != null -> File(path).let { f -> if (f.isDirectory) Loc.Dir(f.absolutePath) else if (f.parentFile != null && f.exists()) Loc.Dir(f.parentFile!!.absolutePath) else Loc.Home }
            else -> Loc.Home
        }
        tabs.add(Tab(first))
        Jobs.appCtx = applicationContext
        Tags.init(applicationContext)
        Jobs.listen(jobListener)
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7) } catch (_: Exception) {}
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }
        hadAccess = Environment.isExternalStorageManager()
        buildUi()
        openedFromSystemDrive()
        openedWithApk()
    }

    /** Started from the system's "Open with" list for a connected drive or phone (the MTP host): go straight to the connected phone. */
    private fun openedFromSystemDrive() {
        val i = intent ?: return
        val t = i.type ?: ""
        val act = i.action ?: ""
        val fromDrive = t.startsWith("vnd.android.document/") || act == "android.provider.action.BROWSE" || i.data?.authority == "com.android.mtp.documents"
        if (!fromDrive) return
        val phones = Mtp.list(this)
        if (phones.size == 1) openPhone(phones[0]) else if (phones.isEmpty()) toast("No phone found")
    }

    /** Another app opened an APK or a split-APK bundle with this one ("Open with"): copy it aside and show the install window. */
    private fun openedWithApk() {
        val i = intent ?: return
        val u = i.data ?: return
        if (i.action != Intent.ACTION_VIEW) return
        val mime = i.type ?: ""
        if (mime.startsWith("vnd.android.document/") || u.authority == "com.android.mtp.documents") return
        Thread {
            try {
                val dir = File(cacheDir, "incoming"); dir.deleteRecursively(); dir.mkdirs()
                val file: File = if (u.scheme == "file") File(u.path ?: return@Thread).also { if (!Apks.installable(it)) return@Thread } else {
                    var name = u.lastPathSegment?.substringAfterLast('/') ?: "app.apk"
                    contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cu ->
                        if (cu.moveToFirst()) cu.getString(0)?.let { name = it }
                    }
                    if (!Apks.installable(File(name)) && mime == "application/vnd.android.package-archive") name += ".apk"
                    if (!Apks.installable(File(name))) return@Thread      // a video, text, zip…: not for the installer
                    val out = File(dir, name.replace('/', '_'))
                    contentResolver.openInputStream(u)?.use { inp -> out.outputStream().use { o -> inp.copyTo(o) } } ?: return@Thread
                    out
                }
                runOnUiThread { Apks.install(this, file) }
            } catch (_: Throwable) { runOnUiThread { toast("Could not open this file") } }
        }.start()
    }

    override fun onDestroy() { super.onDestroy(); Jobs.unlisten(jobListener); page?.destroy(); page2?.destroy() }

    override fun onResume() {
        super.onResume()
        val now = Environment.isExternalStorageManager()
        if (now != hadAccess) { hadAccess = now; buildUi() } else page?.refresh()
    }

    override fun onConfigurationChanged(n: Configuration) { cfgW = n.screenWidthDp; super.onConfigurationChanged(n); buildUi() }

    private fun onBack() {
        when {
            sideOpen -> setSide(false)
            tabs[cur].back.isNotEmpty() -> back()
            tabs.size > 1 -> closeTab(cur)
            else -> finish()
        }
    }

    // ---------- UI ----------
    /** Window width in dp as the system reports it with each configuration change (the view itself still has the old size right after a rotation). */
    private var cfgW = 0
    private fun widthDp(): Int {
        if (cfgW <= 0) cfgW = resources.configuration.screenWidthDp
        return if (cfgW > 0) cfgW else (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
    }

    private fun titleOf(l: Loc): String = when (l) {
        is Loc.Home -> "Home"
        is Loc.Tag -> Tags.byId(l.id)?.name ?: "Tag"
        is Loc.Dav -> l.title.ifEmpty { DavAccts.byId(this, l.acct)?.name ?: "Cloud" }
        is Loc.Mtp -> l.title.ifEmpty { Mtp.find(this, l.dev)?.let { Mtp.label(it) } ?: "Phone" }
        is Loc.Dir -> if (l.only.isNotEmpty()) File(l.path).name + " (" + l.only + ")" else if (l.path == Fs.ext().absolutePath) "Internal storage" else if (l.path == "/") "Root" else File(l.path).name
        is Loc.Trash -> "Trash"
        is Loc.Search -> "Search: " + l.q
        is Loc.Special -> if (l.kind.startsWith("tag:")) (Tags.byId(l.kind.substring(4))?.name ?: "Tag") else l.kind.replaceFirstChar { it.uppercase() }
    }

    private fun buildUi() {
        T.init(Store.str(this, "lang", "system"))
        p = Ui.pal(this)
        page?.destroy(); page = null; page2?.destroy(); page2 = null
        window.decorView.setBackgroundColor(p.bg)
        window.insetsController?.setSystemBarsAppearance(
            if (p.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        wide = widthDp() >= 720

        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(p.bg)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = ins.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)); WindowInsets.CONSUMED
        }
        root.layoutDirection = if (T.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        window.decorView.layoutDirection = root.layoutDirection
        setContentView(root)

        if (!Environment.isExternalStorageManager()) { permissionScreen(root); return }

        // top area: tabs + toolbar
        val top = LinearLayout(this); top.orientation = LinearLayout.VERTICAL
        top.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(p.bar, p.barEnd))
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        top.addView(buildTabs(), LinearLayout.LayoutParams(MATCH, dp(40)))
        top.addView(buildToolbar(), LinearLayout.LayoutParams(MATCH, dp(50)))

        val body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val cnt = FrameLayout(this); content = cnt
        val side = buildSidebar(); sidebar = side
        body.addView(cnt, FrameLayout.LayoutParams(MATCH, MATCH).also { if (wide) it.marginStart = dp(220) })
        body.addView(side, FrameLayout.LayoutParams(dp(220), MATCH))
        side.visibility = if (wide || sideOpen) View.VISIBLE else View.GONE
        showTab()
    }

    private fun permissionScreen(root: LinearLayout) {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.gravity = Gravity.CENTER
        box.setPaddingRelative(dp(32), dp(32), dp(32), dp(32))
        val iv = ImageView(this); iv.setImageDrawable(Glyph(Glyph.Kind.FOLDER))
        box.addView(iv, LinearLayout.LayoutParams(dp(72), dp(72)))
        box.addView(Ui.text(this, "Desktop Files", 22f, p.text, true).also { it.setPaddingRelative(0, dp(12), 0, dp(6)) })
        val t = Ui.text(this, "Allow access to all files so Desktop Files can show and manage your folders.", 14f, p.sub); t.gravity = Gravity.CENTER
        box.addView(t)
        val b = Ui.text(this, "Allow access", 15f, 0xFFFFFFFF.toInt(), true); b.gravity = Gravity.CENTER
        b.background = Ui.rounded(p.accent.let { if (p.dark) p.accentSoft else it }, dpf(22f))
        b.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
        box.addView(b, LinearLayout.LayoutParams(dp(180), dp(44)).also { it.topMargin = dp(20) })
        root.addView(box, LinearLayout.LayoutParams(MATCH, MATCH))
    }

    private fun buildTabs(): View {
        val sv = HorizontalScrollView(this); sv.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL; row.setPaddingRelative(dp(8), dp(6), dp(8), 0)
        tabStrip = row
        sv.addView(row, FrameLayout.LayoutParams(WRAP, MATCH))
        fillTabs()
        return sv
    }

    private fun fillTabs() {
        val row = tabStrip ?: return
        row.removeAllViews()
        for ((i, t) in tabs.withIndex()) {
            val chip = LinearLayout(this); chip.gravity = Gravity.CENTER_VERTICAL
            chip.setPaddingRelative(dp(12), 0, dp(4), 0)
            chip.background = if (i == cur) Ui.rounded(p.bg, dpf(10f)).also { it.cornerRadii = floatArrayOf(dpf(10f), dpf(10f), dpf(10f), dpf(10f), 0f, 0f, 0f, 0f) }
                              else Ui.ripple(this, p, null, dpf(10f))
            chip.isClickable = true
            chip.setOnClickListener { if (i != cur) { cur = i; showTab() } }
            val ic = ImageView(this)
            ic.setImageDrawable(when (t.loc) { is Loc.Home -> Glyph(Glyph.Kind.HOME); is Loc.Trash -> Glyph(Glyph.Kind.BIN); else -> Glyph(Glyph.Kind.FOLDER) })
            chip.addView(ic, LinearLayout.LayoutParams(dp(18), dp(18)).also { it.marginEnd = dp(8) })
            val tx = Ui.text(this, titleOf(t.loc), 13f, p.text, i == cur, t.loc !is Loc.Dir || t.loc.let { it is Loc.Dir && (it.only.isNotEmpty().not() && (it.path == Fs.ext().absolutePath || it.path == "/")) }); tx.maxLines = 1; tx.ellipsize = android.text.TextUtils.TruncateAt.END
            tx.maxWidth = dp(150)
            chip.addView(tx)
            val x = Ui.glyphButton(this, p, "×", 15f, 28, 28) { closeTab(i) }
            chip.addView(x, LinearLayout.LayoutParams(dp(28), dp(28)).also { it.marginStart = dp(4) })
            row.addView(chip, LinearLayout.LayoutParams(WRAP, dp(34)).also { it.marginEnd = dp(2) })
        }
        row.addView(Ui.glyphButton(this, p, "+", 20f, 34, 34) { newTab(Loc.Home) })
    }

    private fun buildToolbar(): View {
        val bar = LinearLayout(this); bar.gravity = Gravity.CENTER_VERTICAL; bar.setPaddingRelative(dp(6), 0, dp(6), 0)
        if (!wide) bar.addView(Ui.iconButton(this, p, "menu") { setSide(!sideOpen) })
        navBack = Ui.iconButton(this, p, if (T.rtl) "fwd" else "back") { back() }; bar.addView(navBack)
        navFwd = Ui.iconButton(this, p, if (T.rtl) "back" else "fwd") { forward() }; bar.addView(navFwd)
        bar.addView(Ui.iconButton(this, p, "up") { up() })
        bar.addView(Ui.iconButton(this, p, "refresh") { refreshAll() })
        val ab = FrameLayout(this); addrBox = ab
        ab.background = Ui.rounded(p.card, dpf(18f), p.line, 1)
        bar.addView(ab, LinearLayout.LayoutParams(0, dp(36), 1f).also { it.marginStart = dp(6); it.marginEnd = dp(6) })
        if (widthDp() >= 820) {
            val s = EditText(this); searchBox = s
            s.hint = T.t("Search"); s.setSingleLine(); s.textSize = 13f; s.setTextColor(p.text); s.setHintTextColor(p.sub)
            s.background = Ui.rounded(p.card, dpf(18f), p.line, 1); s.setPaddingRelative(dp(14), 0, dp(14), 0)
            s.imeOptions = EditorInfo.IME_ACTION_SEARCH
            s.setOnEditorActionListener { v, _, _ -> s.removeCallbacks(searchRun); liveSearch(v.text.toString()); true }
            // the Enter key of a real keyboard does not always reach the action listener
            s.setOnKeyListener { v, k, e ->
                if ((k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) && e.action == KeyEvent.ACTION_DOWN) { v.removeCallbacks(searchRun); liveSearch((v as EditText).text.toString()); true } else false
            }
            // search while typing: every letter narrows the results after a short pause
            s.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { if (!searchSilent) { s.removeCallbacks(searchRun); s.postDelayed(searchRun, 250) } }
            })
            bar.addView(s, LinearLayout.LayoutParams(dp(190), dp(36)))
        } else {
            searchBox = null
            bar.addView(Ui.glyphButton(this, p, "⌕", 20f) { Dialogs.ask(this, "Search", "", "Search") { search(it) } })
        }
        if (widthDp() >= 900) {
            fun toggle(glyph: String, key: String) {
                val on = Store.bool(this, key, false)
                val b = Ui.glyphButton(this, p, glyph, 17f) { Store.setBool(this, key, !Store.bool(this, key, false)); showTab() }
                if (on) b.background = Ui.ripple(this, p, Ui.rounded(p.accentSoft, dpf(8f)), dpf(8f))
                bar.addView(b)
            }
            toggle("◫", "dual"); toggle("ⓘ", "pane")
        }
        val rw = Ui.text(this, "RW", 12f, 0xFFFFFFFF.toInt(), true); rwBtn = rw
        rw.gravity = Gravity.CENTER; rw.visibility = View.GONE; rw.isClickable = true
        bar.addView(rw, LinearLayout.LayoutParams(dp(40), dp(28)).also { it.marginEnd = dp(4) })
        val sb = StatusBtn(this, p) { if (StatusPanel.showing()) StatusPanel.hide() else showStatus() }
        statusBtn = sb
        bar.addView(sb, LinearLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(Ui.glyphButton(this, p, "⚙", 18f) { v -> Pop(this).showBelow(v, settingsItems()) })
        return bar
    }

    private fun settingsItems(): List<MenuItem?> {
        val th = Store.str(this, "theme", "system")
        fun t(l: String, k: String) = MenuItem(l, checked = th == k) { Store.setStr(this, "theme", k); buildUi() }
        return listOf(
            MenuItem("Theme", sub = listOf(t("System", "system"), t("Light", "light"), t("Dark", "dark"))),
            MenuItem("Single click to open", checked = Store.bool(this, "openSingle", true)) { Store.setBool(this, "openSingle", !Store.bool(this, "openSingle", true)) },
            MenuItem("Show hidden files", checked = Store.bool(this, "showHidden", false)) { Store.setBool(this, "showHidden", !Store.bool(this, "showHidden", false)); refreshAll() },
            null,
            MenuItem("Details pane (Alt+P)", checked = Store.bool(this, "pane", false)) { Store.setBool(this, "pane", !Store.bool(this, "pane", false)); showTab() },
            MenuItem("Dual pane", checked = Store.bool(this, "dual", false)) { Store.setBool(this, "dual", !Store.bool(this, "dual", false)); showTab() },
            MenuItem("Language", sub = listOf(
                MenuItem("System", checked = Store.str(this, "lang", "system") == "system") { Store.setStr(this, "lang", "system"); recreate() },
                MenuItem("English", checked = Store.str(this, "lang", "system") == "en") { Store.setStr(this, "lang", "en"); recreate() },
                MenuItem("اردو", checked = Store.str(this, "lang", "system") == "ur") { Store.setStr(this, "lang", "ur"); recreate() })),
            MenuItem("New window") { newWindow(Loc.Home) },
            MenuItem("Desktop Files 1.0", enabled = false))
    }

    private var searchSilent = false
    private val searchRun = Runnable { liveSearch(searchBox?.text?.toString() ?: "") }

    /** Searches for [q] in the folder being looked at. A search that is already showing is replaced (no history entry per letter); an empty box returns to the folder. */
    private fun liveSearch(q: String) {
        val query = q.trim()
        val t = tabs[cur]; val l = t.loc
        val box = searchBox
        val hadFocus = box?.hasFocus() == true
        if (query.isEmpty()) {
            if (l is Loc.Search) { t.loc = Loc.Dir(l.base); showTab() }
        } else {
            val base = when (l) { is Loc.Dir -> l.path; is Loc.Search -> l.base; else -> Fs.ext().absolutePath }
            val n = Loc.Search(base, query)
            if (l == n) return
            if (l is Loc.Search) { t.loc = n; showTab() } else go(n)
        }
        // the page was rebuilt: keep the cursor in the search box so typing goes on
        if (hadFocus && box != null && !box.hasFocus()) { box.requestFocus(); box.setSelection(box.text.length) }
    }

    private fun search(q: String) {
        val s = q.trim(); if (s.isEmpty()) return
        val l = tabs[cur].loc
        go(Loc.Search(when (l) { is Loc.Dir -> l.path; is Loc.Search -> l.base; else -> Fs.ext().absolutePath }, s))
    }

    // ---------- address bar ----------
    private fun crumbs(path: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val vol = Vols.list(this).filter { it.kind != Vol.Kind.ROOT && it.dir != null }.firstOrNull { path == it.dir!!.absolutePath || path.startsWith(it.dir!!.absolutePath + "/") }
        var base: String
        if (vol != null) { base = vol.dir!!.absolutePath; out += vol.name to base }
        else { base = ""; out += "Root" to "/" }
        val rest = path.removePrefix(if (vol != null) base else "").split("/").filter { it.isNotEmpty() }
        var acc = base
        for (r in rest) { acc = "$acc/$r"; out += r to acc }
        return out
    }

    private fun fillAddress() {
        val ab = addrBox ?: return
        ab.removeAllViews()
        val l = tabs[cur].loc
        if (editing) {
            val et = EditText(this); et.setSingleLine(); et.textSize = 13f; et.setTextColor(p.text); et.background = null
            et.setPaddingRelative(dp(14), 0, dp(14), 0)
            et.setText(if (l is Loc.Dir) l.path else "")
            et.imeOptions = EditorInfo.IME_ACTION_GO
            et.setOnEditorActionListener { v, _, _ -> goTyped(v.text.toString()); true }
            et.setOnFocusChangeListener { _, f -> if (!f && editing) { editing = false; fillAddress() } }
            ab.addView(et, FrameLayout.LayoutParams(MATCH, MATCH))
            et.requestFocus(); et.selectAll()
            return
        }
        val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
        val sv = HorizontalScrollView(this); sv.isHorizontalScrollBarEnabled = false
        val cr = LinearLayout(this); cr.gravity = Gravity.CENTER_VERTICAL; cr.setPaddingRelative(dp(8), 0, dp(8), 0)
        sv.addView(cr, FrameLayout.LayoutParams(WRAP, MATCH))
        when (l) {
            is Loc.Dir -> for ((i, c) in crumbs(l.path).withIndex()) {
                if (i > 0) cr.addView(Ui.text(this, if (T.rtl) "‹" else "›", 14f, p.sub).also { it.setPaddingRelative(dp(2), 0, dp(2), 0) })
                val t = Ui.text(this, if (i == 0) T.t(c.first) else c.first, 13f, p.text, false, false); t.setPaddingRelative(dp(8), dp(6), dp(8), dp(6)); t.isClickable = true
                t.background = Ui.ripple(this, p, null, dpf(8f)); t.setOnClickListener { go(Loc.Dir(c.second)) }
                DnD.attach(this, t) { File(c.second) }
                cr.addView(t)
            }
            else -> cr.addView(Ui.text(this, titleOf(l), 13f, p.text, true).also { it.setPaddingRelative(dp(8), 0, dp(8), 0) })
        }
        sv.post { sv.fullScroll(View.FOCUS_RIGHT) }
        row.addView(sv, LinearLayout.LayoutParams(0, MATCH, 1f))
        val edit = Ui.glyphButton(this, p, "✎", 14f, 32, 32) { editing = true; fillAddress() }
        row.addView(edit)
        row.addView(Ui.glyphButton(this, p, "▾", 14f, 32, 32) { v ->
            val items = Store.recents(this).map { r -> MenuItem(r) { go(Loc.Dir(r)) } }
            if (items.isEmpty()) toast("No history yet") else Pop(this).showBelow(v, items)
        })
        ab.addView(row, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun goTyped(s: String) {
        val f = File(s.trim())
        editing = false
        when {
            f.isDirectory || s.trim() == "/" -> go(Loc.Dir(f.absolutePath))
            f.isFile -> { f.parentFile?.let { go(Loc.Dir(it.absolutePath)) }; openFile(f) }
            else -> { toast("That path does not exist"); fillAddress() }
        }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    /** RW / RO button: shows and flips the mount state of the system area that is open (needs root). */
    private fun updateRw() {
        val b = rwBtn ?: return
        val path = (tabs[cur].loc as? Loc.Dir)?.path
        if (path == null || path.startsWith(Fs.ext().absolutePath) || RootSu.state == 2) { b.visibility = View.GONE; return }
        Thread {
            val m = if (RootSu.ensure()) RootSu.mountOf(path) else null
            runOnUiThread {
                if ((tabs[cur].loc as? Loc.Dir)?.path != path) return@runOnUiThread
                if (m == null) { b.visibility = View.GONE; return@runOnUiThread }
                b.visibility = View.VISIBLE
                b.text = if (m.rw) "RW" else "RO"
                b.background = Ui.rounded(if (m.rw) 0xFF2E9E57.toInt() else 0xFFC0392B.toInt(), dpf(8f))
                b.setOnClickListener {
                    Thread {
                        val r = RootSu.remount(m, !m.rw)
                        runOnUiThread {
                            toast(if (r.first) "Remounted " + m.mp + " as " + (if (m.rw) "ro" else "rw") else "Remount failed: " + r.second.take(140))
                            updateRw(); page?.refresh()
                        }
                    }.start()
                }
            }
        }.start()
    }

    // ---------- sidebar ----------
    private fun setSide(open: Boolean) { sideOpen = open; sidebar?.visibility = if (wide || open) View.VISIBLE else View.GONE }

    private fun buildSidebar(): View {
        val sv = ScrollView(this); sv.setBackgroundColor(if (wide) 0 else p.card); sv.elevation = if (wide) 0f else dpf(8f)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.setPaddingRelative(dp(8), dp(8), dp(8), dp(16))
        sv.addView(col)
        val here = tabs[cur].loc
        sideRows.clear()
        fun row(icon: android.graphics.drawable.Drawable, label: String, selected: Boolean, loc: Loc?, badge: Boolean = false, long: ((View) -> Unit)? = null, click: (() -> Unit)? = null) {
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL; r.setPaddingRelative(dp(10), 0, dp(10), 0); r.isClickable = true
            r.background = Ui.ripple(this, p, if (selected) Ui.rounded(p.accentSoft, dpf(10f)) else null, dpf(10f))
            val iv = ImageView(this); iv.setImageDrawable(icon)
            r.addView(iv, LinearLayout.LayoutParams(dp(22), dp(22)).also { it.marginEnd = dp(12) })
            val t = Ui.text(this, label, 14f, p.text, selected).atStart(); t.maxLines = 1; t.ellipsize = android.text.TextUtils.TruncateAt.END
            r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (badge) r.addView(Ui.text(this, "!", 11f, 0xFFFFFFFF.toInt(), true).also { it.gravity = Gravity.CENTER; it.background = Ui.rounded(0xFFE53935.toInt(), dpf(8f)) }, LinearLayout.LayoutParams(dp(16), dp(16)))
            val act: () -> Unit = { if (click != null) click() else if (loc != null) go(loc); if (!wide) setSide(false) }
            val idx = sideRows.size
            sideRows.add(r to act)
            r.setOnClickListener { focusSide = true; sideCursor = idx; act() }
            if (long != null) { r.setOnLongClickListener { long(it); true }; r.setOnContextClickListener { long(it); true } }
            if (loc is Loc.Dir) DnD.attach(this, r) { File(loc.path) } else if (loc is Loc.Trash) DnD.attachTrash(this, r)
            col.addView(r, LinearLayout.LayoutParams(MATCH, dp(40)))
        }
        fun head(s: String) { col.addView(Ui.text(this, s, 12f, p.sub, true).also { it.setPaddingRelative(dp(10), dp(14), 0, dp(6)) }) }

        row(Glyph(Glyph.Kind.HOME), "Home", here is Loc.Home, Loc.Home)
        head("Quick access")
        for (item in Store.pins(this)) {
            val loc = Places.loc(item) ?: continue
            row(Places.glyph(item), Places.label(item), here == loc, loc, long = { v ->
                val xy = IntArray(2); v.getLocationOnScreen(xy)
                Pop(this).showAt(v, xy[0] + v.width / 2, xy[1] + v.height / 2, listOf(MenuItem("Open in new tab") { newTab(loc) }, MenuItem("Open in new window") { newWindow(loc) }, null, MenuItem("Unpin") { Store.unpin(this, item); pinsChanged() }))
            })
        }
        if (Tags.all().isNotEmpty()) {
            head("Tags")
            for (t in Tags.all()) {
                val loc = Loc.Special("tag:" + t.id)
                row(Tags.dot(this, t.color), t.name, here == loc, loc, long = { v ->
                    val xy = IntArray(2); v.getLocationOnScreen(xy)
                    Pop(this).showAt(v, xy[0] + v.width / 2, xy[1] + v.height / 2, listOf(
                        MenuItem("Rename tag") { Dialogs.ask(this, "Rename tag", t.name, "Rename") { n -> Tags.rename(t.id, n); showTab() } },
                        MenuItem("Change colour") { TagUi.recolor(this, t) { showTab(); refreshAll() } },
                        MenuItem("Delete tag", danger = true) { Dialogs.confirm(this, "Delete tag", "Delete the tag \"" + t.name + "\"? The files themselves stay.", "Delete") { Tags.remove(t.id); if (tabs[cur].loc == loc) go(Loc.Home) else showTab() } }))
                })
            }
        }
        head("Drives")
        for (v in Vols.list(this)) {
            val d = v.dir ?: continue
            if (v.kind == Vol.Kind.ROOT) {
                val no = RootSu.state == 2
                row(Places.volGlyph(v), "Root", here is Loc.Dir && here.path == "/", null, no, click = { if (no) toast("Get root access for the root directory") else go(Loc.Dir("/")) })
            } else row(Places.volGlyph(v), v.name, here is Loc.Dir && here.path == d.absolutePath, Loc.Dir(d.absolutePath))
        }
        for (d in Mtp.list(this)) {
            row(Glyph(Glyph.Kind.PHONE), Mtp.label(d), here is Loc.Mtp && here.dev == d.deviceName, null, click = { openPhone(d) })
        }
        head("Cloud")
        for (a in DavAccts.all(this)) {
            row(Glyph(Glyph.Kind.DRIVE), a.name, here is Loc.Dav && here.acct == a.id, Loc.Dav(a.id), long = { v ->
                val xy = IntArray(2); v.getLocationOnScreen(xy); Pop(this).showAt(v, xy[0] + v.width / 2, xy[1] + v.height / 2, listOf(MenuItem("Remove account", danger = true) { Dialogs.confirm(this, "Remove account", "Remove \"" + a.name + "\" from this app? Nothing is deleted on the server.", "Remove") { DavAccts.remove(this, a.id); if (here is Loc.Dav && here.acct == a.id) go(Loc.Home) else showTab() } }))
            })
        }
        row(Glyph(Glyph.Kind.FOLDER), "Add cloud account…", false, null, click = { DavAccts.addDialog(this) { a -> go(Loc.Dav(a.id)) } })
        sv.post { paintSide() }
        return sv
    }

    private fun sideVisible() = sidebar?.visibility == View.VISIBLE && (wide || sideOpen)

    /** The soft outline on the sidebar line the keyboard is on (only while the keyboard is in the sidebar). */
    private fun paintSide() {
        for ((i, r) in sideRows.withIndex()) r.first.foreground = if (focusSide && i == sideCursor && sideVisible()) Ui.rounded(0, dpf(10f), p.accent, dp(2)) else null
    }

    private fun sideKey(code: Int): Boolean {
        if (sideRows.isEmpty()) return false
        val toPage = if (T.rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val n = sideRows.size
        when (code) {
            KeyEvent.KEYCODE_DPAD_DOWN -> sideCursor = if (sideCursor < 0) 0 else minOf(n - 1, sideCursor + 1)
            KeyEvent.KEYCODE_DPAD_UP -> sideCursor = if (sideCursor < 0) n - 1 else maxOf(0, sideCursor - 1)
            KeyEvent.KEYCODE_MOVE_HOME -> sideCursor = 0
            KeyEvent.KEYCODE_MOVE_END -> sideCursor = n - 1
            KeyEvent.KEYCODE_PAGE_DOWN -> sideCursor = minOf(n - 1, maxOf(0, sideCursor) + 8)
            KeyEvent.KEYCODE_PAGE_UP -> sideCursor = maxOf(0, maxOf(0, sideCursor) - 8)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (sideCursor in 0 until n) sideRows[sideCursor].second(); return true }
            toPage -> { focusSide = false; paintSide(); return true }
            else -> return false
        }
        paintSide()
        sideRows.getOrNull(sideCursor)?.first?.let { v -> v.requestRectangleOnScreen(android.graphics.Rect(0, -dp(8), v.width, v.height + dp(8)), false) }
        return true
    }

    private fun touchArea(e: MotionEvent) {
        val x = e.rawX.toInt(); val y = e.rawY.toInt()
        val r = android.graphics.Rect()
        val inSide = sideVisible() && sidebar?.getGlobalVisibleRect(r) == true && r.contains(x, y)
        val inPage = !inSide && content?.getGlobalVisibleRect(r) == true && r.contains(x, y)
        if (inSide && !focusSide) { focusSide = true; paintSide() }
        else if (inPage && focusSide) { focusSide = false; paintSide() }
    }

    // ---------- navigation ----------
    /** Host of the second pane: its folder changes are kept in tab.right. */
    private inner class PaneHost(val m: MainActivity) : Host by m {
        override fun go(loc: Loc) {
            if (loc is Loc.Dir || loc is Loc.Search) { tabs[cur].right = loc; showTab() } else m.go(loc)
        }
    }

    private fun makePage(l: Loc): Page = when (l) {
        is Loc.Home -> HomePage(this)
        is Loc.Tag -> FilePage(this, Loc.Special("tag:" + l.id))
        is Loc.Mtp -> MtpPage(this, l)
        is Loc.Dav -> DavPage(this, l)
        is Loc.Dir -> if (Store.str(this, "view", "details") == "columns" && l.only.isEmpty() && !Store.bool(this, "dual", false)) ColumnsPage(this, File(l.path)) else FilePage(this, l)
        is Loc.Search -> FilePage(this, l)
        is Loc.Trash -> TrashPage(this)
        is Loc.Special -> if (l.kind == "apps") AppsPage(this) else if (l.kind.startsWith("tag:")) FilePage(this, l) else MediaPage(this, l.kind)
    }

    private fun showTab() {
        val cnt = content ?: return
        page?.destroy()
        val t = tabs[cur]
        page2?.destroy(); page2 = null; details = null
        val pg = makePage(t.loc); page = pg
        cnt.removeAllViews()
        val row = LinearLayout(this)
        val panes = LinearLayout(this)
        val dual = Store.bool(this, "dual", false) && widthDp() >= 900 && t.loc is Loc.Dir
        if (dual) {
            panes.addView(pg.view, LinearLayout.LayoutParams(0, MATCH, 1f))
            val sep = View(this); sep.setBackgroundColor(p.line)
            panes.addView(sep, LinearLayout.LayoutParams(dp(1), MATCH))
            var rl = t.right
            if (rl !is Loc.Dir && rl !is Loc.Search) rl = Loc.Dir((t.loc as Loc.Dir).path)
            t.right = rl
            val pg2 = FilePage(PaneHost(this), rl, 1); page2 = pg2
            val right = LinearLayout(this); right.orientation = LinearLayout.VERTICAL
            right.addView(rightHeader(rl), LinearLayout.LayoutParams(MATCH, dp(36)))
            right.addView(pg2.view, LinearLayout.LayoutParams(MATCH, 0, 1f))
            panes.addView(right, LinearLayout.LayoutParams(0, MATCH, 1f))
        } else panes.addView(pg.view, LinearLayout.LayoutParams(MATCH, MATCH))
        row.addView(panes, LinearLayout.LayoutParams(0, MATCH, 1f))
        if (Store.bool(this, "pane", false) && widthDp() >= 900) {
            val d = DetailsPane(this); details = d; d.show(emptyList())
            val sep = View(this); sep.setBackgroundColor(p.line)
            row.addView(sep, LinearLayout.LayoutParams(dp(1), MATCH))
            row.addView(d.view, LinearLayout.LayoutParams(dp(280), MATCH))
        }
        cnt.addView(row, FrameLayout.LayoutParams(MATCH, MATCH))
        editing = false
        searchBox?.let { sb ->
            val sl = t.loc
            val want = if (sl is Loc.Search) sl.q else ""
            if (sb.text.toString().trim() != want && !(sl is Loc.Search && sb.hasFocus())) { searchSilent = true; sb.setText(want); searchSilent = false }
        }
        fillTabs(); fillAddress(); updateRw()
        navBack?.alpha = if (t.back.isEmpty()) 0.4f else 1f
        navFwd?.alpha = if (t.fwd.isEmpty()) 0.4f else 1f
        val l = t.loc
        if (l is Loc.Dir) Store.addRecent(this, l.path)
        refreshSidebar()
    }

    /** Build the sidebar again (highlight, pinned places). */
    private fun refreshSidebar() {
        val old = sidebar
        if (old != null && old.parent is FrameLayout) {
            val par = old.parent as FrameLayout
            val idx = par.indexOfChild(old); par.removeView(old)
            val ns = buildSidebar(); sidebar = ns
            par.addView(ns, idx, FrameLayout.LayoutParams(dp(220), MATCH))
            ns.visibility = if (wide || sideOpen) View.VISIBLE else View.GONE
        }
    }

    override fun go(loc: Loc) {
        val t = tabs[cur]
        if (t.loc == loc) { page?.refresh(); return }
        t.back.add(t.loc); t.fwd.clear(); t.loc = loc; showTab()
    }

    private fun back() { val t = tabs[cur]; if (t.back.isEmpty()) return; t.fwd.add(t.loc); t.loc = t.back.removeAt(t.back.size - 1); showTab() }
    private fun forward() { val t = tabs[cur]; if (t.fwd.isEmpty()) return; t.back.add(t.loc); t.loc = t.fwd.removeAt(t.fwd.size - 1); showTab() }
    private fun up() {
        when (val l = tabs[cur].loc) {
            is Loc.Dir -> { val par = File(l.path).parentFile; if (par != null && l.path != "/") go(Loc.Dir(par.absolutePath)) else go(Loc.Home) }
            is Loc.Search -> go(Loc.Dir(l.base))
            is Loc.Mtp -> {
                if (l.storage == -1) go(Loc.Home)
                else if (l.parent == -1) go(Loc.Mtp(l.dev))
                else Thread { val md = Mtp.device(l.dev); val par = if (md != null) Mtp.parentOf(md, l.parent) else null; runOnUiThread { go(if (par == null) Loc.Mtp(l.dev) else Loc.Mtp(l.dev, l.storage, par)) } }.start()
            }
            is Loc.Dav -> { if (l.path.isEmpty() || l.path == "/") go(Loc.Home) else go(Loc.Dav(l.acct, l.path.trimEnd('/').substringBeforeLast('/', ""))) }
            is Loc.Home -> {}
            else -> go(Loc.Home)
        }
    }

    override fun newTab(loc: Loc) { tabs.add(Tab(loc)); cur = tabs.lastIndex; showTab() }

    private fun closeTab(i: Int) {
        if (tabs.size == 1) { finish(); return }
        tabs.removeAt(i); if (cur >= tabs.size) cur = tabs.lastIndex else if (i < cur) cur--
        showTab()
    }

    override fun newWindow(loc: Loc) {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        when (loc) {
            is Loc.Dir -> i.putExtra("path", loc.path)
            is Loc.Search -> i.putExtra("path", loc.base)
            is Loc.Trash -> i.putExtra("trash", true)
            else -> {}
        }
        try { startActivity(i) } catch (_: Exception) { toast("Could not open a new window") }
    }

    override fun openPhone(d: android.hardware.usb.UsbDevice) {
        toast("Connecting…")
        Mtp.connect(this, d) { md ->
            if (md == null) toast("Could not open the phone. Unlock it and set the USB mode to File transfer.")
            else go(Loc.Mtp(d.deviceName))
        }
    }

    override fun refreshAll() { page?.refresh(); page2?.refresh() }
    override fun relayout() { showTab() }
    override fun selectionChanged(files: List<File>) { details?.show(files) }
    override fun otherPaneDir(pane: Int): File? {
        if (page2 == null) return null
        val l = if (pane == 0) tabs[cur].right else tabs[cur].loc
        return (l as? Loc.Dir)?.path?.let { File(it) }
    }

    private fun rightHeader(l: Loc): View {
        val h = LinearLayout(this); h.gravity = Gravity.CENTER_VERTICAL; h.setBackgroundColor(p.card)
        h.addView(Ui.glyphButton(this, p, "↑", 15f, 32, 32) {
            ((tabs[cur].right as? Loc.Dir)?.path)?.let { pa -> File(pa).parentFile?.let { par -> tabs[cur].right = Loc.Dir(par.absolutePath); showTab() } }
        })
        val path = if (l is Loc.Dir) l.path else if (l is Loc.Search) "Search: " + l.q else ""
        val t = Ui.text(this, path, 12f, p.text); t.maxLines = 1; t.ellipsize = android.text.TextUtils.TruncateAt.START; t.isClickable = true
        t.setOnClickListener { v ->
            val items = Store.recents(this).map { r -> MenuItem(r) { tabs[cur].right = Loc.Dir(r); showTab() } }
            if (items.isNotEmpty()) Pop(this).showBelow(v, items)
        }
        h.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        h.addView(Ui.glyphButton(this, p, "×", 16f, 32, 32) { Store.setBool(this, "dual", false); showTab() })
        return h
    }

    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) touchArea(e)
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN && page2 != null) {
            val v2 = page2!!.view; val xy = IntArray(2); v2.getLocationOnScreen(xy)
            activePane = if (e.rawX >= xy[0]) 1 else 0
        }
        return super.dispatchTouchEvent(e)
    }
    override fun pinsChanged() { showTab(); refreshSidebar() }
    override fun showStatus() { statusBtn?.let { StatusPanel.show(this, it) } }
    override fun contentWidthDp(): Int = content?.width?.let { (it / resources.displayMetrics.density).toInt() } ?: widthDp()

    /** A file the app cannot read (system folders): su copies it to a hidden folder first, then it opens from there. */
    private fun openViaRoot(f: File) {
        val out = File(File(Fs.ext(), ".deskroot").also { it.mkdirs() }, f.name)
        Jobs.start(Job("Preparing " + f.name), { j -> j.detail = f.name; if (!RootSu.sh("cp -f " + RootSu.q(f) + " " + RootSu.q(out))) j.failed++ }) {
            if (out.exists()) Fs.view(this, out) else toast("Could not read the file")
        }
    }

    override fun openFile(f: File) {
        if (!f.canRead() && RootSu.state == 1) { openViaRoot(f); return }
        Store.setList(this, "recentfiles", (listOf(f.absolutePath) + Store.list(this, "recentfiles").filter { it != f.absolutePath }).take(20))
        if (Apks.isBundle(f)) { Apks.install(this, f); return }
        Fs.view(this, f)
    }

    // ---------- keyboard ----------
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && Environment.isExternalStorageManager()) {
            val ctrl = e.isCtrlPressed; val alt = e.isAltPressed; val shift = e.isShiftPressed
            val typing = currentFocus is EditText
            when {
                ctrl && e.keyCode == KeyEvent.KEYCODE_T -> { newTab(Loc.Home); return true }
                ctrl && e.keyCode == KeyEvent.KEYCODE_W -> { closeTab(cur); return true }
                ctrl && e.keyCode == KeyEvent.KEYCODE_N && !shift -> { newWindow(tabs[cur].loc); return true }
                (ctrl && e.keyCode == KeyEvent.KEYCODE_L) || (alt && e.keyCode == KeyEvent.KEYCODE_D) -> { editing = true; fillAddress(); return true }
                ctrl && e.keyCode == KeyEvent.KEYCODE_F -> { searchBox?.requestFocus() ?: Dialogs.ask(this, "Search", "", "Search") { search(it) }; return true }
                ctrl && e.keyCode == KeyEvent.KEYCODE_TAB -> { cur = (cur + 1) % tabs.size; showTab(); return true }
                alt && e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { back(); return true }
                alt && e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { forward(); return true }
                alt && e.keyCode == KeyEvent.KEYCODE_DPAD_UP -> { up(); return true }
                e.keyCode == KeyEvent.KEYCODE_F5 -> { refreshAll(); return true }
                !typing && e.keyCode == KeyEvent.KEYCODE_DEL -> { back(); return true }
                alt && e.keyCode == KeyEvent.KEYCODE_P -> { Store.setBool(this, "pane", !Store.bool(this, "pane", false)); showTab(); return true }
                !typing && !ctrl && !alt && (e.keyCode == KeyEvent.KEYCODE_TAB || e.keyCode == KeyEvent.KEYCODE_F6) && sideVisible() -> { focusSide = !focusSide; if (focusSide && sideCursor < 0) sideCursor = 0; paintSide(); return true }
                !typing && !ctrl && !alt && focusSide && sideVisible() && sideKey(e.keyCode) -> return true
                !typing && (if (activePane == 1 && page2 != null) page2 else page)?.onKey(e.keyCode, ctrl, shift) == true -> return true
            }
        }
        return super.dispatchKeyEvent(e)
    }
}

/** The Trash icon: opens a Desktop Files window on the Trash page. */
class TrashActivity : MainActivity() {
    override fun startLoc(): Loc? = Loc.Trash
}
