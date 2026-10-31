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
import android.os.Handler
import android.os.Looper

/**
 * Draws the desktop taskbar in an accessibility overlay window. The overlay is shown over every app,
 * including fullscreen/immersive ones, and sits in the navigation bar area at the bottom.
 */
interface TaskbarHost {
    val running: Collection<String>
    val focused: String?
    fun global(action: Int)
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
        override fun run() { pruneDead(); handler.postDelayed(this, 4000) }
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
    override fun openSearch() {
        if (drawer == null) openDrawer()
        drawer?.post { drawer?.focusSearch() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        sync()
    }

    /** Re-reads preferences: adds/removes the bar and updates its size. */
    private fun sync(refresh: Boolean = true) {
        try {
            val keyguardLocked = try { getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true } catch (_: Exception) { false }
            if (!Prefs.bool(this, Prefs.K_TASKBAR, true) || !Modes.tablet(this) || keyguardLocked) {
                removeBar(); closeDrawer(); return
            }
            val b = bar
            if (b == null) addBar() else {
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
        } catch (e: Exception) {
            Log.e("DesktopTaskbar", "Taskbar sync failed", e)
            removeBar()
        }
    }

    private fun addBar() {
        var v: TaskbarView? = null
        try {
            v = TaskbarView(uctx, this, true, false)
            val lp = WindowManager.LayoutParams(
                MATCH, uctx.dp(Prefs.tbDp(this)),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.BOTTOM
            lp.setFitInsetsTypes(0)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            // not under the punch hole or a side navigation bar in landscape
            val si = Insets.safe(uctx)
            if (si.left + si.right > 0) {
                lp.width = wm.currentWindowMetrics.bounds.width() - si.left - si.right
                lp.x = (si.left - si.right) / 2
            }
            lp.title = "DesktopTaskbar"
            Blur.apply(lp, uctx, 24)
            wm.addView(v, lp)
            bar = v
        } catch (e: Exception) {
            Log.e("DesktopTaskbar", "Could not add taskbar overlay", e)
            if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
            bar = null
        }
    }

    private fun removeBar() {
        bar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bar = null
    }

    override fun toggleDrawer() { if (drawer != null) closeDrawer() else openDrawer() }

    fun openDrawer() {
        if (drawer != null) return
        val barH = if (bar != null) uctx.dp(Prefs.tbDp(this)) else 0
        val screenH = wm.currentWindowMetrics.bounds.height()
        val d = DrawerView(uctx, uctx.dp(8)) { closeDrawer() }
        val lp = WindowManager.LayoutParams(
            MATCH, screenH - barH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP
        lp.setFitInsetsTypes(0)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        lp.title = "DesktopDrawer"
        // Do not use BLUR_BEHIND on this full-screen touch layer: Android blurs the entire
        // app area and some ROMs also stop dispatching touches. The panel itself is translucent.
        try { wm.addView(d, lp); drawer = d; d.post { d.onShown() } } catch (_: Exception) {}
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
    }

    fun isDrawerOpen() = drawer != null
}

class TaskbarView(private val ctx: Context, private val svc: TaskbarHost, private val showNav: Boolean, private val pill: Boolean) : FrameLayout(ctx) {
    private val center = LinearLayout(ctx)
    private val right = LinearLayout(ctx)
    private val appsRow = LinearLayout(ctx)
    private val scroll = android.widget.HorizontalScrollView(ctx)
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
        addView(center, LayoutParams(MATCH, MATCH, Gravity.START or Gravity.CENTER_VERTICAL).also {
            it.leftMargin = ctx.dp(4)
        })
        addView(right, LayoutParams(WRAP, MATCH, Gravity.END).also { it.rightMargin = ctx.dp(8) })
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
        background = if (pill) Colors.rounded(Colors.taskbar(ctx), ctx.dp(28).toFloat()) else android.graphics.drawable.ColorDrawable(Colors.taskbar(ctx))
        // Keep the scroll position across rebuilds (the strip used to jump back to the start).
        val keepX = scroll.scrollX
        center.removeAllViews()
        right.removeAllViews()
        appsRow.removeAllViews()
        val h = ctx.dp(Prefs.tbDp(ctx))
        val cell = minOf(h, ctx.dp(52))
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
            divider.setBackgroundColor(Colors.text(ctx) and 0x55FFFFFF)
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
        val navReserve = if (showNav) cell * (navCount + imeCount) + ctx.dp(92) else ctx.dp(4)
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
            }, LinearLayout.LayoutParams(cell, MATCH))
            right.addView(NavButton(ctx, 1).also { b ->
                b.background = ripple(); b.setOnClickListener { svc.global(AccessibilityService.GLOBAL_ACTION_HOME) }
            }, LinearLayout.LayoutParams(cell, MATCH))
            right.addView(NavButton(ctx, 2).also { b ->
                b.background = ripple(); b.setOnClickListener { svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS) }
            }, LinearLayout.LayoutParams(cell, MATCH))
        }
        val clock = TextClock(ctx)
        clock.format12Hour = "h:mm a"
        clock.format24Hour = "HH:mm"
        clock.textSize = 13f
        clock.setTextColor(Colors.text(ctx))
        clock.gravity = Gravity.CENTER
        clock.setPadding(ctx.dp(8), 0, ctx.dp(4), 0)
        right.addView(clock, LinearLayout.LayoutParams(WRAP, MATCH))
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
            line.background = Colors.rounded(Colors.accent(ctx), ctx.dp(2).toFloat())
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
        p.color = Colors.accent(context)
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
        p.color = Colors.text(context)
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
class NavButton(c: Context, private val kind: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(canvas: Canvas) {
        p.color = Colors.text(context)
        p.strokeWidth = context.dp(2).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.17f
        when (kind) {
            0 -> {
                val path = Path()
                path.moveTo(cx + r, cy - r); path.lineTo(cx - r, cy); path.lineTo(cx + r, cy + r); path.close()
                canvas.drawPath(path, p)
            }
            1 -> canvas.drawCircle(cx, cy, r, p)
            else -> canvas.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), r * 0.35f, r * 0.35f, p)
        }
    }
}


/** Dedicated taskbar search shortcut, placed beside the app-drawer button. */
class SearchButton(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        p.color = Colors.text(context)
        p.strokeWidth = context.dp(2).toFloat()
        val r = minOf(width, height) * 0.17f
        val cx = width / 2f - r * 0.18f
        val cy = height / 2f - r * 0.18f
        canvas.drawCircle(cx, cy, r, p)
        canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f, cx + r * 1.55f, cy + r * 1.55f, p)
    }
}
