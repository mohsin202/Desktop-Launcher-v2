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
    private var p: Pal = Ui.pal(this)
    private var content: FrameLayout? = null
    private var sidebar: View? = null
    private var sideOpen = false
    private var wide = true
    private var statusBtn: View? = null
    private var addrBox: FrameLayout? = null
    private var searchBox: EditText? = null
    private var navBack: TextView? = null
    private var navFwd: TextView? = null
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
        Jobs.listen(jobListener)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }
        hadAccess = Environment.isExternalStorageManager()
        buildUi()
    }

    override fun onDestroy() { super.onDestroy(); Jobs.unlisten(jobListener); page?.destroy() }

    override fun onResume() {
        super.onResume()
        val now = Environment.isExternalStorageManager()
        if (now != hadAccess) { hadAccess = now; buildUi() } else page?.refresh()
    }

    override fun onConfigurationChanged(n: Configuration) { super.onConfigurationChanged(n); buildUi() }

    private fun onBack() {
        when {
            sideOpen -> setSide(false)
            tabs[cur].back.isNotEmpty() -> back()
            tabs.size > 1 -> closeTab(cur)
            else -> finish()
        }
    }

    // ---------- UI ----------
    private fun widthDp(): Int = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt().let {
        val w = window.decorView.width; if (w > 0) (w / resources.displayMetrics.density).toInt() else it }

    private fun titleOf(l: Loc): String = when (l) {
        is Loc.Home -> "Home"
        is Loc.Dir -> if (l.path == Fs.ext().absolutePath) "Internal storage" else if (l.path == "/") "Root" else File(l.path).name
        is Loc.Trash -> "Trash"
        is Loc.Search -> "Search: " + l.q
        is Loc.Special -> l.kind.replaceFirstChar { it.uppercase() }
    }

    private fun buildUi() {
        p = Ui.pal(this)
        page?.destroy(); page = null
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
        body.addView(cnt, FrameLayout.LayoutParams(MATCH, MATCH).also { if (wide) it.leftMargin = dp(220) })
        body.addView(side, FrameLayout.LayoutParams(dp(220), MATCH))
        side.visibility = if (wide || sideOpen) View.VISIBLE else View.GONE
        showTab()
    }

    private fun permissionScreen(root: LinearLayout) {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.gravity = Gravity.CENTER
        box.setPadding(dp(32), dp(32), dp(32), dp(32))
        val iv = ImageView(this); iv.setImageDrawable(Glyph(Glyph.Kind.FOLDER))
        box.addView(iv, LinearLayout.LayoutParams(dp(72), dp(72)))
        box.addView(Ui.text(this, "Desktop Files", 22f, p.text, true).also { it.setPadding(0, dp(12), 0, dp(6)) })
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
        val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL; row.setPadding(dp(8), dp(6), dp(8), 0)
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
            chip.setPadding(dp(12), 0, dp(4), 0)
            chip.background = if (i == cur) Ui.rounded(p.bg, dpf(10f)).also { it.cornerRadii = floatArrayOf(dpf(10f), dpf(10f), dpf(10f), dpf(10f), 0f, 0f, 0f, 0f) }
                              else Ui.ripple(this, p, null, dpf(10f))
            chip.isClickable = true
            chip.setOnClickListener { if (i != cur) { cur = i; showTab() } }
            val ic = ImageView(this)
            ic.setImageDrawable(when (t.loc) { is Loc.Home -> Glyph(Glyph.Kind.HOME); is Loc.Trash -> Glyph(Glyph.Kind.BIN); else -> Glyph(Glyph.Kind.FOLDER) })
            chip.addView(ic, LinearLayout.LayoutParams(dp(18), dp(18)).also { it.rightMargin = dp(8) })
            val tx = Ui.text(this, titleOf(t.loc), 13f, p.text, i == cur); tx.maxLines = 1; tx.ellipsize = android.text.TextUtils.TruncateAt.END
            tx.maxWidth = dp(150)
            chip.addView(tx)
            val x = Ui.glyphButton(this, p, "×", 15f, 28, 28) { closeTab(i) }
            chip.addView(x, LinearLayout.LayoutParams(dp(28), dp(28)).also { it.leftMargin = dp(4) })
            row.addView(chip, LinearLayout.LayoutParams(WRAP, dp(34)).also { it.rightMargin = dp(2) })
        }
        row.addView(Ui.glyphButton(this, p, "+", 20f, 34, 34) { newTab(Loc.Home) })
    }

    private fun buildToolbar(): View {
        val bar = LinearLayout(this); bar.gravity = Gravity.CENTER_VERTICAL; bar.setPadding(dp(6), 0, dp(6), 0)
        if (!wide) bar.addView(Ui.glyphButton(this, p, "☰", 18f) { setSide(!sideOpen) })
        navBack = Ui.glyphButton(this, p, "←", 18f) { back() }; bar.addView(navBack)
        navFwd = Ui.glyphButton(this, p, "→", 18f) { forward() }; bar.addView(navFwd)
        bar.addView(Ui.glyphButton(this, p, "↑", 18f) { up() })
        bar.addView(Ui.glyphButton(this, p, "⟳", 18f) { refreshAll() })
        val ab = FrameLayout(this); addrBox = ab
        ab.background = Ui.rounded(p.card, dpf(18f), p.line, 1)
        bar.addView(ab, LinearLayout.LayoutParams(0, dp(36), 1f).also { it.leftMargin = dp(6); it.rightMargin = dp(6) })
        if (widthDp() >= 820) {
            val s = EditText(this); searchBox = s
            s.hint = "Search"; s.setSingleLine(); s.textSize = 13f; s.setTextColor(p.text); s.setHintTextColor(p.sub)
            s.background = Ui.rounded(p.card, dpf(18f), p.line, 1); s.setPadding(dp(14), 0, dp(14), 0)
            s.imeOptions = EditorInfo.IME_ACTION_SEARCH
            s.setOnEditorActionListener { v, _, _ -> search(v.text.toString()); true }
            bar.addView(s, LinearLayout.LayoutParams(dp(190), dp(36)))
        } else {
            searchBox = null
            bar.addView(Ui.glyphButton(this, p, "⌕", 20f) { Dialogs.ask(this, "Search", "", "Search") { search(it) } })
        }
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
            MenuItem("New window") { newWindow(Loc.Home) },
            MenuItem("Desktop Files 1.0", enabled = false))
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
            et.setPadding(dp(14), 0, dp(14), 0)
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
        val cr = LinearLayout(this); cr.gravity = Gravity.CENTER_VERTICAL; cr.setPadding(dp(8), 0, dp(8), 0)
        sv.addView(cr, FrameLayout.LayoutParams(WRAP, MATCH))
        when (l) {
            is Loc.Dir -> for ((i, c) in crumbs(l.path).withIndex()) {
                if (i > 0) cr.addView(Ui.text(this, "›", 14f, p.sub).also { it.setPadding(dp(2), 0, dp(2), 0) })
                val t = Ui.text(this, c.first, 13f, p.text); t.setPadding(dp(8), dp(6), dp(8), dp(6)); t.isClickable = true
                t.background = Ui.ripple(this, p, null, dpf(8f)); t.setOnClickListener { go(Loc.Dir(c.second)) }
                cr.addView(t)
            }
            else -> cr.addView(Ui.text(this, titleOf(l), 13f, p.text, true).also { it.setPadding(dp(8), 0, dp(8), 0) })
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

    // ---------- sidebar ----------
    private fun setSide(open: Boolean) { sideOpen = open; sidebar?.visibility = if (wide || open) View.VISIBLE else View.GONE }

    private fun buildSidebar(): View {
        val sv = ScrollView(this); sv.setBackgroundColor(if (wide) 0 else p.card); sv.elevation = if (wide) 0f else dpf(8f)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.setPadding(dp(8), dp(8), dp(8), dp(16))
        sv.addView(col)
        val here = tabs[cur].loc
        fun row(icon: Glyph, label: String, selected: Boolean, loc: Loc?, badge: Boolean = false, long: ((View) -> Unit)? = null, click: (() -> Unit)? = null) {
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL; r.setPadding(dp(10), 0, dp(10), 0); r.isClickable = true
            r.background = Ui.ripple(this, p, if (selected) Ui.rounded(p.accentSoft, dpf(10f)) else null, dpf(10f))
            val iv = ImageView(this); iv.setImageDrawable(icon)
            r.addView(iv, LinearLayout.LayoutParams(dp(22), dp(22)).also { it.rightMargin = dp(12) })
            val t = Ui.text(this, label, 14f, p.text, selected); t.maxLines = 1; t.ellipsize = android.text.TextUtils.TruncateAt.END
            r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (badge) r.addView(Ui.text(this, "!", 11f, 0xFFFFFFFF.toInt(), true).also { it.gravity = Gravity.CENTER; it.background = Ui.rounded(0xFFE53935.toInt(), dpf(8f)) }, LinearLayout.LayoutParams(dp(16), dp(16)))
            r.setOnClickListener { if (click != null) click() else if (loc != null) go(loc); if (!wide) setSide(false) }
            if (long != null) { r.setOnLongClickListener { long(it); true }; r.setOnContextClickListener { long(it); true } }
            col.addView(r, LinearLayout.LayoutParams(MATCH, dp(40)))
        }
        fun head(s: String) { col.addView(Ui.text(this, s, 12f, p.sub, true).also { it.setPadding(dp(10), dp(14), 0, dp(6)) }) }

        row(Glyph(Glyph.Kind.HOME), "Home", here is Loc.Home, Loc.Home)
        head("Quick access")
        for (item in Store.pins(this)) {
            if (item == "sp:apps") continue
            val loc = Places.loc(item) ?: continue
            row(Places.glyph(item), Places.label(item), here == loc, loc, long = { v ->
                val xy = IntArray(2); v.getLocationOnScreen(xy)
                Pop(this).showAt(v, xy[0] + v.width / 2, xy[1] + v.height / 2, listOf(MenuItem("Open in new tab") { newTab(loc) }, MenuItem("Open in new window") { newWindow(loc) }, null, MenuItem("Unpin") { Store.unpin(this, item); pinsChanged() }))
            })
        }
        head("Drives")
        for (v in Vols.list(this)) {
            val d = v.dir ?: continue
            if (v.kind == Vol.Kind.ROOT) {
                val no = RootSu.state == 2
                row(Places.volGlyph(v), "Root", here is Loc.Dir && here.path == "/", null, no, click = { if (no) toast("Get root access for the root directory") else go(Loc.Dir("/")) })
            } else row(Places.volGlyph(v), v.name, here is Loc.Dir && here.path == d.absolutePath, Loc.Dir(d.absolutePath))
        }
        return sv
    }

    // ---------- navigation ----------
    private fun makePage(l: Loc): Page = when (l) {
        is Loc.Home -> HomePage(this)
        is Loc.Dir, is Loc.Search -> FilePage(this, l)
        is Loc.Trash -> TrashPage(this)
        is Loc.Special -> SoonPage(this, "This page comes in the next update")
    }

    private fun showTab() {
        val cnt = content ?: return
        page?.destroy()
        val t = tabs[cur]
        val pg = makePage(t.loc); page = pg
        cnt.removeAllViews(); cnt.addView(pg.view, FrameLayout.LayoutParams(MATCH, MATCH))
        editing = false
        fillTabs(); fillAddress()
        navBack?.alpha = if (t.back.isEmpty()) 0.4f else 1f
        navFwd?.alpha = if (t.fwd.isEmpty()) 0.4f else 1f
        val l = t.loc
        if (l is Loc.Dir) Store.addRecent(this, l.path)
        // refresh the sidebar highlight
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

    override fun refreshAll() { page?.refresh() }
    override fun pinsChanged() { showTab() }
    override fun showStatus() { statusBtn?.let { StatusPanel.show(this, it) } }
    override fun contentWidthDp(): Int = content?.width?.let { (it / resources.displayMetrics.density).toInt() } ?: widthDp()

    override fun openFile(f: File) {
        Store.setList(this, "recentfiles", (listOf(f.absolutePath) + Store.list(this, "recentfiles").filter { it != f.absolutePath }).take(20))
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
                !typing && page?.onKey(e.keyCode, ctrl, shift) == true -> return true
            }
        }
        return super.dispatchKeyEvent(e)
    }
}

/** The Trash icon: opens a Desktop Files window on the Trash page. */
class TrashActivity : MainActivity() {
    override fun startLoc(): Loc? = Loc.Trash
}
