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
    private var padLeft = 0
    private var padRight = 0
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
            val bars = Insets.of(ins)
            padTop = bars.top
            padBottom = bars.bottom
            padLeft = bars.left
            padRight = bars.right
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
        MenuArea.bottomReserve = bottom
        desktop.setPadding(padLeft, padTop, padRight, bottom)
        dock?.let {
            val lp = it.layoutParams as FrameLayout.LayoutParams
            lp.height = dockH
            lp.leftMargin = padLeft + dp(16); lp.rightMargin = padRight + dp(16); lp.bottomMargin = padBottom + dp(8)
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
        desktop.refreshTrashIcon(true)
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
        else { closeWidgetSheet(); closeDrawer(); TaskbarService.instance?.closeDrawer(); desktop.clearSelection(); desktop.closeFolder() }
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        closeDrawer()
        applyMode()
        Bus.fire()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (sheet != null) closeWidgetSheet() else if (drawer != null) closeDrawer() else desktop.closeFolder() }

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

    private var sheet: WidgetSheet? = null

    fun pickWidget(x: Int, y: Int) {
        closeWidgetSheet()
        val pm = packageManager
        val providers = try { awm.installedProviders.sortedBy { it.loadLabel(pm).toString().lowercase() } }
                  catch (_: Exception) { emptyList() }
        val dens = resources.displayMetrics.density
        val ownName = try { pm.getApplicationLabel(applicationInfo).toString() } catch (_: Exception) { "Desktop Launcher" }
        val ownIcon = try { pm.getApplicationIcon(packageName) } catch (_: Exception) { pm.defaultActivityIcon }
        // Built-in launcher widgets come first, then the widgets of installed apps.
        val all = ArrayList<WidgetItem>()
        for (d in InternalWidgets.all) {
            all += WidgetItem(UiText.text(this, d.title), packageName, ownName, ownIcon, d.minWdp, d.minHdp,
                { InternalWidgets.preview(this, d.type) }) { px, py -> desktop.addInternal(d.type, px, py) }
        }
        for (info in providers) {
            all += WidgetItem(try { info.loadLabel(pm).toString() } catch (_: Exception) { info.provider.className },
                info.provider.packageName,
                try { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() } catch (_: Exception) { info.provider.packageName },
                try { pm.getApplicationIcon(info.provider.packageName) } catch (_: Exception) { pm.defaultActivityIcon },
                (maxOf(info.minWidth, 1) / dens).toInt(), (maxOf(info.minHeight, 1) / dens).toInt(),
                { try { info.loadPreviewImage(this, resources.displayMetrics.densityDpi) } catch (_: Exception) { null } }) { px, py ->
                pendX = px; pendY = py
                startAdd(info)
            }
        }
        val bottom = if (!Modes.tablet(this) && barOn()) padBottom + dockH + dp(16)
            else if (Modes.tablet(this) && barOn() && TaskbarService.instance != null) maxOf(padBottom, dp(Prefs.tbDp(this))) else padBottom
        val sh = WidgetSheet(this, all, padTop, bottom, { w, h -> desktop.spansFor(w, h) }) { closeWidgetSheet() }
        root.addView(sh, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet = sh
    }

    fun closeWidgetSheet() {
        sheet?.let { root.removeView(it) }
        sheet = null
    }

    /** The desktop reports the end of a widget drag: bring the sheet back if nothing was dropped. */
    fun onWidgetDragEnded(dropped: Boolean) {
        if (dropped) closeWidgetSheet() else sheet?.restoreAfterDrag()
    }

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
