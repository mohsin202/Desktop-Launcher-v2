package com.android.desktoplauncher

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Material 3 style on / off switch: outlined small thumb when off, filled track with a large thumb when on. */
class M3Switch(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f   // 0 = off, 1 = on
    private var anim: ValueAnimator? = null
    var checked: Boolean = false
        set(v) { if (field != v) { field = v; animateTo(if (v) 1f else 0f) } }
    var onChange: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        setOnClickListener { checked = !checked; onChange?.invoke(checked) }
    }

    fun setCheckedSilently(v: Boolean) { anim?.cancel(); checked = v; t = if (v) 1f else 0f; invalidate() }

    private fun animateTo(target: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(t, target).apply {
            duration = 160
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(context.dp(52), context.dp(32)) }

    private fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 0xFF
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * f).toInt()
        return (m(24) shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val on = Colors.accent(context)
        val offTrack = Colors.surfaceHigh(context)
        val outline = (Colors.text(context) and 0x00FFFFFF) or 0x99000000.toInt()
        val bw = context.dp(2).toFloat()
        // track
        p.style = Paint.Style.FILL
        p.color = mix(offTrack, on, t)
        cv.drawRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, p)
        // outline fades out as the switch turns on
        p.style = Paint.Style.STROKE
        p.strokeWidth = bw
        p.color = mix(outline, on, t)
        cv.drawRoundRect(RectF(bw / 2, bw / 2, w - bw / 2, h - bw / 2), h / 2f, h / 2f, p)
        // thumb: 16dp when off, 24dp when on
        val rOff = context.dp(8).toFloat(); val rOn = context.dp(12).toFloat()
        val r = rOff + (rOn - rOff) * t
        val x0 = h / 2f; val x1 = w - h / 2f
        val cx = x0 + (x1 - x0) * t
        p.style = Paint.Style.FILL
        p.color = mix(outline, 0xFFFFFFFF.toInt(), t)
        cv.drawCircle(cx, h / 2f, r, p)
    }
}

class SettingsActivity : Activity() {
    private class Pg(val id: String, val title: String, val sub: String, val sym: String, val build: () -> Unit)

    private lateinit var root: FrameLayout
    private lateinit var col: LinearLayout
    private var activeSection: LinearLayout? = null
    private var currentPage: String? = null
    private var pageScroll: ScrollView? = null
    private var refreshPreview: (() -> Unit)? = null

    private val widthDp get() = resources.configuration.screenWidthDp
    private val twoPane get() = widthDp >= 600
    private fun isRtl() = when (Prefs.direction(this)) {
        1 -> false
        2 -> true
        else -> resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    }

    private val pages: List<Pg> get() = listOf(
        Pg("GENERAL", "General", "Language, direction and layout", "⚙") { secGeneral() },
        Pg("ICON STYLE", "Icon style", "Icon size, themed icons and dots", "✦") { secIcons() },
        Pg("APPEARANCE", "APPEARANCE", "Colours, blur and drawer behaviour", "◐") { secAppearance() },
        Pg("DESKTOP", "DESKTOP", "Desktop items and wallpaper", "⌂") { secDesktop() },
        Pg("TASKBAR / DOCK", "TASKBAR / DOCK", "Taskbar, dock and navigation controls", "▤") { secTaskbar() },
        Pg("WINDOWS", "WINDOWS", "Freeform windows and sizes", "▢") { secWindows() },
        Pg("PERMISSIONS", "PERMISSIONS", "Access needed by launcher features", "✓") { secPermissions() }
    )

    /** Section titles are stored in capitals (they are the translation keys); show them in normal case in English. */
    private fun pgTitle(t: String): String {
        val u = UiText.text(this, t)
        return if (u == t && t == t.uppercase() && t.any { it.isLetter() }) t.lowercase().replaceFirstChar { it.uppercase() } else u
    }

