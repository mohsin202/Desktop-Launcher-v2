package com.android.desktoplauncher

import android.Manifest
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.ContactsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

class Result(
    val header: Boolean,
    val title: String,
    val sub: String = "",
    val icon: Drawable? = null,
    val file: File? = null,
    val action: () -> Unit = {},
    val keepOpen: Boolean = false,
    val custom: (() -> View)? = null
)

/**
 * App drawer: rounded panel at the bottom-left with a search field,
 * an icon grid, and universal search (apps, settings, shortcuts, contacts, files, web).
 * Used both inside the launcher activity and inside the taskbar overlay window.
 */
class DrawerView(private val c: Context, private val bottomMargin: Int, private val wide: Boolean = false, private val onClose: () -> Unit) : FrameLayout(c) {
    val search = EditText(c)
    private val panel = LinearLayout(c)
    private val grid = GridView(c)
    private val list = ListView(c)
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var gen = 0
    private var activeQuery = ""
    private var keyboardDismissedThisGesture = false
    @Volatile private var showAllFiles = false
    private val allApps: List<AppInfo> = AppRepo.apps(c)
    private var apps: List<AppInfo> = allApps
    private var results: List<Result> = emptyList()
    private var shortcutCache: List<ShortcutInfo> = emptyList()
    private val themed = Prefs.bool(c, Prefs.K_THEMED, false)
    private val dots = Prefs.bool(c, Prefs.K_DOTS, true)
    private var menu: PopupMenuX? = null
    private var searchMode = false
    private var moreApps = false
    private var dlFiles: List<Downloads.Entry> = emptyList()
    private var dlExpanded = false
    // resizable panel (desktop mode only): handle at the top corner opposite to the side the drawer sits on
    private var centredMode = false
    private var handleVisible = false
    private var resizing = false
    private var rx0 = 0f
    private var ry0 = 0f
    private var rw0 = 0
    private var rh0 = 0
    private val handlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private var dlApps: List<AppInfo> = emptyList()

