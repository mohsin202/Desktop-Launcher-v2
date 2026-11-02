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
        const val ACTION_CATEGORIES = "com.android.desktoplauncher.CATEGORIES"
        private const val REQ_BIND = 11
        private const val REQ_CONFIG = 12
        private const val HOST_ID = 1024
        private var live: java.lang.ref.WeakReference<LauncherActivity>? = null
        /** The taskbar's drawer is its own window: it asks the launcher screen behind it to blur. */
        fun blurFromOverlay(on: Boolean) { live?.get()?.let { it.extBlur = on; it.syncBlur() } }
    }
    private var extBlur = false

    private lateinit var root: FrameLayout
    private lateinit var desktop: DesktopView
    private lateinit var banner: TextView
    lateinit var host: AppWidgetHost
    lateinit var awm: AppWidgetManager
    private var drawer: DrawerView? = null
    private var dock: TaskbarView? = null
    private var catBar: CatBar? = null
    private var catMenu: CatMenu? = null
    private var catMargin = 0
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
        catBar = CatBar(this, desktop)
        root.addView(catBar, FrameLayout.LayoutParams(WRAP, dp(24), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
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
        live = java.lang.ref.WeakReference(this)
    }

    override fun onDestroy() {
        if (live?.get() === this) live = null
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
        catMargin = if (phone && barOn()) padBottom + dp(8) + dockH + dp(4) else padBottom + dp(8)
        catBar?.visibility = if (phone) android.view.View.VISIBLE else android.view.View.GONE
        catBar?.let {
            val lp = it.layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin = catMargin
            it.layoutParams = lp
        }
        val bottom = when {
            phone && barOn() -> catMargin + dp(32)
            phone -> catMargin + dp(32)
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
        else if (intent.action == ACTION_CATEGORIES) { closeWidgetSheet(); closeDrawer(); TaskbarService.instance?.closeDrawer(); openCats() }
        else { closeWidgetSheet(); closeDrawer(); closeCats(); TaskbarService.instance?.closeDrawer(); desktop.clearSelection(); desktop.closeFolder(); desktop.goHome() }
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        closeDrawer()
        applyMode()
        Bus.fire()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (catMenu != null) { if (catMenu?.back() != true) closeCats() } else if (sheet != null) closeWidgetSheet() else if (drawer != null) closeDrawer() else desktop.closeFolder() }

    /** The desktop (and the page button) are blurred while the drawer, the screens page or the widget sheet is open. */
    /** Page button above the dock, blurred while a folder is open. */
    fun blurChrome(on: Boolean) { Blur.behind(this, catBar, on || drawer != null || catMenu != null || sheet != null || extBlur) }

    /** Blurs the wallpaper behind the launcher window (everything behind it, but not the dock, which is part of this window). */
    fun wallBlur(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        try {
            val want = on && (Prefs.bool(this, Prefs.K_BLUR, false) || Prefs.bool(this, Prefs.K_GLASS, false))
            val lp = window.attributes
            if (want) { lp.blurBehindRadius = dp(26); window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND) }
            else { lp.blurBehindRadius = 0; window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND) }
            window.attributes = lp
        } catch (_: Throwable) {}
    }

    private fun syncBlur() {
        val on = drawer != null || catMenu != null || sheet != null || extBlur
        Blur.behind(this, desktop, on)
        Blur.behind(this, catBar, on)
    }

    fun toggleDrawer() {
        val svc = TaskbarService.instance
        if (svc != null && Modes.tablet(this) && barOn()) { svc.toggleDrawer(); return }
        if (drawer != null) closeDrawer() else {
            val bm = if (!Modes.tablet(this) && barOn()) padBottom + dockH + dp(16) else padBottom + dp(8)
            val d = DrawerView(this, bm, !Modes.tablet(this)) { closeDrawer() }
            root.addView(d, FrameLayout.LayoutParams(MATCH, MATCH))
            drawer = d
            d.post { d.onShown() }
            syncBlur()
        }
    }

    fun openCats() {
        if (catMenu != null) return
        closeDrawer()
        val phone = !Modes.tablet(this)
        val gap = if (phone) catMargin + dp(24) + dp(8) else MenuArea.bottomReserve + dp(8)
        val m = CatMenu(this, desktop, gap) { closeCats() }
        root.addView(m, FrameLayout.LayoutParams(MATCH, MATCH))
        catMenu = m
        syncBlur()
    }

    fun closeCats() {
        catMenu?.let { root.removeView(it) }
        catMenu = null
        syncBlur()
    }

    fun desktopOpenFile(file: java.io.File) { desktop.openFile(file) }

    fun closeDrawer() {
        drawer?.let { root.removeView(it) }
        drawer = null
        syncBlur()
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
        syncBlur()
    }

    fun closeWidgetSheet() {
        sheet?.let { root.removeView(it) }
        sheet = null
        syncBlur()
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
            val overWidget = desktop.widgetAtRaw(e1.rawX, e1.rawY)
            val dy = e1.y - e2.y
            // Swipe up on the desktop opens the app drawer: a short, light flick anywhere is enough, but only while the
            // plain home screen is showing (not with a menu, folder, widget sheet or screens page open).
            if (drawer == null && !overWidget && dy > dp(40) && vy < -450f && Math.abs(vy) > Math.abs(vx)) {
                if (catMenu != null || sheet != null || PopupMenuX.anyOpen() || !desktop.homeIdle()) return false
                root.post { if (drawer == null) toggleDrawer() }
                return true
            }
            val verticalSwipe = Math.abs(dy) > dp(90) && Math.abs(vy) > 900f && Math.abs(vy) > Math.abs(vx)
            if (!verticalSwipe) return false
            // Swipe down closes it only when the content was already at the top before the gesture.
            if (drawer != null && dy < 0 && vy > 900f && drawerWasAtTopOnTouchDown) {
                root.post { if (drawer != null && drawer?.isContentAtTopForSwipeDismiss() == true) closeDrawer() }
                return true
            }
            return false
        }
    })

    // Distance-based swipe: a short, slow swipe up (anywhere, except over a widget) opens the drawer, a short swipe down closes it.
    private var upX = 0f; private var upY = 0f; private var upOk = false
    private var upOpening = true; private var upTopAtDown = false
    private fun trackUpSwipe(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                upX = ev.rawX; upY = ev.rawY
                upOpening = drawer == null
                upTopAtDown = drawer?.isContentAtTopForSwipeDismiss() == true
                upOk = !Modes.tablet(this) && (if (upOpening) !desktop.widgetAtRaw(ev.rawX, ev.rawY) else upTopAtDown)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> upOk = false
            MotionEvent.ACTION_MOVE -> {
                // open as soon as the finger has travelled far enough, without waiting for it to lift
                if (upOk && upOpening && drawer == null) {
                    val dy = upY - ev.rawY; val dx = ev.rawX - upX
                    if (dy > dp(56) && dy > Math.abs(dx) * 1.5f) {
                        upOk = false
                        if (catMenu != null || sheet != null || PopupMenuX.anyOpen() || !desktop.homeIdle()) return
                        root.post { if (drawer == null) toggleDrawer() }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!upOk) return
                upOk = false
                val dy = upY - ev.rawY; val dx = ev.rawX - upX
                if (Math.abs(dy) < dp(40) || Math.abs(dy) < Math.abs(dx) * 1.5f) return
                if (upOpening && dy > 0 && drawer == null) {
                    if (catMenu != null || sheet != null || PopupMenuX.anyOpen() || !desktop.homeIdle()) return
                    root.post { if (drawer == null) toggleDrawer() }
                } else if (!upOpening && dy < 0 && drawer != null) {
                    root.post { if (drawer != null && drawer?.isContentAtTopForSwipeDismiss() == true) closeDrawer() }
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        trackUpSwipe(ev)
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
