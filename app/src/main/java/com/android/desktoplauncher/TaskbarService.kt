package com.android.desktoplauncher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.view.ContextThemeWrapper
import android.util.Log
import android.app.ActivityManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import android.os.Looper

/**
 * Draws the desktop taskbar in an accessibility overlay window. The overlay is shown over every app,
 * including fullscreen/immersive ones, and sits in the navigation bar area at the bottom.
 */
interface TaskbarHost {
    val running: Collection<String>
    val focused: String?
    fun global(action: Int)
    /** Long press on Back (0), Home (1) or Recents (2). */
    fun navLong(kind: Int) {}
    fun hideRunning(pkg: String)
    fun toggleDrawer()
    fun openSearch()
    val imeShown: Boolean
    fun pickIme()
}

class TaskbarService : AccessibilityService(), TaskbarHost {
    companion object {
        @Volatile var instance: TaskbarService? = null
    }

    private lateinit var wm: WindowManager
    private lateinit var uctx: Context
    private var bar: TaskbarView? = null
    /** 0 = bar along the bottom; 1 / 2 = vertical bar over a system navigation bar on the left / right edge. */
    private var barSide = 0
    private var barShown = true
    private var tintReq: Runnable? = null
    private var lastShot = 0L
    private var tintKey: String? = null
    private var drawer: DrawerView? = null
    override val running = LinkedHashSet<String>()
    override var focused: String? = null
    override var imeShown = false
    private var editing = false
    private var editPkg: String? = null
    private var metaAlone = false
    private val listener: () -> Unit = { sync() }
    /** Apps the user hid from the taskbar; they only come back when they are opened again. */
    private val dismissed = HashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val pruneTick = object : Runnable {
        override fun run() { pruneDead(); if (bar != null) setBarShown(systemBarsVisible()); handler.postDelayed(this, 4000) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            instance = this
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            uctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
            Bus.add(listener)
            sync()
            handler.postDelayed(pruneTick, 4000)
        } catch (e: Exception) {
            Log.e("DesktopTaskbar", "Taskbar service failed to initialize", e)
            removeBar()
            if (instance === this) instance = null
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pruneTick)
        Bus.remove(listener)
        closeDrawer()
        removeBar()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val ed = try { e.source?.isEditable == true } catch (_: Exception) { false }
                editing = ed
                editPkg = e.packageName?.toString()
                scan()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                requestTint()
                if (e.packageName?.toString() != editPkg && e.className?.toString()?.contains("InputMethod") != true) editing = false
                scan()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scan()
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // "Clear all" in the Recents screen: forget every running app.
                val pkg = e.packageName?.toString() ?: return
                val fromRecentsUi = pkg == "com.android.systemui" || pkg.contains("launcher", true) ||
                    pkg.contains("quickstep", true) || pkg.contains("trebuchet", true)
                if (!fromRecentsUi) return
                val txt = ((e.text?.joinToString(" ") ?: "") + " " + (e.contentDescription ?: "")).lowercase()
                if (txt.contains("clear all") || txt.contains("close all") || txt.contains("dismiss all")) {
                    handler.postDelayed({
                        running.clear(); dismissed.clear()
                        bar?.refresh()
                    }, 600)
                }
            }
        }
    }

    /**
     * Removes apps whose process no longer exists (swiped away from Recents). This only works when the
     * ROM lets third-party apps see other apps' processes; if the list looks restricted we do nothing.
     */
    private fun pruneDead() {
        if (running.isEmpty() || bar == null) return
        try {
            val am = getSystemService(ActivityManager::class.java) ?: return
            val procs = am.runningAppProcesses ?: return
            val alive = HashSet<String>()
            for (p in procs) p.pkgList?.let { alive.addAll(it) }
            if (alive.count { it != packageName } < 4) return
            var ch = false
            val it = running.iterator()
            while (it.hasNext()) {
                val pkg = it.next()
                if (pkg !in alive && pkg != focused) { it.remove(); ch = true }
            }
            if (ch) bar?.refresh()
        } catch (_: Exception) {}
    }

    /** Windows / Meta key on a hardware keyboard opens the app drawer. */
    override fun onKeyEvent(e: android.view.KeyEvent): Boolean {
        if (bar == null || !Prefs.bool(this, Prefs.K_WINKEY, true)) return false
        val meta = e.keyCode == android.view.KeyEvent.KEYCODE_META_LEFT || e.keyCode == android.view.KeyEvent.KEYCODE_META_RIGHT
        if (meta) {
            if (e.action == android.view.KeyEvent.ACTION_DOWN) { if (e.repeatCount == 0) metaAlone = true; return true }
            if (e.action == android.view.KeyEvent.ACTION_UP) {
                val was = metaAlone
                metaAlone = false
                if (was) toggleDrawer()
                return true
            }
        } else if (e.action == android.view.KeyEvent.ACTION_DOWN) metaAlone = false
        return false
    }

    override fun pickIme() {
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker() }
        catch (_: Exception) {}
    }

    private fun skip(p: String): Boolean {
        if (p == packageName || p == "com.android.systemui") return true
        return packageManager.getLaunchIntentForPackage(p) == null
    }

    private fun scan() {
        var fg: String? = null
        var imeWin = false
        val vis = LinkedHashSet<String>()
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) imeWin = true
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val p = w.root?.packageName?.toString() ?: continue
                if (skip(p)) continue
                vis += p
                if (w.isActive) fg = p
            }
        } catch (_: Exception) {}
        var changed = false
        // Keep a short recent-app list, not just windows visible on this exact frame.
        // Order is kept (first opened = first) so the taskbar does not jump around while scrolling.
        val prevFocus = focused
        for (p in vis) {
            if (p in dismissed) {
                if (p == fg && p != prevFocus) dismissed.remove(p) else continue
            }
            if (running.add(p)) changed = true
        }
        while (running.size > 10) { running.remove(running.first()); changed = true }
        if (fg != focused) { focused = fg; changed = true }
        val ime = imeWin || editing
        if (ime != imeShown) { imeShown = ime; changed = true }
        if (changed) bar?.refresh()
        // Accessibility events also arrive when the keyguard is shown/hidden.
        sync(false)
    }

    override fun hideRunning(pkg: String) { running.remove(pkg); dismissed.add(pkg); bar?.refresh() }
    override fun global(action: Int) { performGlobalAction(action) }

    override fun navLong(kind: Int) {
        val key = when (kind) { 0 -> Prefs.K_LP_BACK; 1 -> Prefs.K_LP_HOME; else -> Prefs.K_LP_RECENTS }
        try {
            when (Prefs.getInt(this, key, if (kind == 1) NavActions.ASSIST else NavActions.NONE)) {
                NavActions.ASSIST -> startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                NavActions.SPLIT -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                NavActions.SHOT -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                NavActions.LOCK -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                NavActions.POWER -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                NavActions.NOTIF -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                NavActions.QS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                NavActions.DRAWER -> toggleDrawer()
                NavActions.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                NavActions.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                NavActions.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        } catch (e: Exception) { Log.e("DesktopTaskbar", "Long-press action failed", e) }
    }
    override fun openSearch() {
        if (drawer == null) openDrawer()
        drawer?.post { drawer?.focusSearch() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        sync()
        requestTint(700)
    }

    /** Re-reads preferences: adds/removes the bar and updates its size. */
    private fun sync(refresh: Boolean = true) {
        try {
            val keyguardLocked = try { getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true } catch (_: Exception) { false }
            if (!Prefs.bool(this, Prefs.K_TASKBAR, true) || !Modes.tablet(this) || keyguardLocked) {
                removeBar(); closeDrawer(); return
            }
            // While the system bars are hidden (video, fullscreen) keep the bar where it was and just hide it.
            val vis = systemBarsVisible()
            val side = if (vis) navSide() else barSide
            if (bar != null && barSide != side) removeBar()
            val b = bar
            if (b != null && side != 0) {
                val lp = b.layoutParams as? WindowManager.LayoutParams
                if (lp != null && lp.width != uctx.dp(Prefs.tbDp(this))) { removeBar(); addBar() } else if (refresh) b.refresh()
            } else if (b == null) addBar() else {
                val lp = b.layoutParams as? WindowManager.LayoutParams
                if (lp != null && (refresh || lp.height != uctx.dp(Prefs.tbDp(this)))) {
                    lp.height = uctx.dp(Prefs.tbDp(this))
                    Blur.apply(lp, uctx, 24)
                    try { wm.updateViewLayout(b, lp) } catch (t: Throwable) {
                        Log.e("DesktopTaskbar", "Could not update taskbar window", t)
                    }
                }
                if (refresh) b.refresh()
            }
            setBarShown(vis)
            requestTint(300)
        } catch (e: Exception) {
            Log.e("DesktopTaskbar", "Taskbar sync failed", e)
            removeBar()
        }
    }

    /** Which screen edge the system navigation bar is on when it is a side bar (phone in landscape); 0 otherwise. */
    private fun navSide(): Int = try {
        val i = wm.currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.navigationBars())
        if (i.right > 0) 2 else if (i.left > 0) 1 else 0
    } catch (_: Exception) { 0 }

    /** False while both the navigation bar and the status bar are hidden. The metrics only count bars that are showing. */
    private fun systemBarsVisible(): Boolean = try {
        val w = wm.currentWindowMetrics.windowInsets
        val n = w.getInsets(android.view.WindowInsets.Type.navigationBars())
        val st = w.getInsets(android.view.WindowInsets.Type.statusBars())
        n.left + n.right + n.bottom + n.top + st.top > 0
    } catch (_: Exception) { true }

    private fun statusTop(): Int = try {
        wm.currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top
    } catch (_: Exception) { 0 }

    private fun addBar() {
        var v: TaskbarView? = null
        try {
            val side = navSide()
            val thick = uctx.dp(Prefs.tbDp(this))
            val top = if (side != 0) statusTop() else 0
            val len = wm.currentWindowMetrics.bounds.height()
            v = TaskbarView(uctx, this, true, false, side, len, top)
            val lp = WindowManager.LayoutParams(
                if (side != 0) thick else MATCH, if (side != 0) len else thick,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = if (side == 0) Gravity.BOTTOM else Gravity.TOP or (if (side == 2) Gravity.END else Gravity.START)
            lp.setFitInsetsTypes(0)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            // not under the punch hole or a side navigation bar in landscape
            val si = Insets.safe(uctx)
            if (side == 0 && si.left + si.right > 0) {
                lp.width = wm.currentWindowMetrics.bounds.width() - si.left - si.right
                lp.x = (si.left - si.right) / 2
            }
            lp.title = "DesktopTaskbar"
            Blur.apply(lp, uctx, 24)
            wm.addView(v, lp)
            bar = v
            barSide = side
            barShown = true
            // follow the system bars: hidden in video / fullscreen, back when the system buttons come back
            v.onInsets = { handler.post { sync(false) } }
        } catch (e: Exception) {
            Log.e("DesktopTaskbar", "Could not add taskbar overlay", e)
            if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
            bar = null
        }
    }

    /** The window stays alive (so it keeps receiving inset changes) but is invisible and lets touches through while hidden. */
    private fun setBarShown(show: Boolean) {
        val b = bar ?: return
        if (show == barShown) return
        barShown = show
        try {
            val lp = b.layoutParams as WindowManager.LayoutParams
            lp.flags = if (show) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                       else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            b.alpha = if (show) 1f else 0f
            wm.updateViewLayout(b, lp)
            if (!show) closeDrawer()
        } catch (e: Exception) {
            Log.e("DesktopTaskbar", "Could not show/hide taskbar", e)
        }
    }

    /** Experimental: asks for a screenshot shortly after the app changes and tints the bar with the colour of the system navigation strip. */
    private fun requestTint(delay: Long = 500) {
        tintReq?.let { handler.removeCallbacks(it) }
        val r = Runnable { sampleTint() }
        tintReq = r
        handler.postDelayed(r, delay)
    }

    private fun sampleTint() {
        val b = bar ?: return
        if (!Prefs.bool(this, Prefs.K_APPCOLOR, false)) { b.tint = null; tintKey = null; return }
        if (!barShown || focused == null || focused == packageName) { b.tint = null; tintKey = null; return }
        // only look again when the app or the screen direction changed (the bar flickers for a moment while it is sampled)
        val key = "$focused|$barSide|${resources.configuration.orientation}"
        if (key == tintKey) return
        val now = SystemClock.uptimeMillis()
        if (now - lastShot < 1100) { requestTint(1150 - (now - lastShot)); return }
        lastShot = now
        tintKey = key
        fun restore() { b.alpha = if (barShown) 1f else 0f }
        b.alpha = 0f   // so the bar itself is not in the picture
        handler.postDelayed({
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                        restore()
                        b.tint = try { stripColor(r) } catch (e: Exception) { Log.e("DesktopTaskbar", "colour sample failed", e); null }
                    }
                    override fun onFailure(code: Int) { restore() }
                })
            } catch (e: Exception) { restore() }
        }, 90)
    }

    /** Average colour of a few points on the strip where the system navigation bar sits, or null when they differ too much. */
    private fun stripColor(r: AccessibilityService.ScreenshotResult): Int? {
        val hb = r.hardwareBuffer
        val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace) ?: run { hb.close(); return null }
        val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
        hb.close()
        if (bmp == null) return null
        val w = bmp.width; val h = bmp.height
        val pts = ArrayList<Pair<Int, Int>>()
        when (barSide) {
            0 -> for (f in floatArrayOf(0.04f, 0.1f, 0.9f, 0.96f)) pts += Pair((w * f).toInt(), h - 3)
            2 -> for (f in floatArrayOf(0.25f, 0.45f, 0.65f, 0.85f)) pts += Pair(w - 3, (h * f).toInt())
            else -> for (f in floatArrayOf(0.25f, 0.45f, 0.65f, 0.85f)) pts += Pair(2, (h * f).toInt())
        }
        val cols = pts.map { bmp.getPixel(it.first.coerceIn(0, w - 1), it.second.coerceIn(0, h - 1)) }
        bmp.recycle()
        for (a in cols) for (c in cols) {
            if (Math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(c)) > 36 ||
                Math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(c)) > 36 ||
                Math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(c)) > 36) return null
        }
        val rr = cols.sumOf { android.graphics.Color.red(it) } / cols.size
        val gg = cols.sumOf { android.graphics.Color.green(it) } / cols.size
        val bb = cols.sumOf { android.graphics.Color.blue(it) } / cols.size
        return android.graphics.Color.rgb(rr, gg, bb)
    }

    private fun removeBar() {
        bar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bar = null
    }

    override fun toggleDrawer() { if (drawer != null) closeDrawer() else openDrawer() }

    fun openDrawer() {
        if (drawer != null) return
        val barH = if (bar != null && barSide == 0) uctx.dp(Prefs.tbDp(this)) else 0
        val barW = if (bar != null && barSide != 0) uctx.dp(Prefs.tbDp(this)) else 0
        val screenH = wm.currentWindowMetrics.bounds.height()
        val d = DrawerView(uctx, uctx.dp(8), false, if (barW > 0) barSide else 0) { closeDrawer() }
        // dragging an icon to the desktop is only possible while our launcher is the app behind the drawer
        d.canDrag = { focused == packageName }
        d.dragHook = { on ->
            // while an icon is carried, the drawer window lets touches / drops through to the desktop
            try {
                val dlp = d.layoutParams as WindowManager.LayoutParams
                dlp.flags = if (on) dlp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            else dlp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                wm.updateViewLayout(d, dlp)
            } catch (_: Exception) {}
            LauncherActivity.blurFromOverlay(!on && drawer === d)
        }
        val lp = WindowManager.LayoutParams(
            if (barW > 0) wm.currentWindowMetrics.bounds.width() - barW else MATCH, screenH - barH - (if (barW > 0) statusTop() else 0),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or (if (barSide == 1) Gravity.END else Gravity.START)
        if (barW > 0) lp.y = statusTop()
        lp.setFitInsetsTypes(0)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        lp.title = "DesktopDrawer"
        // Do not use BLUR_BEHIND on this full-screen touch layer: Android blurs the entire
        // app area and some ROMs also stop dispatching touches. The panel itself is translucent.
        try { wm.addView(d, lp); drawer = d; d.post { d.onShown() }; LauncherActivity.blurFromOverlay(true) } catch (_: Exception) {}
        raiseBar()
    }

    /**
     * The keyboard is layered directly above the window that owns the text field (the drawer). Re-adding the
     * taskbar afterwards puts it above the drawer, so it also stays above the keyboard.
     */
    private fun raiseBar() {
        val b = bar ?: return
        try {
            val lp = b.layoutParams
            wm.removeViewImmediate(b)
            wm.addView(b, lp)
        } catch (e: Exception) {
            Log.e("DesktopTaskbar", "Could not raise taskbar", e)
            bar = null
            sync()
        }
    }

    fun closeDrawer() {
        drawer?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        drawer = null
        LauncherActivity.blurFromOverlay(false)
    }

    fun isDrawerOpen() = drawer != null
}