    private val gridAdapter = object : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(p: Int) = apps[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val cell = (cv as? AppCell) ?: AppCell(c)
            val a = apps[p]
            cell.bind(a)
            cell.setSecondaryClick { x, y -> showAppMenu(a, x, y) }
            return cell
        }
    }

    private val listAdapter = object : BaseAdapter() {
        override fun getCount() = results.size
        override fun getItem(p: Int) = results[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun areAllItemsEnabled() = false
        override fun isEnabled(p: Int) = !results[p].header && results[p].custom == null
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val r = results[p]
            r.custom?.let { return it() }
            val row = LinearLayout(c)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            if (r.header) {
                row.setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(4))
                val t = TextView(c)
                t.text = UiText.text(c, r.title)
                t.textSize = 12f
                t.setTextColor(Colors.accent(c))
                row.addView(t)
            } else {
                row.setPadding(c.dp(12), c.dp(8), c.dp(12), c.dp(8))
                if (r.icon != null) {
                    val iv = android.widget.ImageView(c)
                    if (r.file != null) Thumbs.bind(iv, r.file, c.dp(36)) else iv.setImageDrawable(r.icon)
                    row.addView(iv, LinearLayout.LayoutParams(c.dp(36), c.dp(36)).also { it.rightMargin = c.dp(12) })
                }
                val col = LinearLayout(c)
                col.orientation = LinearLayout.VERTICAL
                val t = TextView(c)
                t.text = UiText.text(c, r.title)
                t.textSize = 15f
                t.setTextColor(Colors.text(c))
                t.maxLines = 1
                t.ellipsize = android.text.TextUtils.TruncateAt.END
                col.addView(t)
                if (r.sub.isNotEmpty()) {
                    val s = TextView(c)
                    s.text = UiText.text(c, r.sub)
                    s.textSize = 12f
                    s.alpha = 0.6f
                    s.setTextColor(Colors.text(c))
                    s.maxLines = 1
                    s.ellipsize = android.text.TextUtils.TruncateAt.END
                    col.addView(s)
                }
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            return row
        }
    }

    init {
        UiText.applyDirection(this, c)
        UiText.applyDirection(panel, c)
        UiText.applyDirection(search, c)
        isClickable = true
        setOnClickListener {
            // The first outside tap that dismissed the keyboard must not also close the drawer.
            if (keyboardDismissedThisGesture) keyboardDismissedThisGesture = false
            else if (!hideKeyboardIfVisible()) onClose()
        }
        val dm = c.resources.displayMetrics
        var pw = if (wide) dm.widthPixels - c.dp(16) else minOf(c.dp(440), dm.widthPixels - c.dp(16))
        var ph = if (wide) (dm.heightPixels * 0.86f).toInt() else minOf(c.dp(580), (dm.heightPixels * 0.78f).toInt())
        if (!wide) {
            // size the user chose last time
            val sw = Prefs.getLong(c, "drawerWdp", 0L).toInt()
            val sh = Prefs.getLong(c, "drawerHdp", 0L).toInt()
            if (sw > 0) pw = c.dp(sw).coerceIn(c.dp(280), dm.widthPixels - c.dp(16))
            if (sh > 0) ph = c.dp(sh).coerceIn(c.dp(320), (dm.heightPixels * 0.95f).toInt())
        }
        panel.orientation = LinearLayout.VERTICAL
        panel.background = Colors.rounded(Colors.surface(c), c.dp(28).toFloat())
        panel.elevation = c.dp(10).toFloat()
        panel.setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(10))
        panel.isClickable = true
        // Portrait (and phone mode): centred. Landscape desktop: starts near the drawer button.
        val portrait = dm.heightPixels > dm.widthPixels
        val centred = wide || portrait
        centredMode = centred
        setWillNotDraw(false)
        val lp = LayoutParams(pw, ph, if (centred) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM or Gravity.START)
        // keep clear of the punch hole / side navigation bar in landscape
        val si = Insets.safe(c)
        lp.leftMargin = si.left; lp.rightMargin = si.right
        if (!centred) lp.marginStart = c.dp(8) + (if (Prefs.direction(c) == 2) si.right else si.left)
        lp.bottomMargin = bottomMargin
        addView(panel, lp)

        search.hint = UiText.text(c, "Search")
        search.setSingleLine()
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.textSize = 16f
        search.setTextColor(Colors.text(c))
        search.setHintTextColor((Colors.text(c) and 0x00FFFFFF) or 0x88000000.toInt())
        search.background = Colors.rounded(Colors.surfaceHigh(c), c.dp(26).toFloat())
        search.setPadding(c.dp(20), c.dp(10), c.dp(20), c.dp(10))
        search.gravity = Gravity.CENTER_VERTICAL
        panel.addView(search, LinearLayout.LayoutParams(MATCH, c.dp(50)))

        val cols = if (wide) 4 else maxOf(3, (pw - c.dp(28)) / c.dp(92))
        grid.numColumns = cols
        grid.adapter = gridAdapter
        grid.verticalSpacing = c.dp(6)
        grid.selector = android.graphics.drawable.ColorDrawable(0)
        grid.setOnItemClickListener { _, _, p, _ -> launch(apps[p]) }
        var lx = 0; var ly = 0
        grid.setOnTouchListener { _, ev -> lx = ev.rawX.toInt(); ly = ev.rawY.toInt(); false }
        grid.setOnItemLongClickListener { _, _, p, _ -> showAppMenu(apps[p], lx, ly); true }
        val gp = LinearLayout.LayoutParams(MATCH, 0, 1f)
        gp.topMargin = c.dp(10)
        panel.addView(grid, gp)

        list.adapter = listAdapter
        list.divider = null
        list.visibility = GONE
        list.setOnItemClickListener { _, _, p, _ -> results[p].let { if (!it.header && it.custom == null) { remember(); it.action(); if (!it.keepOpen) onClose() } } }
        list.setOnItemLongClickListener { _, v, p, _ ->
            val r = results.getOrNull(p) ?: return@setOnItemLongClickListener false
            if (r.header || r.custom != null) return@setOnItemLongClickListener true
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            val items = ArrayList<MenuItem>()
            items += MenuItem("Open") { r.action(); onClose() }
            r.file?.let { f -> items += MenuItem("Create shortcut on Desktop") {
                val dest = Files.unique(Files.root(c), f.name + ".desktoplink")
                try {
                    val content = "DESKTOP_SHORTCUT\n${f.absolutePath}"
                    dest.writeText(content)
                    c.toast("Shortcut created on Desktop")
                } catch (_: Exception) { c.toast("Could not create shortcut") }
            } }
            PopupMenuX(c, this).show(loc[0], loc[1] + v.height, items)
            true
        }
        panel.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f).also { it.topMargin = c.dp(6) })

        search.setOnTouchListener { _, ev -> if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) enterSearch(); false }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) { runSearch(s?.toString() ?: "") }
        })
        search.setOnEditorActionListener { _, _, _ ->
            remember()
            if (activeQuery.isNotEmpty() && apps.isNotEmpty()) launch(apps[0])
            else results.firstOrNull { !it.header && it.custom == null }?.let { it.action(); onClose() }
            true
        }
        loadShortcuts()
    }

    /** True only when the currently displayed drawer content is already at its top edge. */
    fun isContentAtTopForSwipeDismiss(): Boolean {
        val target = if (list.visibility == VISIBLE) list else grid
        if (target.visibility != VISIBLE) return true
        if (target.firstVisiblePosition > 0) return false
        val first = target.getChildAt(0) ?: return true
        return first.top >= target.paddingTop
    }

    private fun hideKeyboardIfVisible(): Boolean {
        val visible = try { rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true } catch (_: Exception) { false }
        if (!visible && !search.hasFocus()) return false
        val imm = c.getSystemService(InputMethodManager::class.java)
        val token = search.windowToken
        if (token != null) imm?.hideSoftInputFromWindow(token, 0)
        search.clearFocus()
        return visible
    }

    fun focusSearch() {
        enterSearch()
        search.requestFocus()
        val imm = c.getSystemService(InputMethodManager::class.java)
        search.post { imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }
    }

    /** Called after the view is attached. */
    fun onShown() {
        if (!wide) {
            // show the resize handles for a moment so touch users can find them
            handleVisible = true; invalidate()
            postDelayed({ if (!resizing) { handleVisible = false; invalidate() } }, 2500)
        }
        search.requestFocus()
        if (Prefs.bool(c, Prefs.K_DRAWER_IME, false) && c.resources.configuration.keyboard == android.content.res.Configuration.KEYBOARD_NOKEYS) {
            val imm = c.getSystemService(InputMethodManager::class.java)
            search.postDelayed({ imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }, 180)
        }
    }

    private fun onLeftCorner() = !centredMode && layoutDirection == LAYOUT_DIRECTION_RTL

    /** -1 = top left handle, +1 = top right handle, 0 = none. Centred drawers have both, side drawers only the far one. */
    private fun handleSide(x: Float, y: Float): Int {
        if (wide) return 0
        val z = c.dp(48)
        if (y < panel.top || y > panel.top + z) return 0
        val leftOk = centredMode || onLeftCorner()
        val rightOk = centredMode || !onLeftCorner()
        if (leftOk && x >= panel.left && x <= panel.left + z) return -1
        if (rightOk && x <= panel.right && x >= panel.right - z) return 1
        return 0
    }

    private fun inHandleZone(x: Float, y: Float) = handleSide(x, y) != 0
    private var activeSide = 1

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (!wide && (ev.actionMasked == android.view.MotionEvent.ACTION_HOVER_MOVE || ev.actionMasked == android.view.MotionEvent.ACTION_HOVER_ENTER)) {
            val v = inHandleZone(ev.x, ev.y)
            if (v != handleVisible) { handleVisible = v; invalidate() }
        } else if (ev.actionMasked == android.view.MotionEvent.ACTION_HOVER_EXIT && handleVisible && !resizing) {
            handleVisible = false; invalidate()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun resizeTouch(ev: android.view.MotionEvent): Boolean {
        val lp = panel.layoutParams as LayoutParams
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val side = handleSide(ev.x, ev.y)
                if (side == 0) return false
                activeSide = side
                resizing = true; handleVisible = true
                rx0 = ev.rawX; ry0 = ev.rawY; rw0 = lp.width; rh0 = lp.height
                parent?.requestDisallowInterceptTouchEvent(true)
                hideKeyboardIfVisible()
                invalidate()
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!resizing) return false
                val dm = c.resources.displayMetrics
                val dx = ev.rawX - rx0
                val dy = ev.rawY - ry0
                val factor = if (centredMode) 2 else 1
                val w = (rw0 + (if (activeSide < 0) -dx else dx) * factor).toInt().coerceIn(c.dp(280), dm.widthPixels - c.dp(16))
                val maxH = (if (height > 0) height else dm.heightPixels) - lp.bottomMargin - c.dp(8)
                val h = (rh0 - dy).toInt().coerceIn(c.dp(320), maxOf(c.dp(320), maxH))
                if (w != lp.width || h != lp.height) {
                    lp.width = w; lp.height = h
                    panel.layoutParams = lp
                    grid.numColumns = maxOf(3, (w - c.dp(28)) / c.dp(92))
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (!resizing) return false
                resizing = false
                Prefs.setLong(c, "drawerWdp", (lp.width / c.resources.displayMetrics.density).toLong())
                Prefs.setLong(c, "drawerHdp", (lp.height / c.resources.displayMetrics.density).toLong())
                invalidate()
                return true
            }
        }
        return false
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (!handleVisible || wide) return
        handlePaint.color = Colors.accent(c)
        handlePaint.strokeWidth = c.dp(4).toFloat()
        val rad = c.dp(16).toFloat()
        val inset = c.dp(8).toFloat()
        if (centredMode || onLeftCorner()) {
            val cx = panel.left + rad + inset; val cy = panel.top + rad + inset
            canvas.drawArc(android.graphics.RectF(cx - rad, cy - rad, cx + rad, cy + rad), 185f, 80f, false, handlePaint)
        }
        if (centredMode || !onLeftCorner()) {
            val cx = panel.right - rad - inset; val cy = panel.top + rad + inset
            canvas.drawArc(android.graphics.RectF(cx - rad, cy - rad, cx + rad, cy + rad), 275f, 80f, false, handlePaint)
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!wide && (resizing || ev.actionMasked == android.view.MotionEvent.ACTION_DOWN)) {
            if (resizeTouch(ev)) return true
        }
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val loc = IntArray(2)
            search.getLocationOnScreen(loc)
            val inSearch = ev.rawX >= loc[0] && ev.rawX <= loc[0] + search.width &&
                ev.rawY >= loc[1] && ev.rawY <= loc[1] + search.height
            if (!inSearch && hideKeyboardIfVisible()) keyboardDismissedThisGesture = true
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
            post { keyboardDismissedThisGesture = false }
        }
        return handled
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_UP && (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            val imeVisible = try { rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true } catch (_: Exception) { false }
            if (imeVisible) {
                val imm = c.getSystemService(InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(search.windowToken, 0)
                search.clearFocus()
            } else if (moreApps) { moreApps = false; render() }
            else if (searchMode) { searchMode = false; search.setText(""); runSearch("") }
            else onClose()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    private fun launch(a: AppInfo) { Launch.app(c, a.comp); onClose() }

    private fun showAppMenu(a: AppInfo, x: Int, y: Int) {
        menu?.dismiss()
        val pinned = Prefs.list(c, Prefs.K_PINNED).contains(a.comp.flattenToString())
        val m = PopupMenuX(c, this)
        menu = m
        m.show(x, y, listOf(
            MenuItem("Open") { launch(a) },
            MenuItem("App Info") { onClose(); Launch.info(c, a.pkg) },
            MenuItem("Share") { onClose(); Launch.shareApp(c, a.pkg) },
            MenuItem(if (pinned) "Unpin from Taskbar" else "Pin to Taskbar") {
                Prefs.toggleInList(c, Prefs.K_PINNED, a.comp.flattenToString())
            },
            MenuItem("Add to Desktop") {
                val l = Prefs.list(c, Prefs.K_DESKAPPS)
                if (!l.contains(a.comp.flattenToString())) Prefs.setList(c, Prefs.K_DESKAPPS, l + a.comp.flattenToString())
                c.toast("Added to desktop")
            },
            MenuItem("Uninstall") { onClose(); Launch.uninstall(c, a.pkg) }
        ))
    }

    private fun loadShortcuts() {
        bg.execute {
            try {
                val la = c.getSystemService(LauncherApps::class.java)
                if (!la.hasShortcutHostPermission()) return@execute
                val out = ArrayList<ShortcutInfo>()
                val flags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                for (pkg in allApps.map { it.pkg }.distinct()) {
                    try {
                        val q = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(flags)
                        la.getShortcuts(q, Process.myUserHandle())?.let { out.addAll(it) }
                    } catch (_: Exception) {}
                }
                shortcutCache = out
            } catch (_: Exception) {}
        }
    }

    private fun enterSearch() {
        if (searchMode) return
        searchMode = true
        runSearch(search.text?.toString() ?: "")
    }

    private fun render() {
        val showList = (searchMode || activeQuery.isNotEmpty()) && !moreApps
        list.visibility = if (showList) VISIBLE else GONE
        grid.visibility = if (showList || apps.isEmpty()) GONE else VISIBLE
    }

    private fun remember() {
        val q = activeQuery.replace("|", " ").trim()
        if (q.isEmpty()) return
        Prefs.setList(c, Prefs.K_RECENT, (listOf(q) + Prefs.list(c, Prefs.K_RECENT).filter { !it.equals(q, true) }).take(8), false)
    }

    private fun refreshHome() {
        if (searchMode && activeQuery.isEmpty()) { results = homeResults(); listAdapter.notifyDataSetChanged() }
    }

    private fun runSearch(q: String) {
        val query = q.trim()
        if (query != activeQuery) {
            activeQuery = query
            showAllFiles = false
            moreApps = false
        }
        if (query.isNotEmpty()) searchMode = true
        val my = ++gen
        apps = if (query.isEmpty()) allApps else allApps.filter { it.label.contains(query, ignoreCase = true) }
        gridAdapter.notifyDataSetChanged()
        render()
        if (!searchMode && query.isEmpty()) return
        if (query.isEmpty()) {
            results = homeResults()
            listAdapter.notifyDataSetChanged()
            bg.execute {
                val d = Downloads.recent(c)
                val na = Downloads.newApps(c, allApps)
                main.post { if (my == gen) { dlFiles = d; dlApps = na; refreshHome() } }
            }
            return
        }
        bg.execute {
            val r = buildResults(query)
            main.post { if (my == gen) { results = r; listAdapter.notifyDataSetChanged() } }
        }
    }

    private fun homeResults(): List<Result> {
        val out = ArrayList<Result>()
        if (dlFiles.isNotEmpty() || dlApps.isNotEmpty()) out += Result(false, "", custom = { downloadsCard() })
        val rec = Prefs.list(c, Prefs.K_RECENT)
        if (rec.isNotEmpty()) {
            out += Result(true, "Recent")
            for (q in rec.take(4)) out += Result(false, q, custom = { recentRow(q) })
        }
        if (apps.isNotEmpty()) { out += Result(true, "Apps"); out += Result(false, "", custom = { appsBlock() }) }
        return out
    }

    private fun recentRow(q: String): View {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(c.dp(12), c.dp(10), c.dp(12), c.dp(10))
        val ic = TextView(c); ic.text = "\u21BA"; ic.textSize = 18f; ic.alpha = 0.6f; ic.setTextColor(Colors.text(c))
        row.addView(ic, LinearLayout.LayoutParams(c.dp(28), WRAP))
        val t = TextView(c); t.text = q; t.textSize = 15f; t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END; t.setTextColor(Colors.text(c))
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).also { it.marginStart = c.dp(10) })
        row.setOnClickListener { search.setText(q); search.setSelection(q.length) }
        row.setOnLongClickListener {
            Prefs.setList(c, Prefs.K_RECENT, Prefs.list(c, Prefs.K_RECENT).filter { it != q }, false)
            refreshHome(); true
        }
        return row
    }

    /** Apps in two rows of four, plus a "More apps" link when there are more. */
    private fun appsBlock(): View {
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        val shown = apps.take(8)
        for (r in 0 until 2) {
            if (r * 4 >= shown.size) break
            val row = LinearLayout(c)
            row.orientation = LinearLayout.HORIZONTAL
            for (i in 0 until 4) {
                val idx = r * 4 + i
                if (idx < shown.size) {
                    val a = shown[idx]
                    val cell = AppCell(c)
                    cell.bind(a)
                    cell.setOnClickListener { remember(); launch(a) }
                    cell.setSecondaryClick { x, y -> showAppMenu(a, x, y) }
                    cell.setOnLongClickListener { v ->
                        val loc = IntArray(2); v.getLocationOnScreen(loc)
                        showAppMenu(a, loc[0] + v.width / 2, loc[1] + v.height / 2); true
                    }
                    row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
                } else row.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            }
            col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        if (apps.size > 8) {
            val more = TextView(c)
            more.text = UiText.text(c, "More apps")
            more.textSize = 14f
            more.gravity = Gravity.CENTER
            more.setTextColor(Colors.accent(c))
            more.setPadding(0, c.dp(10), 0, c.dp(10))
            more.setOnClickListener { moreApps = true; render() }
            col.addView(more, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        return col
    }

    private fun downloadsCard(): View {
        val files = if (dlExpanded) dlFiles else dlFiles.take(5)
        val card = LinearLayout(c)
        card.orientation = LinearLayout.VERTICAL
        card.background = Colors.rounded(Colors.surfaceHigh(c), c.dp(24).toFloat())
        card.setPadding(c.dp(14), c.dp(8), c.dp(14), c.dp(4))
        UiText.applyDirection(card, c)

        val head = LinearLayout(c)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(c)
        title.text = UiText.text(c, if (dlFiles.isNotEmpty()) "Files downloaded today" else "Apps installed today")
        title.textSize = 16f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.setTextColor(Colors.text(c))
        head.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        val x = TextView(c)
        x.text = "\u2715"
        x.textSize = 18f
        x.gravity = Gravity.CENTER
        x.setTextColor(Colors.text(c))
        x.setOnClickListener { Downloads.dismiss(c); dlFiles = emptyList(); dlApps = emptyList(); refreshHome() }
        head.addView(x, LinearLayout.LayoutParams(c.dp(40), c.dp(40)))
        card.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        val pill = TextView(c)
        pill.text = UiText.text(c, "Create shortcuts for all files")
        pill.textSize = 14f
        pill.gravity = Gravity.CENTER
        pill.setTextColor(Colors.iconFg(c))
        pill.background = Colors.rounded(Colors.iconBg(c), c.dp(20).toFloat())
        pill.setPadding(c.dp(16), c.dp(8), c.dp(16), c.dp(8))
        pill.setOnClickListener {
            var ok = 0
            for (e in dlFiles) if (Downloads.createShortcut(c, e.file)) ok++
            c.toast(UiText.text(c, if (ok > 0) "Shortcuts created on Desktop" else "Could not create shortcut"))
            if (ok > 0) { dlFiles = emptyList(); refreshHome() }
        }
        if (dlFiles.isNotEmpty()) card.addView(pill, LinearLayout.LayoutParams(WRAP, WRAP).also { it.topMargin = c.dp(4); it.bottomMargin = c.dp(6) })

        // Files and today's new apps share one scroll area that is as tall as five file rows.
        val inner = LinearLayout(c)
        inner.orientation = LinearLayout.VERTICAL
        val scroll = CappedScroll(c, if (dlExpanded) Int.MAX_VALUE else c.dp(52) * 5)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(inner, ViewGroup.LayoutParams(MATCH, WRAP))
        card.addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP))

        val ext = android.os.Environment.getExternalStorageDirectory().absolutePath
        for (e in files) {
            val f = e.file
            val row = LinearLayout(c)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, c.dp(8), 0, c.dp(8))
            val iv = android.widget.ImageView(c)
            Thumbs.bind(iv, f, c.dp(36))
            row.addView(iv, LinearLayout.LayoutParams(c.dp(36), c.dp(36)).also { it.marginEnd = c.dp(12) })
            val col = LinearLayout(c)
            col.orientation = LinearLayout.VERTICAL
            val n = TextView(c); n.text = f.name; n.textSize = 15f; n.maxLines = 1
            n.ellipsize = android.text.TextUtils.TruncateAt.END; n.setTextColor(Colors.text(c))
            col.addView(n)
            val dir = (f.parent ?: "").removePrefix(ext).trimStart('/')
            val sub = LinearLayout(c)
            sub.orientation = LinearLayout.HORIZONTAL
            val s = TextView(c); s.text = dir; s.textSize = 12f; s.alpha = 0.6f
            s.maxLines = 1; s.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; s.setTextColor(Colors.text(c))
            sub.addView(s, LinearLayout.LayoutParams(0, WRAP, 1f))
            val tm = TextView(c); tm.text = " \u2022 " + Downloads.ago(c, e.time); tm.textSize = 12f; tm.alpha = 0.6f
            tm.maxLines = 1; tm.setTextColor(Colors.text(c))
            sub.addView(tm, LinearLayout.LayoutParams(WRAP, WRAP))
            col.addView(sub, LinearLayout.LayoutParams(MATCH, WRAP))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.setOnClickListener { Files.open(c, f); onClose() }
            row.setOnLongClickListener { v ->
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                PopupMenuX(c, this).show(loc[0], loc[1] + v.height, listOf(
                    MenuItem("Open") { Files.open(c, f); onClose() },
                    MenuItem("Create shortcut on Desktop") {
                        if (Downloads.createShortcut(c, f)) {
                            c.toast(UiText.text(c, "Shortcut created on Desktop"))
                            dlFiles = dlFiles.filter { it.file != f }; refreshHome()
                        } else c.toast(UiText.text(c, "Could not create shortcut"))
                    }
                ))
                true
            }
            inner.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        if (dlApps.isNotEmpty()) {
            if (dlFiles.isNotEmpty()) {
                val h = TextView(c)
                h.text = UiText.text(c, "Apps installed today")
                h.textSize = 12f
                h.setTextColor(Colors.accent(c))
                h.setPadding(0, c.dp(10), 0, c.dp(2))
                inner.addView(h)
            }
            val shown = if (dlExpanded) dlApps else dlApps.take(8)
            for (r in 0 until (shown.size + 3) / 4) {
                val row = LinearLayout(c)
                row.orientation = LinearLayout.HORIZONTAL
                for (i in 0 until 4) {
                    val idx = r * 4 + i
                    if (idx < shown.size) {
                        val a = shown[idx]
                        val cell = AppCell(c)
                        cell.bind(a)
                        cell.setOnClickListener { launch(a) }
                        cell.setOnLongClickListener { v ->
                            val loc = IntArray(2); v.getLocationOnScreen(loc)
                            showAppMenu(a, loc[0] + v.width / 2, loc[1] + v.height / 2); true
                        }
                        row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
                    } else row.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
                }
                inner.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }

        if (dlFiles.size > 5 || dlApps.size > 8) {
            val all = TextView(c)
            all.text = UiText.text(c, if (dlExpanded) "Show less" else "All downloads")
            all.textSize = 14f
            all.gravity = Gravity.CENTER
            all.setTextColor(Colors.accent(c))
            all.setPadding(0, c.dp(10), 0, c.dp(10))
            all.setOnClickListener { dlExpanded = !dlExpanded; refreshHome() }
            card.addView(all, LinearLayout.LayoutParams(MATCH, WRAP))
        } else card.setPadding(c.dp(14), c.dp(8), c.dp(14), c.dp(8))

        val wrap = FrameLayout(c)
        wrap.setPadding(0, c.dp(4), 0, c.dp(8))
        wrap.addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
        return wrap
    }

    private fun buildResults(q: String): List<Result> {
        val ql = q.lowercase()
        val out = ArrayList<Result>()
        if (apps.isNotEmpty()) { out += Result(true, "Apps"); out += Result(false, "", custom = { appsBlock() }) }

        val setHits = SETTINGS.filter { it.first.lowercase().contains(ql) }
        if (setHits.isNotEmpty()) {
            out += Result(true, "Settings")
            for ((name, action) in setHits) out += Result(false, name, "System settings", action = {
                if (action == "wallpaper") Launch.wallpapers(c) else Launch.start(c, Intent(action))
            })
        }

        val sc = shortcutCache.filter {
            (it.shortLabel?.toString() ?: "").lowercase().contains(ql) ||
                (it.longLabel?.toString() ?: "").lowercase().contains(ql)
        }.take(6)
        if (sc.isNotEmpty()) {
            out += Result(true, "App shortcuts")
            val la = c.getSystemService(LauncherApps::class.java)
            for (s in sc) out += Result(false, s.shortLabel?.toString() ?: s.id, AppRepo.byPkg(c, s.`package`)?.label ?: s.`package`, action = {
                try { la.startShortcut(s, null, null) } catch (_: Exception) { c.toast("Cannot open shortcut") }
            })
        }

        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            val cs = ArrayList<Result>()
            try {
                c.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                    "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%$q%"),
                    "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
                )?.use {
                    while (it.moveToNext() && cs.size < 6) {
                        val uri = ContactsContract.Contacts.getLookupUri(it.getLong(0), it.getString(1))
                        cs += Result(false, it.getString(2) ?: "Contact", "Contact", action = {
                            Launch.start(c, Intent(Intent.ACTION_VIEW, uri))
                        })
                    }
                }
            } catch (_: Exception) {}
            if (cs.isNotEmpty()) { out += Result(true, "Contacts"); out += cs }
        }

        val fs = searchFiles(q)
        if (fs.isNotEmpty()) {
            out += Result(true, "Files")
            val visibleFiles = if (showAllFiles) fs else fs.take(5)
            for (f in visibleFiles) out += Result(false, f.name, f.parent ?: "", Icons.file(f), f, action = {
                if (f.extension == "desktoplink") {
                    try {
                        val target = f.readLines().getOrNull(1)?.let { File(it) }
                        if (target != null && target.exists()) Files.open(c, target) else c.toast("Shortcut target not found")
                    } catch (_: Exception) { c.toast("Invalid shortcut") }
                } else Files.open(c, f)
            })
            if (fs.size > 5) {
                val toggleLabel = if (showAllFiles) "Hide all files" else "See all files"
                val toggleSub = if (showAllFiles) "Show only the first 5 results" else "Show all matching results"
                out += Result(false, toggleLabel, toggleSub, action = {
                    showAllFiles = !showAllFiles
                    runSearch(search.text?.toString() ?: "")
                }, keepOpen = true)
            }
        }

        out += Result(true, "In apps")
        for ((label, intentFn) in inAppSearches(q)) out += Result(false, label, "", action = { Launch.start(c, intentFn()) })

        out += Result(true, "Web")
        out += Result(false, "Search the web for “$q”", "", action = {
            val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) {
                Launch.start(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            }
        })
        return out
    }

    private fun searchFiles(q: String): List<File> {
        val res = ArrayList<File>()
        try {
            val args = Bundle()
            args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            args.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%$q%"))
            args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")
            c.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns.DATA), args, null
            )?.use {
                while (it.moveToNext()) {
                    val p = it.getString(0) ?: continue
                    val f = File(p)
                    if (f.exists()) res += f
                }
            }
        } catch (_: Exception) {}
        // The Desktop folder itself (also works without media index).
        try {
            Files.root(c).listFiles()?.filter { it.name.lowercase().contains(q.lowercase()) && res.none { r -> r == it } }
                ?.take(6)?.let { res.addAll(0, it) }
        } catch (_: Exception) {}
        return res.distinctBy { it.absolutePath }
    }

    private fun inAppSearches(q: String): List<Pair<String, () -> Intent>> {
        val out = ArrayList<Pair<String, () -> Intent>>()
        fun has(i: Intent) = c.packageManager.resolveActivity(i, 0) != null
        val play = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q)))
        if (has(play)) out += "Search “$q” in Play Store" to { play }
        val yt = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(SearchManager.QUERY, q)
        if (has(yt)) out += "Search “$q” in YouTube" to { yt }
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q)))
        if (has(maps)) out += "Search “$q” in Maps" to { maps }
        return out
    }

    /** ScrollView limited to a maximum height; it keeps touches from the outer list while it can still scroll. */
    private class CappedScroll(ctx: Context, private val maxH: Int) : android.widget.ScrollView(ctx) {
        private var downY = 0f
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, if (maxH == Int.MAX_VALUE) h else MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downY = ev.y; parent?.requestDisallowInterceptTouchEvent(canScrollVertically(1) || canScrollVertically(-1)) }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = ev.y - downY
                    val can = if (dy > 0) canScrollVertically(-1) else canScrollVertically(1)
                    parent?.requestDisallowInterceptTouchEvent(can)
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    private inner class AppCell(ctx: Context) : LinearLayout(ctx) {
        private val icon = BadgedIcon(ctx)
        private val label = TextView(ctx)
        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ctx.dp(4), 0, ctx.dp(4))
            addView(icon, LayoutParams(ctx.dp(48), ctx.dp(48)))
            label.textSize = 12f
            label.maxLines = 2
            label.gravity = Gravity.CENTER
            label.ellipsize = android.text.TextUtils.TruncateAt.END
            label.setTextColor(Colors.text(ctx))
            addView(label, LayoutParams(MATCH, WRAP))
        }
        fun bind(a: AppInfo) {
            icon.setImageDrawable(Icons.app(context, a.comp, themed))
            icon.dot = dots && NotifState.has(a.pkg)
            label.text = a.label
        }
    }

    companion object {
        val SETTINGS = listOf(
            "Wi‑Fi" to "android.settings.WIFI_SETTINGS",
            "Bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
            "Display" to "android.settings.DISPLAY_SETTINGS",
            "Sound" to "android.settings.SOUND_SETTINGS",
            "Battery" to "android.settings.BATTERY_SAVER_SETTINGS",
            "Apps" to "android.settings.APPLICATION_SETTINGS",
            "Storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
            "Network & internet" to "android.settings.WIRELESS_SETTINGS",
            "Airplane mode" to "android.settings.AIRPLANE_MODE_SETTINGS",
            "Location" to "android.settings.LOCATION_SOURCE_SETTINGS",
            "Security" to "android.settings.SECURITY_SETTINGS",
            "Accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
            "Date & time" to "android.settings.DATE_SETTINGS",
            "Language & input" to "android.settings.LOCALE_SETTINGS",
            "Default apps" to "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
            "Developer options" to "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
            "Notifications" to "android.settings.NOTIFICATION_SETTINGS",
            "Privacy" to "android.settings.PRIVACY_SETTINGS",
            "About device" to "android.settings.DEVICE_INFO_SETTINGS",
            "Wallpaper & style" to "wallpaper",
            "Settings" to "android.settings.SETTINGS"
        )
    }
}
