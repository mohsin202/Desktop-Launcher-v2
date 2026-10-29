package com.android.desktoplauncher

import android.app.Activity
import android.app.Dialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher

class LauncherActivity : Activity() {
    companion object {
        const val ACTION_TOGGLE_DRAWER = "com.android.desktoplauncher.TOGGLE_DRAWER"
        private const val REQ_BIND = 11
        private const val REQ_CONFIG = 12
        private const val HOST_ID = 1024
    }

    private lateinit var root: FrameLayout
    private lateinit var desktop: DesktopView
    private lateinit var banner: TextView
    lateinit var host: AppWidgetHost
    lateinit var awm: AppWidgetManager
    private var drawer: DrawerView? = null
    private var dock: TaskbarView? = null
    private var dockH = 0
    private var padTop = 0
    private var padBottom = 0
    private var pendingId = -1
    private var pendingInfo: AppWidgetProviderInfo? = null
    private var pendX = 300
    private var pendY = 300
    private val busListener: () -> Unit = { applyMode() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        swipe = makeSwipe()
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        host = AppWidgetHost(this, HOST_ID)
        awm = AppWidgetManager.getInstance(this)

        root = FrameLayout(this)
        desktop = DesktopView(this)
        root.addView(desktop, FrameLayout.LayoutParams(MATCH, MATCH))
        banner = TextView(this)
        banner.setTextColor(Color.WHITE)
        banner.textSize = 13f
        banner.setPadding(dp(14), dp(8), dp(14), dp(8))
        banner.background = Colors.rounded(0xCC222222.toInt(), dp(18).toFloat())
        banner.visibility = android.view.View.GONE
        root.addView(banner, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).also {
            it.topMargin = dp(48); it.rightMargin = dp(12)
        })
        UiText.applyDirection(root, this)
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars())
            padTop = bars.top
            padBottom = bars.bottom
            applyPadding()
            ins
        }
        Bus.add(busListener)
    }

    override fun onDestroy() {
        Bus.remove(busListener)
        super.onDestroy()
    }

    private val dockHost = object : TaskbarHost {
        override val running: Collection<String> = emptyList()
        override val focused: String? = null
        override fun global(action: Int) {}
        override fun hideRunning(pkg: String) {}
        override fun toggleDrawer() { this@LauncherActivity.toggleDrawer() }
        override fun openSearch() {
            if (drawer == null) toggleDrawer()
            drawer?.post { drawer?.focusSearch() }
        }
        override val imeShown = false
        override fun pickIme() {}
    }

    private fun barOn() = Prefs.bool(this, Prefs.K_TASKBAR, true)

    /** Phone (<600dp): normal dock on the home screen. Tablet (>=600dp): dock merges into the taskbar overlay. */
    private fun applyMode() {
        if (::root.isInitialized) UiText.applyDirection(root, this)
        val phone = !Modes.tablet(this)
        if (phone && barOn()) {
            dockH = dp(Prefs.tbDp(this)) + dp(4)
            if (dock == null) {
                val d = TaskbarView(this, dockHost, false, true)
                root.addView(d, FrameLayout.LayoutParams(MATCH, dockH, Gravity.BOTTOM))
                dock = d
            } else dock?.refresh()
        } else {
            dock?.let { root.removeView(it) }
            dock = null
            dockH = 0
        }
        applyPadding()
        desktop.reload()
    }

    private fun applyPadding() {
        val phone = !Modes.tablet(this)
        val bottom = when {
            phone && barOn() -> padBottom + dockH + dp(16)
            !phone && barOn() && TaskbarService.instance != null -> maxOf(padBottom, dp(Prefs.tbDp(this)))
            else -> padBottom
        }
        desktop.setPadding(0, padTop, 0, bottom)
        dock?.let {
            val lp = it.layoutParams as FrameLayout.LayoutParams
            lp.height = dockH
            lp.leftMargin = dp(16); lp.rightMargin = dp(16); lp.bottomMargin = padBottom + dp(8)
            it.layoutParams = lp
        }
    }

    override fun onStart() {
        super.onStart()
        try { host.startListening() } catch (_: Exception) {}
    }

    override fun onStop() {
        try { host.stopListening() } catch (_: Exception) {}
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        applyMode()
        updateBanner()
    }

    private fun updateBanner() {
        if (!Environment.isExternalStorageManager()) {
            banner.text = "Tap: allow file access for the Desktop folder"
            banner.setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            banner.visibility = android.view.View.VISIBLE
        } else if (Modes.tablet(this) && barOn() && TaskbarService.instance == null) {
            banner.text = "Tap: turn on Accessibility to show the taskbar"
            banner.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            banner.visibility = android.view.View.VISIBLE
        } else banner.visibility = android.view.View.GONE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_TOGGLE_DRAWER) toggleDrawer()
        else { closeDrawer(); TaskbarService.instance?.closeDrawer(); desktop.clearSelection() }
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        closeDrawer()
        applyMode()
        Bus.fire()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (drawer != null) closeDrawer() }

    fun toggleDrawer() {
        val svc = TaskbarService.instance
        if (svc != null && Modes.tablet(this) && barOn()) { svc.toggleDrawer(); return }
        if (drawer != null) closeDrawer() else {
            val bm = if (!Modes.tablet(this) && barOn()) padBottom + dockH + dp(16) else padBottom + dp(8)
            val d = DrawerView(this, bm, !Modes.tablet(this)) { closeDrawer() }
            root.addView(d, FrameLayout.LayoutParams(MATCH, MATCH))
            drawer = d
            d.post { d.onShown() }
        }
    }

    fun desktopOpenFile(file: java.io.File) { desktop.openFile(file) }

    fun closeDrawer() {
        drawer?.let { root.removeView(it) }
        drawer = null
        TaskbarService.instance?.closeDrawer()
    }

    // ------------------------------------------------------------ widgets

    fun pickWidget(x: Int, y: Int) {
        pendX = x; pendY = y
        val pm = packageManager
        val providers = try { awm.installedProviders.sortedBy { it.loadLabel(pm).toString().lowercase() } }
                  catch (_: Exception) { emptyList() }
        val dens = resources.displayMetrics.density
        val ownName = try { pm.getApplicationLabel(applicationInfo).toString() } catch (_: Exception) { "Desktop Launcher" }
        val ownIcon = try { pm.getApplicationIcon(packageName) } catch (_: Exception) { pm.defaultActivityIcon }
        // Built-in launcher widgets come first, then the widgets of installed apps.
        val all = ArrayList<PickItem>()
        for (d in InternalWidgets.all) {
            all += PickItem(UiText.text(this, d.title), ownName, ownIcon, d.minWdp, d.minHdp,
                { InternalWidgets.preview(this, d.type) }) { desktop.addInternal(d.type, pendX, pendY) }
        }
        for (info in providers) {
            all += PickItem(try { info.loadLabel(pm).toString() } catch (_: Exception) { info.provider.className },
                try { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() } catch (_: Exception) { info.provider.packageName },
                try { pm.getApplicationIcon(info.provider.packageName) } catch (_: Exception) { pm.defaultActivityIcon },
                (maxOf(info.minWidth, 1) / dens).toInt(), (maxOf(info.minHeight, 1) / dens).toInt(),
                { try { info.loadPreviewImage(this, resources.displayMetrics.densityDpi) } catch (_: Exception) { null } }) { startAdd(info) }
        }

        var selected = all.first()
        val dialog = Dialog(this)
        val outer = FrameLayout(this).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.TRANSPARENT)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Colors.rounded(Colors.surfaceSolid(this@LauncherActivity), dp(30).toFloat())
            setPadding(dp(18), dp(18), dp(18), dp(12))
        }
        outer.addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))

        val search = EditText(this).apply {
            hint = UiText.text(this@LauncherActivity, "Search")
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 16f
            setTextColor(Colors.text(this@LauncherActivity))
            setHintTextColor(Colors.text(this@LauncherActivity) and 0x88FFFFFF.toInt())
            background = Colors.rounded(Colors.surfaceHigh(this@LauncherActivity), dp(28).toFloat())
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        panel.addView(search, LinearLayout.LayoutParams(MATCH, dp(52)))

        val selectedTitle = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Colors.text(this@LauncherActivity))
            setPadding(dp(4), dp(12), dp(4), dp(2))
        }
        panel.addView(selectedTitle, LinearLayout.LayoutParams(MATCH, WRAP))
        val selectedApp = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            alpha = 0.72f
            setTextColor(Colors.text(this@LauncherActivity))
        }
        panel.addView(selectedApp, LinearLayout.LayoutParams(MATCH, WRAP))
        val preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        panel.addView(preview, LinearLayout.LayoutParams(MATCH, dp(150)))
        val sizeLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            alpha = 0.7f
            setTextColor(Colors.text(this@LauncherActivity))
            setPadding(0, 0, 0, dp(8))
        }
        panel.addView(sizeLabel, LinearLayout.LayoutParams(MATCH, WRAP))
        val include = Button(this).apply {
            text = UiText.text(this@LauncherActivity, "Include")
            isAllCaps = false
            background = Colors.rounded(Colors.taskbar(this@LauncherActivity), dp(30).toFloat())
            setTextColor(Colors.text(this@LauncherActivity))
        }
        panel.addView(include, LinearLayout.LayoutParams(WRAP, dp(48)).also { it.gravity = Gravity.CENTER; it.bottomMargin = dp(10) })
        val divider = android.view.View(this).apply { setBackgroundColor(Colors.text(this@LauncherActivity) and 0x22000000) }
        panel.addView(divider, LinearLayout.LayoutParams(MATCH, dp(1)))
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows, FrameLayout.LayoutParams(MATCH, WRAP))
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val cancel = TextView(this).apply {
            text = UiText.text(this@LauncherActivity, "Cancel")
            textSize = 15f
            setTextColor(Colors.accent(this@LauncherActivity))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(4))
            setOnClickListener { dialog.dismiss() }
        }
        panel.addView(cancel, LinearLayout.LayoutParams(MATCH, dp(42)))

        fun refreshSelected() {
            selectedTitle.text = selected.label
            selectedApp.text = selected.appName
            // Size in home screen grid cells (widgets always snap to the grid).
            val sp = desktop.spansFor(selected.minWdp, selected.minHdp)
            sizeLabel.text = "${sp[0]} × ${sp[1]}"
            preview.setImageDrawable(selected.preview() ?: selected.icon)
        }

        fun renderRows(query: String) {
            rows.removeAllViews()
            val filtered = all.filter { info ->
                info.label.contains(query, true) || info.appName.contains(query, true)
            }
            for (info in filtered) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = if (info == selected) Colors.rounded(Colors.surfaceHigh(this@LauncherActivity), dp(14).toFloat()) else ColorDrawable(Color.TRANSPARENT)
                    isClickable = true
                    isFocusable = true
                }
                val icon = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(info.icon)
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)).also { it.marginEnd = dp(12) })
                val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val title = TextView(this).apply {
                    text = info.label
                    textSize = 16f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Colors.text(this@LauncherActivity))
                }
                val subtitle = TextView(this).apply {
                    text = info.appName
                    textSize = 12f
                    alpha = 0.7f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Colors.text(this@LauncherActivity))
                }
                labels.addView(title); labels.addView(subtitle)
                row.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.setOnClickListener { selected = info; refreshSelected(); renderRows(search.text?.toString() ?: "") }
                rows.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = dp(3) })
            }
            if (filtered.isEmpty()) {
                rows.addView(TextView(this).apply {
                    text = UiText.text(this@LauncherActivity, "No widgets found")
                    setTextColor(Colors.text(this@LauncherActivity)); setPadding(dp(12), dp(18), dp(12), dp(18))
                })
            }
        }

        include.setOnClickListener { dialog.dismiss(); selected.add() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderRows(s?.toString() ?: "") }
            override fun afterTextChanged(s: Editable?) {}
        })
        refreshSelected()
        renderRows("")
        UiText.applyDirection(panel, this)
        dialog.setContentView(outer)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
    }

    private class PickItem(
        val label: String, val appName: String, val icon: android.graphics.drawable.Drawable?,
        val minWdp: Int, val minHdp: Int, val preview: () -> android.graphics.drawable.Drawable?, val add: () -> Unit
    )

    /** Search bar widget: opens the app drawer with the search field focused. */
    fun openSearchFromWidget() {
        val svc = TaskbarService.instance
        if (svc != null && Modes.tablet(this) && barOn()) svc.openSearch() else dockHost.openSearch()
    }

    private fun startAdd(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        if (awm.bindAppWidgetIdIfAllowed(id, info.provider)) configureOrFinish(id, info)
        else {
            pendingId = id; pendingInfo = info
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            try { startActivityForResult(i, REQ_BIND) } catch (_: Exception) { host.deleteAppWidgetId(id); toast("Cannot bind widget") }
        }
    }

    private fun configureOrFinish(id: Int, info: AppWidgetProviderInfo) {
        pendingId = id; pendingInfo = info
        if (info.configure != null) {
            try { host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null) }
            catch (_: Exception) { desktop.addWidget(id, info, pendX, pendY) }
        } else desktop.addWidget(id, info, pendX, pendY)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val info = pendingInfo
        when (req) {
            REQ_BIND -> if (res == RESULT_OK && info != null) configureOrFinish(pendingId, info)
                        else if (pendingId != -1) host.deleteAppWidgetId(pendingId)
            REQ_CONFIG -> if (res == RESULT_OK && info != null) desktop.addWidget(pendingId, info, pendX, pendY)
                          else if (pendingId != -1) host.deleteAppWidgetId(pendingId)
        }
    }

    // Must NOT be created as a field initializer: the Activity has no Context yet at construction time.
    private lateinit var swipe: android.view.GestureDetector
    private var drawerWasAtTopOnTouchDown = false
    private fun makeSwipe() = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // Only allow a downward dismissal if the drawer was already at its top edge
            // when this gesture began. Scrolling through the app list must never close it.
            drawerWasAtTopOnTouchDown = drawer?.isContentAtTopForSwipeDismiss() == true
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null || Modes.tablet(this@LauncherActivity)) return false
            val dy = e1.y - e2.y
            val verticalSwipe = Math.abs(dy) > dp(90) && Math.abs(vy) > 900f && Math.abs(vy) > Math.abs(vx)
            if (!verticalSwipe) return false

            // Swipe up on the desktop opens the app drawer.
            if (drawer == null && dy > 0 && vy < -900f) {
                root.post { if (drawer == null) toggleDrawer() }
                return true
            }
            // Swipe down closes it only when the content was already at the top before the gesture.
            if (drawer != null && dy < 0 && vy > 900f && drawerWasAtTopOnTouchDown) {
                root.post { if (drawer != null && drawer?.isContentAtTopForSwipeDismiss() == true) closeDrawer() }
                return true
            }
            return false
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::swipe.isInitialized) swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Windows key / Meta opens the drawer.
        if (e.keyCode == KeyEvent.KEYCODE_META_LEFT || e.keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            if (e.action == KeyEvent.ACTION_UP) toggleDrawer()
            return true
        }
        return super.dispatchKeyEvent(e)
    }
}