class TaskbarView(private val ctx: Context, private val svc: TaskbarHost, private val showNav: Boolean, private val pill: Boolean,
                  private val side: Int = 0, private val len: Int = 0, private val inset: Int = 0) : FrameLayout(ctx) {
    /**
     * Side mode: the whole bar is laid out horizontally in [body] (len x thickness) and turned 90 degrees so it lies along the
     * screen edge; the icons are turned back, so they stay upright like the system navigation buttons do.
     */
    /** Set by the service: called when the window insets change, so it can check whether the system bars are showing. */
    var onInsets: (() -> Unit)? = null

    /** Colour taken from the open app (null = normal Material You colour). Set by the service when the option is on. */
    var tint: Int? = null
        set(v) { if (field != v) { field = v; refresh() } }

    /** Icon colour that reads well on the tint. */
    val fg: Int? get() = tint?.let { t ->
        val lum = (0.299f * android.graphics.Color.red(t) + 0.587f * android.graphics.Color.green(t) + 0.114f * android.graphics.Color.blue(t)) / 255f
        if (lum < 0.55f) 0xFFFFFFFF.toInt() else 0xFF1B1B1F.toInt()
    }
    private val body: FrameLayout? = if (side != 0) FrameLayout(ctx) else null
    private val host: FrameLayout get() = body ?: this
    private val center = LinearLayout(ctx)
    private val right = LinearLayout(ctx)
    private val appsRow = LinearLayout(ctx)
    private var curCell = 0
    private val scroll = SnapScroll(ctx) { curCell }
    private var menu: PopupMenuX? = null
    private var lastFocused: String? = null

    init {
        // The taskbar is always left-to-right, whatever direction the rest of the UI uses.
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        center.layoutDirection = View.LAYOUT_DIRECTION_LTR
        right.layoutDirection = View.LAYOUT_DIRECTION_LTR
        appsRow.layoutDirection = View.LAYOUT_DIRECTION_LTR
        scroll.layoutDirection = View.LAYOUT_DIRECTION_LTR
        elevation = if (pill) ctx.dp(6).toFloat() else 0f
        center.orientation = LinearLayout.HORIZONTAL
        center.gravity = Gravity.CENTER_VERTICAL
        right.orientation = LinearLayout.HORIZONTAL
        right.gravity = Gravity.CENTER_VERTICAL
        appsRow.orientation = LinearLayout.HORIZONTAL
        appsRow.gravity = Gravity.CENTER_VERTICAL
        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipToPadding = false
        scroll.addView(appsRow, FrameLayout.LayoutParams(WRAP, MATCH))
        if (showNav) setOnApplyWindowInsetsListener { _, ins -> onInsets?.invoke(); ins }
        host.addView(center, LayoutParams(MATCH, MATCH, Gravity.START or Gravity.CENTER_VERTICAL).also {
            it.leftMargin = ctx.dp(4)
        })
        host.addView(right, LayoutParams(WRAP, MATCH, Gravity.END).also { it.rightMargin = ctx.dp(8) })
        if (body != null) {
            val thick = ctx.dp(Prefs.tbDp(ctx))
            body.pivotX = 0f; body.pivotY = 0f
            // Left edge (device top to the right): the bar reads top to bottom. Right edge: bottom to top, like the system buttons.
            if (side == 1) { body.rotation = 90f; body.translationX = thick.toFloat() }
            else { body.rotation = -90f; body.translationY = len.toFloat() }
            // keep the icons clear of the status bar area, which is now part of the bar
            body.setPadding(if (side == 1) inset else 0, 0, if (side == 2) inset else 0, 0)
            addView(body, LayoutParams(len, thick))
        }
        refresh()
    }

    /** Mouse wheel scrolls the app strip sideways. */
    override fun onGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_SCROLL) {
            val dy = ev.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
            val dx = ev.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL)
            scroll.scrollBy(((dx - dy) * ctx.dp(48)).toInt(), 0)
            return true
        }
        return super.onGenericMotionEvent(ev)
    }

    private fun ripple(): android.graphics.drawable.Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33000000), null, Colors.rounded(0xFFFFFFFF.toInt(), ctx.dp(10).toFloat()))

    fun refresh() {
        val barCol = tint?.let { Colors.glass(ctx, it) } ?: Colors.taskbar(ctx)
        val bg = if (pill) Colors.panel(ctx, barCol, ctx.dp(28).toFloat()) else android.graphics.drawable.ColorDrawable(barCol)
        if (body != null) body.background = bg else background = bg
        // Keep the scroll position across rebuilds (the strip used to jump back to the start).
        val keepX = scroll.scrollX
        center.removeAllViews()
        right.removeAllViews()
        appsRow.removeAllViews()
        val h = ctx.dp(Prefs.tbDp(ctx))
        val cell = minOf(h, ctx.dp(52))
        curCell = cell
        right.visibility = if (showNav) View.VISIBLE else View.GONE

        center.addView(StartButton(ctx).also {
            it.background = ripple()
            it.contentDescription = UiText.text(ctx, "Apps")
            it.setOnClickListener { svc.toggleDrawer() }
        }, LinearLayout.LayoutParams(cell, MATCH))
        center.addView(SearchButton(ctx).also {
            it.background = ripple()
            it.contentDescription = UiText.text(ctx, "Search")
            it.setOnClickListener { svc.openSearch() }
        }, LinearLayout.LayoutParams(cell, MATCH))

        val pinned = Prefs.list(ctx, Prefs.K_PINNED).mapNotNull { ComponentName.unflattenFromString(it) }
        val shownPk = HashSet<String>()
        for (comp in pinned) {
            shownPk += comp.packageName
            appsRow.addView(appItem(comp, svc.running.contains(comp.packageName), svc.focused == comp.packageName, true, cell)
                .also { it.tag = comp.packageName }, LinearLayout.LayoutParams(cell, MATCH))
        }
        val unpinned = svc.running.filter { it !in shownPk }.mapNotNull { pkg -> AppRepo.byPkg(ctx, pkg)?.let { pkg to it.comp } }
        if (unpinned.isNotEmpty() && pinned.isNotEmpty()) {
            val divider = View(ctx)
            divider.setBackgroundColor((fg ?: Colors.text(ctx)) and 0x55FFFFFF)
            appsRow.addView(divider, LinearLayout.LayoutParams(ctx.dp(1), (h * 0.58f).toInt()).also {
                it.leftMargin = ctx.dp(5); it.rightMargin = ctx.dp(5)
            })
        }
        for ((pkg, comp) in unpinned) {
            appsRow.addView(appItem(comp, true, svc.focused == pkg, false, cell).also { it.tag = pkg }, LinearLayout.LayoutParams(cell, MATCH))
        }
        center.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f))
        val navCount = if (showNav && Prefs.bool(ctx, Prefs.K_NAV, true)) 3 else 0
        val imeCount = if (showNav && svc.imeShown && Prefs.bool(ctx, Prefs.K_IME, true)) 1 else 0
        val navReserve = if (showNav) cell * (navCount + imeCount) + (if (body != null) ctx.dp(8) else ctx.dp(92)) else ctx.dp(4)
        (center.layoutParams as? LayoutParams)?.let { it.rightMargin = navReserve; center.layoutParams = it }

        val foc = svc.focused
        val focusChanged = foc != lastFocused
        lastFocused = foc
        scroll.post {
            scroll.scrollTo(keepX, 0)
            // A newly opened / switched-to app scrolls into view; otherwise the position stays where the user left it.
            if (focusChanged && foc != null) {
                val v = appsRow.findViewWithTag<View>(foc)
                if (v != null && (v.left < scroll.scrollX || v.right > scroll.scrollX + scroll.width)) {
                    scroll.smoothScrollTo((v.left - (scroll.width - v.width) / 2).coerceAtLeast(0), 0)
                }
            }
        }

        if (showNav && svc.imeShown && Prefs.bool(ctx, Prefs.K_IME, true)) {
            right.addView(ImeButton(ctx).also { b ->
                b.background = ripple(); b.setOnClickListener { svc.pickIme() }
            }, LinearLayout.LayoutParams(cell, MATCH))
        }
        if (showNav && Prefs.bool(ctx, Prefs.K_NAV, true)) {
            right.addView(NavButton(ctx, 0).also { b ->
                b.background = ripple(); b.setOnClickListener { svc.global(AccessibilityService.GLOBAL_ACTION_BACK) }
                b.setOnLongClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); svc.navLong(0); true }
            }, LinearLayout.LayoutParams(cell, MATCH))
            right.addView(NavButton(ctx, 1).also { b ->
                b.background = ripple(); b.setOnClickListener { svc.global(AccessibilityService.GLOBAL_ACTION_HOME) }
                b.setOnLongClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); svc.navLong(1); true }
            }, LinearLayout.LayoutParams(cell, MATCH))
            right.addView(NavButton(ctx, 2).also { b ->
                b.background = ripple(); b.setOnClickListener { svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS) }
                b.setOnLongClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); svc.navLong(2); true }
            }, LinearLayout.LayoutParams(cell, MATCH))
        }
        if (showNav && body == null) {
            // taskbar mode: the category button takes the place of the time
            val pill = CatPill(ctx, 68, 22, outline = { fg ?: Colors.accent(ctx) }, ink = { fg }) { (Colors.iconBg(ctx) and 0x00FFFFFF) or 0xCC000000.toInt() }
            pill.setOnClickListener {
                Launch.start(ctx, android.content.Intent(ctx, LauncherActivity::class.java).setAction(LauncherActivity.ACTION_CATEGORIES))
            }
            right.addView(pill, LinearLayout.LayoutParams(ctx.dp(68), ctx.dp(22)).also {
                it.gravity = Gravity.CENTER_VERTICAL; it.marginStart = ctx.dp(6); it.marginEnd = ctx.dp(6)
            })
        } else if (!showNav) {
            val clock = TextClock(ctx)
            clock.format12Hour = "h:mm a"
            clock.format24Hour = "HH:mm"
            clock.textSize = 13f
            clock.setTextColor(Colors.text(ctx))
            clock.gravity = Gravity.CENTER
            clock.setPadding(ctx.dp(8), 0, ctx.dp(4), 0)
            right.addView(clock, LinearLayout.LayoutParams(WRAP, MATCH))
        }
        if (body != null) uprightIcons(body)
    }

    /** Side mode: turns every icon back by the bar's rotation (the running-dot lines and dividers turn with the bar). */
    private fun uprightIcons(g: android.view.ViewGroup) {
        for (i in 0 until g.childCount) {
            val v = g.getChildAt(i)
            if (v is android.view.ViewGroup) uprightIcons(v)
            else if (v is android.widget.ImageView || v is NavButton || v is SearchButton || v is StartButton || v is ImeButton) v.rotation = if (side == 1) -90f else 90f
        }
    }

    private fun appItem(comp: ComponentName, running: Boolean, active: Boolean, pinned: Boolean, cell: Int): View {
        val f = FrameLayout(ctx)
        f.background = ripple()
        val ic = BadgedIcon(ctx)
        ic.setImageDrawable(Icons.app(ctx, comp, false))
        ic.dot = Prefs.bool(ctx, Prefs.K_DOTS, true) && NotifState.has(comp.packageName)
        val s = (cell * 0.58f).toInt()
        f.addView(ic, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        if (running) {
            val line = View(ctx)
            line.background = Colors.rounded(fg ?: Colors.accent(ctx), ctx.dp(2).toFloat())
            f.addView(line, FrameLayout.LayoutParams(ctx.dp(if (active) 18 else 6), ctx.dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .also { it.bottomMargin = ctx.dp(3) })
        }
        f.setOnClickListener { Launch.app(ctx, comp) }
        val show = { x: Int, y: Int ->
            menu?.dismiss()
            val m = PopupMenuX(ctx, this)
            menu = m
            val items = ArrayList<MenuItem>()
            items += MenuItem("Open") { Launch.app(ctx, comp) }
            items += MenuItem("App Info") { Launch.info(ctx, comp.packageName) }
            items += Launch.sizeMenu(ctx, comp)
            items += MenuItem(if (pinned) "Unpin from taskbar" else "Pin to taskbar") {
                Prefs.toggleInList(ctx, Prefs.K_PINNED, comp.flattenToString())
            }
            if (running) items += MenuItem("Hide from taskbar") { svc.hideRunning(comp.packageName) }
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            m.show(x, loc[1], items, above = true)
        }
        f.setOnLongClickListener {
            val loc = IntArray(2); f.getLocationOnScreen(loc); show(loc[0], 0); true
        }
        f.setSecondaryClick { x, y -> show(x, y) }
        return f
    }
}

/** App drawer icon: 3x3 dots, same as the all-apps button of the Launcher3 taskbar. */
class StartButton(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        p.color = tintFg() ?: Colors.accent(context)
        val s = minOf(width, height) * 0.38f
        val step = s / 2f
        val r = s * 0.11f
        val l = (width - s) / 2
        val t = (height - s) / 2
        for (i in 0..2) for (j in 0..2) canvas.drawCircle(l + i * step, t + j * step, r, p)
    }
}