    private fun addSetting(v: View, params: LinearLayout.LayoutParams? = null) {
        if (params != null) (activeSection ?: col).addView(v, params) else (activeSection ?: col).addView(v)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setDecorFitsSystemWindows(false)
        currentPage = b?.getString("page")
        root = FrameLayout(this)
        root.setBackgroundColor(Colors.surfaceSolid(this))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val bars = Insets.of(ins)
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            ins
        }
        render()
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); currentPage?.let { o.putString("page", it) } }
    override fun onResume() { super.onResume(); render() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!twoPane && currentPage != null) { currentPage = null; render() } else super.onBackPressed()
    }

    // ---- screen: list of sections, and one section page (side by side on wide screens)
    private fun render() {
        val keep = pageScroll?.scrollY ?: 0
        root.removeAllViews()
        UiText.applyDirection(root, this)
        refreshPreview = null
        if (twoPane) {
            val sel = currentPage ?: pages[0].id
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val left = ScrollView(this)
            left.addView(mainList(sel))
            row.addView(left, LinearLayout.LayoutParams(dp(360).coerceAtMost((widthDp * 0.42f * resources.displayMetrics.density).toInt()), -1))
            val right = pageView(sel, false)
            row.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
            root.addView(row, FrameLayout.LayoutParams(-1, -1))
        } else {
            val id = currentPage
            if (id == null) {
                val sv = ScrollView(this)
                sv.addView(mainList(null))
                root.addView(sv, FrameLayout.LayoutParams(-1, -1))
                pageScroll = null
            } else root.addView(pageView(id, true), FrameLayout.LayoutParams(-1, -1))
        }
        pageScroll?.post { pageScroll?.scrollTo(0, keep) }
    }

    private fun mainList(selected: String?): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(16), dp(16), dp(24))
        val title = TextView(this)
        title.text = UiText.text(this, "Settings")
        title.textSize = 30f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.setTextColor(Colors.text(this))
        title.setPadding(dp(8), dp(8), dp(8), dp(18))
        c.addView(title)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = Colors.rounded(Colors.surfaceHigh(this), dp(28).toFloat())
        box.setPadding(0, dp(6), 0, dp(6))
        for ((i, pg) in pages.withIndex()) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(18), dp(14), dp(18), dp(14))
            if (pg.id == selected) r.background = Colors.rounded(Colors.iconBg(this), dp(22).toFloat())
            val sym = TextView(this)
            sym.text = pg.sym
            sym.textSize = 20f
            sym.gravity = Gravity.CENTER
            sym.setTextColor(if (pg.id == selected) Colors.iconFg(this) else Colors.text(this))
            r.addView(sym, LinearLayout.LayoutParams(dp(40), dp(40)).also { it.marginEnd = dp(14) })
            val tb = LinearLayout(this)
            tb.orientation = LinearLayout.VERTICAL
            val t = TextView(this)
            t.text = pgTitle(pg.title)
            t.textSize = 17f
            t.setTextColor(Colors.text(this))
            tb.addView(t)
            val s = TextView(this)
            s.text = UiText.text(this, pg.sub)
            s.textSize = 12.5f
            s.alpha = 0.75f
            s.setTextColor(Colors.text(this))
            tb.addView(s)
            r.addView(tb, LinearLayout.LayoutParams(0, -2, 1f))
            r.setOnClickListener { currentPage = pg.id; render() }
            box.addView(r, LinearLayout.LayoutParams(-1, -2).also { it.setMargins(dp(6), 0, dp(6), 0) })
        }
        c.addView(box, LinearLayout.LayoutParams(-1, -2))
        val about = TextView(this)
        about.text = UiText.text(this, "Desktop Launcher")
        about.textSize = 12f
        about.alpha = 0.6f
        about.setTextColor(Colors.text(this))
        about.setPadding(dp(8), dp(16), dp(8), 0)
        c.addView(about)
        return c
    }

    private fun pageView(id: String, withBack: Boolean): View {
        val pg = pages.first { it.id == id }
        val sv = ScrollView(this)
        pageScroll = sv
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(12), dp(16), dp(30))
        if (withBack) {
            val back = TextView(this)
            back.text = if (isRtl()) "→" else "←"
            back.textSize = 26f
            back.setTextColor(Colors.text(this))
            back.setPadding(dp(8), dp(6), dp(8), dp(6))
            back.setOnClickListener { currentPage = null; render() }
            c.addView(back, LinearLayout.LayoutParams(-2, -2))
        }
        val title = TextView(this)
        title.text = pgTitle(pg.title)
        title.textSize = 32f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.setTextColor(Colors.text(this))
        title.setPadding(dp(8), dp(if (withBack) 18 else 28), dp(8), dp(18))
        c.addView(title)
        col = c
        activeSection = null
        pg.build()
        sv.addView(c)
        return sv
    }

    /** A rounded card; an optional small coloured title sits above it. */
    private fun card(title: String? = null) {
        if (title != null) {
            val t = TextView(this)
            t.text = UiText.text(this, title)
            t.textSize = 13.5f
            t.setTextColor(Colors.accent(this))
            t.setPadding(dp(10), dp(8), dp(10), dp(8))
            col.addView(t)
        }
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(18), dp(10), dp(18), dp(10))
        card.background = Colors.rounded(Colors.surfaceHigh(this), dp(24).toFloat())
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(12)
        col.addView(card, lp)
        activeSection = card
    }

    private fun note(t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 12f
        v.alpha = 0.72f
        v.setTextColor(Colors.text(this))
        v.setPadding(dp(2), dp(4), dp(2), dp(8))
        addSetting(v)
    }

    private fun sw(title: String, key: String, def: Boolean, onChange: (() -> Unit)? = null) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(56)
        val t = TextView(this)
        t.text = title
        t.textSize = 15f
        t.setTextColor(Colors.text(this))
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(12) })
        val s = M3Switch(this)
        s.setCheckedSilently(Prefs.bool(this, key, def))
        s.onChange = { on -> Prefs.setBool(this, key, on); onChange?.invoke() }
        row.addView(s)
        row.setOnClickListener { s.performClick() }
        addSetting(row)
    }

    /** An action: a plain tappable row (not a raised grey button). */
    private fun btn(t: String, a: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(52)
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 15f
        tv.setTextColor(Colors.text(this))
        row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(12) })
        val ar = TextView(this)
        ar.text = if (isRtl()) "‹" else "›"
        ar.textSize = 22f
        ar.alpha = 0.6f
        ar.setTextColor(Colors.text(this))
        row.addView(ar)
        row.setOnClickListener { a() }
        addSetting(row)
    }

    /**
     * A permission / system access: the name with a switch showing whether it is on. Tapping opens the system screen where it is
     * turned on or off (an app cannot flip these itself); the switch shows the real state again when you come back.
     */
    private fun permSw(title: String, on: Boolean, open: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(56)
        val t = TextView(this)
        t.text = title
        t.textSize = 15f
        t.setTextColor(Colors.text(this))
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(12) })
        val s = M3Switch(this)
        s.setCheckedSilently(on)
        s.onChange = { _ -> s.setCheckedSilently(on); open() }
        row.addView(s)
        row.setOnClickListener { s.performClick() }
        addSetting(row)
    }

    private fun go(i: Intent) { try { startActivity(i) } catch (_: Exception) { toast(UiText.text(this, "Cannot open")) } }

    /** Chips in one row (like the "Icon pack / Themed icon" chips): the chosen one is filled. */
    private fun chips(names: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(6), 0, dp(10))
        names.forEachIndexed { i, n ->
            val ch = TextView(this)
            ch.text = n
            ch.textSize = 14f
            ch.gravity = Gravity.CENTER
            ch.setPadding(dp(16), dp(9), dp(16), dp(9))
            if (i == selected) { ch.background = Colors.rounded(Colors.iconBg(this), dp(20).toFloat()); ch.setTextColor(Colors.iconFg(this)) }
            else {
                ch.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0); cornerRadius = dp(20).toFloat(); setStroke(dp(1), (Colors.text(this@SettingsActivity) and 0x00FFFFFF) or 0x66000000)
                }
                ch.setTextColor(Colors.text(this))
            }
            ch.setOnClickListener { onPick(i) }
            row.addView(ch, LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(8) })
        }
        val hs = android.widget.HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(row)
        addSetting(hs)
    }

    // ---- pages
    private fun secGeneral() {
        card("LANGUAGE & DIRECTION")
        note(UiText.text(this, "UI language"))
        val languageGroup = RadioGroup(this)
        listOf(UiText.text(this, "English"), UiText.text(this, "Urdu")).forEachIndexed { i, label ->
            val r = RadioButton(this); r.id = 700 + i; r.text = label; r.setTextColor(Colors.text(this)); r.setPadding(dp(2), dp(7), dp(2), dp(7)); languageGroup.addView(r)
        }
        languageGroup.check(700 + Prefs.language(this))
        languageGroup.setOnCheckedChangeListener { _, id -> if (id >= 700) Prefs.setLanguage(this, id - 700); render() }
        addSetting(languageGroup)
        note(UiText.text(this, "Layout direction"))
        val directionGroup = RadioGroup(this)
        listOf(UiText.text(this, "System default"), UiText.text(this, "Left to right (LTR)"), UiText.text(this, "Right to left (RTL)")).forEachIndexed { i, label ->
            val r = RadioButton(this); r.id = 710 + i; r.text = label; r.setTextColor(Colors.text(this)); r.setPadding(dp(2), dp(7), dp(2), dp(7)); directionGroup.addView(r)
        }
        directionGroup.check(710 + Prefs.direction(this))
        directionGroup.setOnCheckedChangeListener { _, id -> if (id >= 710) Prefs.setDirection(this, id - 710); render() }
        addSetting(directionGroup)
        note(UiText.text(this, "Changes apply immediately where possible; reopen the launcher if an old screen does not update."))
        card("LAYOUT")
        val lg = RadioGroup(this)
        listOf(UiText.text(this, "Automatic (600 dp or more = desktop taskbar)"), UiText.text(this, "Always phone (dock)"), UiText.text(this, "Always desktop (taskbar)")).forEachIndexed { i, n ->
            val r = RadioButton(this)
            r.text = n
            r.id = 200 + i
            r.setTextColor(Colors.text(this))
            r.setPadding(dp(2), dp(7), dp(2), dp(7))
            lg.addView(r)
        }
        lg.check(200 + Prefs.layoutMode(this))
        lg.setOnCheckedChangeListener { _, id -> Prefs.setLayoutMode(this, id - 200) }
        addSetting(lg)
    }

    private fun secIcons() {
        card()
        // preview: a few real icons the way they currently look
        val pv = FrameLayout(this)
        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = dp(22).toFloat()
        bg.setColor(Colors.surface(this))
        pv.background = bg
        try {
            val wp = android.app.WallpaperManager.getInstance(this).drawable
            if (wp != null) pv.background = android.graphics.drawable.InsetDrawable(wp, 0).also { }
        } catch (_: Throwable) {}
        pv.clipToOutline = true
        pv.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(22).toFloat()) }
        }
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(dp(14), dp(14), dp(14), dp(14))
        pv.addView(grid, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        val fill = {
            grid.removeAllViews()
            val apps = AppRepo.apps(this).take(8)
            val iconPx = dp(when (Prefs.iconMode(this)) { 0 -> 40; 2 -> 58; else -> 48 })
            for (r in 0 until 2) {
                val line = LinearLayout(this)
                line.orientation = LinearLayout.HORIZONTAL
                for (cc in 0 until 4) {
                    val a = apps.getOrNull(r * 4 + cc)
                    val cell = LinearLayout(this)
                    cell.orientation = LinearLayout.VERTICAL
                    cell.gravity = Gravity.CENTER_HORIZONTAL
                    if (a != null) {
                        val iv = ImageView(this)
                        iv.setImageDrawable(Icons.app(this, a.comp, false))
                        cell.addView(iv, LinearLayout.LayoutParams(iconPx, iconPx))
                        val lb = TextView(this)
                        lb.text = a.label
                        lb.textSize = 11f
                        lb.maxLines = 1
                        lb.ellipsize = android.text.TextUtils.TruncateAt.END
                        lb.setTextColor(0xFFFFFFFF.toInt())
                        lb.setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
                        lb.gravity = Gravity.CENTER
                        cell.addView(lb, LinearLayout.LayoutParams(dp(64), -2))
                    }
                    line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                }
                grid.addView(line, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) })
            }
        }
        fill()
        refreshPreview = fill
        addSetting(pv, LinearLayout.LayoutParams(-1, dp(210)).also { it.topMargin = dp(8); it.bottomMargin = dp(8) })

        note(UiText.text(this, "Icon size"))
        chips(listOf(UiText.text(this, "Small"), UiText.text(this, "Normal"), UiText.text(this, "Large")), Prefs.iconMode(this)) { i ->
            Prefs.setIconMode(this, i); render()
        }
        card("Themed icons")
        sw(UiText.text(this, "Enable themed icons"), Prefs.K_THEMED, false) { refreshPreview?.invoke() }
        sw(UiText.text(this, "Themed icon on unsupported icons"), Prefs.K_THEMED_ALL, false) { refreshPreview?.invoke() }
        note(UiText.text(this, "Applies to the home screen and the app drawer. Apps without a monochrome icon are turned into one-colour icons when the second option is on."))
        card("Notification dots")
        sw(UiText.text(this, "Show notification dots"), Prefs.K_DOTS, true)
        val notifOn = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        permSw(UiText.text(this, "Notification access (needed for dots)"), notifOn) { go(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun secAppearance() {
        card()
        sw(UiText.text(this, "Enable UI blur"), Prefs.K_BLUR, false)
        sw(UiText.text(this, "Enable glass blur"), Prefs.K_GLASS, false)
        note(UiText.text(this, "Makes the app drawer, menus, folders, widget sheet and taskbar frosted glass, like the Glass style of the special clock."))
        note(UiText.text(this, "Blurs only overlay windows such as the taskbar and menus. When off, solid Material You colours are used."))
        sw(UiText.text(this, "Show keyboard when app drawer opens"), Prefs.K_DRAWER_IME, false)
        note(UiText.text(this, "When off, the app drawer opens without automatically showing the keyboard. Back should hide the keyboard before closing the drawer."))
    }

    private fun secWindows() {
        card()
        sw(UiText.text(this, "Open apps in freeform windows"), Prefs.K_FREEFORM, false)
        note(UiText.text(this, "Apps opened from the home screen, app drawer, taskbar and search open in resizable windows, if the system allows it."))
        val wg = RadioGroup(this)
        listOf("Phone", "Tablet", "Desktop").forEachIndexed { i, n ->
            val r = RadioButton(this)
            r.text = UiText.text(this, n)
            r.id = 300 + i
            r.setTextColor(Colors.text(this))
            r.setPadding(dp(2), dp(7), dp(2), dp(7))
            wg.addView(r)
        }
        wg.check(300 + Prefs.getInt(this, Prefs.K_WINSIZE, 2))
        wg.setOnCheckedChangeListener { _, id -> Prefs.setInt(this, Prefs.K_WINSIZE, id - 300) }
        addSetting(wg)
        note(UiText.text(this, "Window size. You can also pick a size for one app from its menu: Window size."))
        sw(UiText.text(this, "Freeform helper window (older Android)"), Prefs.K_FFHACK, false)
        note(UiText.text(this, "Opens an invisible 1x1 freeform window first, like the Taskbar app does, so that apps opened afterwards also open as windows."))
        note(UiText.text(this, "Special permission (optional)") + ": " + UiText.text(this, if (Launch.hasSecure(this)) "granted" else "not granted — tap the button to copy the adb command"))
        btn(UiText.text(this, "Freeform test (show details)")) {
            val report = Launch.diagnose(this)
            android.app.AlertDialog.Builder(this).setTitle(UiText.text(this, "Freeform test (show details)")).setMessage(report)
                .setPositiveButton(UiText.text(this, "Copy")) { _, _ ->
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("freeform", report))
                }.setNegativeButton("OK", null).show()
        }
        btn(UiText.text(this, if (Launch.hasSecure(this)) "Switch on system freeform support" else "Copy adb command for the special permission")) {
            if (Launch.hasSecure(this)) toast(UiText.text(this, if (Launch.ensureFreeform(this)) "Freeform support is on" else "Could not change the system setting"))
            else {
                (getSystemService(android.content.ClipboardManager::class.java)).setPrimaryClip(android.content.ClipData.newPlainText("adb", Launch.ADB_GRANT))
                toast(UiText.text(this, "Copied. Run it from a computer with adb, then reopen the settings."))
            }
        }
    }

    private fun secPermissions() {
        val notifOn = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        card("Special access")
        note(UiText.text(this, "Special accesses the launcher can use. Each button opens the system screen where you turn it on."))
        val usageOn = try {
            val ao = getSystemService(android.app.AppOpsManager::class.java)
            ao.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) { false }
        val pkgUri = Uri.parse("package:$packageName")
        fun perm(name: String, on: Boolean, open: () -> Unit) {
            permSw(UiText.text(this, name), on, open)
        }
        perm("Usage access", usageOn) { go(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri)) }
        perm("Display over other apps", Settings.canDrawOverlays(this)) { go(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)) }
        perm("Modify system settings", Settings.System.canWrite(this)) { go(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)) }
        perm("All files access", Environment.isExternalStorageManager()) { go(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri)) }
        perm("Accessibility (taskbar)", TaskbarService.instance != null) { go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        perm("Notification access", notifOn) { go(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        card("More")
        val c = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        permSw(UiText.text(this, "Contacts (for search)"), c) { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1) }
        val isHome = try {
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
        } catch (_: Exception) { false }
        permSw(UiText.text(this, "Default home app"), isHome) { go(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    private fun secTaskbar() {
        val notifOn = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        card()
        val sw600 = resources.configuration.smallestScreenWidthDp
        note(UiText.text(this, "Smallest width") + ": $sw600 dp — " + UiText.text(this, if (sw600 >= 600) "tablet mode: the dock is merged into the taskbar (over all apps)." else "phone mode: normal dock on the home screen. At 600 dp or more the dock becomes the taskbar."))
        sw(UiText.text(this, "Show taskbar / dock"), Prefs.K_TASKBAR, true)
        sw(UiText.text(this, "Show Back / Home / Recents buttons"), Prefs.K_NAV, true)
        sw(UiText.text(this, "Taskbar takes the colour of the open app (experimental)"), Prefs.K_APPCOLOR, false)
        note(UiText.text(this, "Look of the Back / Home / Recents buttons on the taskbar"))
        val ng = RadioGroup(this)
        NavStyle.names.forEachIndexed { i, n ->
            val r = RadioButton(this)
            r.text = UiText.text(this, n)
            r.id = 400 + i
            r.setTextColor(Colors.text(this))
            r.setPadding(dp(2), dp(7), dp(2), dp(7))
            ng.addView(r)
        }
        ng.check(400 + Prefs.getInt(this, Prefs.K_NAVSTYLE, NavStyle.SYSTEM))
        ng.setOnCheckedChangeListener { _, id -> Prefs.setInt(this, Prefs.K_NAVSTYLE, id - 400) }
        addSetting(ng)
        for ((label, key, def) in listOf(
            Triple("Long press Back", Prefs.K_LP_BACK, NavActions.NONE),
            Triple("Long press Home", Prefs.K_LP_HOME, NavActions.ASSIST),
            Triple("Long press Recents", Prefs.K_LP_RECENTS, NavActions.NONE))) {
            val cur = Prefs.getInt(this, key, def).coerceIn(0, NavActions.names.size - 1)
            val title = UiText.text(this, label)
            btn(title + ": " + UiText.text(this, NavActions.names[cur])) {
                android.app.AlertDialog.Builder(this).setTitle(title)
                    .setSingleChoiceItems(NavActions.names.map { UiText.text(this, it) }.toTypedArray(), cur) { d, which ->
                        Prefs.setInt(this, key, which); d.dismiss(); recreate()
                    }.setNegativeButton("OK", null).show()
            }
        }
        sw(UiText.text(this, "Show language switch icon while typing"), Prefs.K_IME, true)
        sw(UiText.text(this, "Windows key opens the app drawer"), Prefs.K_WINKEY, true)
        val h = TextView(this)
        h.setTextColor(Colors.text(this))
        h.text = UiText.text(this, "Taskbar height") + ": ${Prefs.tbDp(this)} dp"
        addSetting(h)
        val sb = SeekBar(this)
        sb.max = 44
        sb.progress = Prefs.tbDp(this) - 36
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { h.text = UiText.text(this@SettingsActivity, "Taskbar height") + ": ${p + 36} dp" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { Prefs.setTbDp(this@SettingsActivity, (s?.progress ?: 12) + 36) }
        })
        addSetting(sb)
        note(UiText.text(this, "Match this to the system navigation bar height so the taskbar covers it exactly."))
        permSw(UiText.text(this, "Taskbar service (Accessibility)"), TaskbarService.instance != null) { go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun secDesktop() {
        card()
        btn(UiText.text(this, "Restore removed desktop items")) {
            Prefs.setList(this, Prefs.K_HIDDEN, emptyList()); toast(UiText.text(this, "Restored"))
        }
        btn(UiText.text(this, "Wallpaper & style")) { Launch.wallpapers(this) }
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g); render()
    }
}
