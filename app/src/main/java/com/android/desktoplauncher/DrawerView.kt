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
    val keepOpen: Boolean = false
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
        override fun isEnabled(p: Int) = !results[p].header
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val r = results[p]
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
                    iv.setImageDrawable(r.icon)
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
        val pw = if (wide) dm.widthPixels - c.dp(16) else minOf(c.dp(440), dm.widthPixels - c.dp(16))
        val ph = if (wide) (dm.heightPixels * 0.86f).toInt() else minOf(c.dp(580), (dm.heightPixels * 0.78f).toInt())
        panel.orientation = LinearLayout.VERTICAL
        panel.background = Colors.rounded(Colors.surface(c), c.dp(28).toFloat())
        panel.elevation = c.dp(10).toFloat()
        panel.setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(10))
        panel.isClickable = true
        // Portrait (and phone mode): centred. Landscape desktop: starts near the drawer button.
        val portrait = dm.heightPixels > dm.widthPixels
        val centred = wide || portrait
        val lp = LayoutParams(pw, ph, if (centred) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM or Gravity.START)
        if (!centred) lp.marginStart = c.dp(8)
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
        list.setOnItemClickListener { _, _, p, _ -> results[p].let { if (!it.header) { it.action(); if (!it.keepOpen) onClose() } } }
        list.setOnItemLongClickListener { _, v, p, _ ->
            val r = results.getOrNull(p) ?: return@setOnItemLongClickListener false
            if (r.header) return@setOnItemLongClickListener true
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

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) { runSearch(s?.toString() ?: "") }
        })
        search.setOnEditorActionListener { _, _, _ ->
            results.firstOrNull { !it.header }?.let { it.action(); onClose() }
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
        search.requestFocus()
        val imm = c.getSystemService(InputMethodManager::class.java)
        search.post { imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }
    }

    /** Called after the view is attached. */
    fun onShown() {
        search.requestFocus()
        if (Prefs.bool(c, Prefs.K_DRAWER_IME, false) && c.resources.configuration.keyboard == android.content.res.Configuration.KEYBOARD_NOKEYS) {
            val imm = c.getSystemService(InputMethodManager::class.java)
            search.postDelayed({ imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }, 180)
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
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
            } else onClose()
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

    private fun runSearch(q: String) {
        val query = q.trim()
        if (query != activeQuery) {
            activeQuery = query
            showAllFiles = false
        }
        val my = ++gen
        if (query.isEmpty()) {
            apps = allApps
            gridAdapter.notifyDataSetChanged()
            list.visibility = GONE
            grid.visibility = VISIBLE
            return
        }
        apps = allApps.filter { it.label.contains(query, ignoreCase = true) }
        gridAdapter.notifyDataSetChanged()
        grid.visibility = if (apps.isEmpty()) GONE else VISIBLE
        list.visibility = VISIBLE
        bg.execute {
            val r = buildResults(query)
            main.post { if (my == gen) { results = r; listAdapter.notifyDataSetChanged() } }
        }
    }

    private fun buildResults(q: String): List<Result> {
        val ql = q.lowercase()
        val out = ArrayList<Result>()

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