/** Language / keyboard switcher (globe). Shown while a keyboard is in use. */
class ImeButton(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    override fun onDraw(canvas: Canvas) {
        p.color = barFg()
        p.strokeWidth = context.dp(2).toFloat() * 0.8f
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.2f
        canvas.drawCircle(cx, cy, r, p)
        canvas.drawOval(RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), p)
        canvas.drawLine(cx - r, cy, cx + r, cy, p)
    }
}

/** kind: 0 back, 1 home, 2 recents */
/** Icon colour of a taskbar button: follows the bar's tint when it has one. */
fun View.tintFg(): Int? {
    var p = parent
    while (p != null) { if (p is TaskbarView) return p.fg; p = p.parent }
    return null
}

fun View.barFg(): Int = tintFg() ?: Colors.text(context)

/**
 * The app strip: only whole icons are visible at rest. The strip is as wide as a whole number of icons and, after a drag or
 * mouse-wheel scroll, glides to the nearest icon edge, so no icon is ever cut in half at either end.
 */
class SnapScroll(c: Context, private val cell: () -> Int) : android.widget.HorizontalScrollView(c) {
    private var touching = false
    private val snap = Runnable {
        val row = getChildAt(0) as? android.view.ViewGroup ?: return@Runnable
        var best = scrollX
        var bd = Int.MAX_VALUE
        for (i in 0 until row.childCount) {
            val d = Math.abs(row.getChildAt(i).left - scrollX)
            if (d < bd) { bd = d; best = row.getChildAt(i).left }
        }
        val max = (row.width - width).coerceAtLeast(0)
        best = best.coerceIn(0, max)
        if (Math.abs(best - scrollX) > 1) smoothScrollTo(best, 0)
    }

    override fun onMeasure(w: Int, h: Int) {
        val cw = cell()
        val size = MeasureSpec.getSize(w)
        if (cw > 0 && MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED && size >= cw) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(size / cw * cw, MeasureSpec.EXACTLY), h)
        } else super.onMeasure(w, h)
    }

    override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) {
        super.onScrollChanged(l, t, ol, ot)
        removeCallbacks(snap)
        if (!touching) postDelayed(snap, 140)
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { touching = true; removeCallbacks(snap) }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { touching = false; removeCallbacks(snap); postDelayed(snap, 160) }
        }
        return super.onTouchEvent(ev)
    }
}

/** What a long press on Back / Home / Recents can do. */
object NavActions {
    const val NONE = 0; const val ASSIST = 1; const val SPLIT = 2; const val SHOT = 3; const val LOCK = 4
    const val POWER = 5; const val NOTIF = 6; const val QS = 7; const val DRAWER = 8
    const val BACK = 9; const val HOME = 10; const val RECENTS = 11
    val names = listOf("None", "Assistant (Circle to Search where the system allows)", "Split screen", "Screenshot", "Lock screen",
        "Power menu", "Notifications", "Quick settings", "App drawer", "Back", "Home", "Recents")
}

/** Look of the Back / Home / Recents buttons. 0 = follows the phone's make. */
object NavStyle {
    const val SYSTEM = 0; const val LAUNCHER = 1; const val SAMSUNG = 2; const val PIXEL = 3; const val VIVO = 4; const val OPPO = 5; const val XIAOMI = 6
    val names = listOf("System (like this phone)", "Desktop Launcher", "Samsung", "Pixel", "Vivo", "Oppo", "Xiaomi")

    fun resolve(c: Context): Int {
        val v = Prefs.getInt(c, Prefs.K_NAVSTYLE, SYSTEM)
        if (v != SYSTEM) return v
        val m = (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND).lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> XIAOMI
            m.contains("samsung") -> SAMSUNG
            m.contains("vivo") || m.contains("iqoo") -> VIVO
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> OPPO
            else -> PIXEL
        }
    }
}

class NavButton(c: Context, private val kind: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }

    private fun stroke(w: Float) { p.style = Paint.Style.STROKE; p.strokeWidth = w }
    private fun fill(w: Float = 0f) { p.style = if (w > 0f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL; p.strokeWidth = w }

    /** Triangle pointing left; filled ones get softly rounded corners from a round-joined outline of the same colour. */
    private fun tri(cv: Canvas, cx: Float, cy: Float, r: Float, filled: Boolean, w: Float) {
        val pad = if (filled) w / 2f else 0f
        val path = Path()
        path.moveTo(cx + r * 0.85f - pad, cy - r + pad); path.lineTo(cx - r * 0.85f + pad, cy); path.lineTo(cx + r * 0.85f - pad, cy + r - pad); path.close()
        if (filled) fill(w) else stroke(w)
        cv.drawPath(path, p)
    }

    private fun chevron(cv: Canvas, cx: Float, cy: Float, r: Float, w: Float) {
        val path = Path()
        path.moveTo(cx + r * 0.45f, cy - r * 0.95f); path.lineTo(cx - r * 0.45f, cy); path.lineTo(cx + r * 0.45f, cy + r * 0.95f)
        stroke(w); cv.drawPath(path, p)
    }

    private fun square(cv: Canvas, cx: Float, cy: Float, r: Float, filled: Boolean, w: Float, corner: Float) {
        val pad = if (filled) w / 2f else 0f
        val rc = RectF(cx - r + pad, cy - r + pad, cx + r - pad, cy + r - pad)
        if (filled) fill(w) else stroke(w)
        cv.drawRoundRect(rc, corner, corner, p)
    }

    override fun onDraw(canvas: Canvas) {
        p.color = barFg()
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.17f      // same size as before: the shapes are about 0.34 of the cell
        val dp2 = context.dp(2).toFloat()
        when (NavStyle.resolve(context)) {
            NavStyle.LAUNCHER -> when (kind) {
                0 -> tri(canvas, cx, cy, r, false, dp2)
                1 -> { stroke(dp2); canvas.drawCircle(cx, cy, r, p) }
                else -> square(canvas, cx, cy, r, false, dp2, r * 0.35f)
            }
            NavStyle.XIAOMI -> when (kind) {
                0 -> tri(canvas, cx, cy, r, true, r * 0.45f)
                1 -> {   // thin ring, a gap, then a solid disc; a little larger than Back and Recents
                    val big = r * 1.22f
                    stroke(r * 0.15f); canvas.drawCircle(cx, cy, big - r * 0.075f, p)
                    fill(); canvas.drawCircle(cx, cy, big * 0.66f, p)
                }
                else -> square(canvas, cx, cy, r * 0.95f, true, r * 0.3f, r * 0.4f)
            }
            NavStyle.PIXEL -> when (kind) {
                0 -> tri(canvas, cx, cy, r, true, r * 0.5f)
                1 -> { fill(); canvas.drawCircle(cx, cy, r, p) }
                else -> square(canvas, cx, cy, r * 0.92f, true, r * 0.4f, r * 0.5f)
            }
            NavStyle.SAMSUNG -> when (kind) {
                0 -> chevron(canvas, cx, cy, r, r * 0.3f)
                1 -> { stroke(r * 0.26f); canvas.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), r * 0.6f, r * 0.6f, p) }   // rounded square
                else -> { stroke(r * 0.28f); for (i in -1..1) canvas.drawLine(cx + i * r * 0.85f, cy - r * 0.9f, cx + i * r * 0.85f, cy + r * 0.9f, p) }
            }
            NavStyle.VIVO -> when (kind) {
                0 -> chevron(canvas, cx, cy, r, r * 0.24f)
                1 -> { stroke(r * 0.24f); canvas.drawCircle(cx, cy, r, p) }
                else -> { stroke(r * 0.24f); for (i in -1..1) canvas.drawLine(cx - r * 0.9f, cy + i * r * 0.7f, cx + r * 0.9f, cy + i * r * 0.7f, p) }
            }
            else -> when (kind) {   // OPPO
                0 -> tri(canvas, cx, cy, r, false, r * 0.24f)
                1 -> { stroke(r * 0.24f); canvas.drawCircle(cx, cy, r, p) }
                else -> square(canvas, cx, cy, r, false, r * 0.24f, r * 0.3f)
            }
        }
    }
}


/** Dedicated taskbar search shortcut, placed beside the app-drawer button. */
class SearchButton(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        p.color = barFg()
        p.strokeWidth = context.dp(2).toFloat()
        val r = minOf(width, height) * 0.17f
        val cx = width / 2f - r * 0.18f
        val cy = height / 2f - r * 0.18f
        canvas.drawCircle(cx, cy, r, p)
        canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f, cx + r * 1.55f, cy + r * 1.55f, p)
    }
}
