package com.android.desktoplauncher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var col: LinearLayout
    private var activeSection: LinearLayout? = null

    private fun addSetting(v: android.view.View, params: LinearLayout.LayoutParams? = null) {
        if (params != null) (activeSection ?: col).addView(v, params) else (activeSection ?: col).addView(v)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setDecorFitsSystemWindows(false)
        val sv = ScrollView(this)
        sv.setBackgroundColor(Colors.surfaceSolid(this))
        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(20), dp(20), dp(30))
        sv.addView(col)
        UiText.applyDirection(sv, this)
        setContentView(sv)
        sv.setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            ins
        }
        build()
    }

    override fun onResume() { super.onResume(); build() }

    private fun head(t: String) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(18), dp(14), dp(18), dp(14))
        card.background = Colors.rounded(Colors.surfaceHigh(this), dp(24).toFloat())
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(14)
        col.addView(card, lp)
        activeSection = card

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        val symbol = TextView(this)
        symbol.text = when (t) {
            "LANGUAGE & DIRECTION" -> "文"
            "ICONS" -> "✦"
            "LAYOUT" -> "▦"
            "APPEARANCE" -> "◐"
            "TASKBAR / DOCK" -> "▤"
            "PERMISSIONS" -> "✓"
            "DESKTOP" -> "⌂"
            else -> "⚙"
        }
        symbol.textSize = 21f
        symbol.gravity = android.view.Gravity.CENTER
        symbol.setTextColor(Colors.iconFg(this))
        symbol.background = Colors.rounded(Colors.iconBg(this), dp(14).toFloat())
        val iconLp = LinearLayout.LayoutParams(dp(44), dp(44))
        iconLp.marginEnd = dp(14)
        row.addView(symbol, iconLp)

        val titleBox = LinearLayout(this)
        titleBox.orientation = LinearLayout.VERTICAL
        val v = TextView(this)
        v.text = UiText.text(this, t)
        v.textSize = 17f
        v.setTypeface(null, android.graphics.Typeface.BOLD)
        v.setTextColor(Colors.text(this))
        titleBox.addView(v)
        val subtitle = TextView(this)
        subtitle.text = when (t) {
            "LANGUAGE & DIRECTION" -> UiText.text(this, "Choose language and reading direction")
            "ICONS" -> UiText.text(this, "Icon size and app appearance")
            "LAYOUT" -> UiText.text(this, "Phone or desktop layout")
            "APPEARANCE" -> UiText.text(this, "Colours, blur and drawer behaviour")
            "TASKBAR / DOCK" -> UiText.text(this, "Taskbar, dock and navigation controls")
            "PERMISSIONS" -> UiText.text(this, "Access needed by launcher features")
            "DESKTOP" -> UiText.text(this, "Desktop items and wallpaper")
            else -> ""
        }
        subtitle.textSize = 12f
        subtitle.alpha = 0.78f
        subtitle.setTextColor(Colors.text(this))
        if (subtitle.text.isNotEmpty()) {
            val subLp = LinearLayout.LayoutParams(-1, -2)
            subLp.topMargin = dp(3)
            titleBox.addView(subtitle, subLp)
        }
        row.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
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

    private fun sw(title: String, key: String, def: Boolean) {
        val s = Switch(this)
        s.text = title
        s.textSize = 15f
        s.setTextColor(Colors.text(this))
        s.isChecked = Prefs.bool(this, key, def)
        s.setPadding(dp(2), dp(10), dp(2), dp(10))
        s.minHeight = dp(54)
        s.setOnCheckedChangeListener { _, on -> Prefs.setBool(this, key, on) }
        addSetting(s)
    }

    private fun btn(t: String, a: () -> Unit) {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setOnClickListener { a() }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(4)
        lp.bottomMargin = dp(4)
        addSetting(b, lp)
    }

    private fun go(i: Intent) { try { startActivity(i) } catch (_: Exception) { toast(UiText.text(this, "Cannot open")) } }

    private fun build() {
        col.removeAllViews()
        UiText.applyDirection(col, this)
        val title = TextView(this)
        title.text = UiText.text(this, "Desktop Launcher")
        title.textSize = 30f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.setTextColor(Colors.text(this))
        title.setPadding(dp(4), dp(8), dp(4), dp(22))
        col.addView(title)

        head("LANGUAGE & DIRECTION")
        note(UiText.text(this, "UI language"))
        val languageGroup = RadioGroup(this)
        listOf(UiText.text(this, "English"), UiText.text(this, "Urdu")).forEachIndexed { i, label ->
            val r = RadioButton(this); r.id = 700 + i; r.text = label; r.setTextColor(Colors.text(this)); r.setPadding(dp(2), dp(7), dp(2), dp(7)); languageGroup.addView(r)
        }
        languageGroup.check(700 + Prefs.language(this))
        languageGroup.setOnCheckedChangeListener { _, id -> if (id >= 700) Prefs.setLanguage(this, id - 700); build() }
        addSetting(languageGroup)
        note(UiText.text(this, "Layout direction"))
        val directionGroup = RadioGroup(this)
        listOf(UiText.text(this, "System default"), UiText.text(this, "Left to right (LTR)"), UiText.text(this, "Right to left (RTL)")).forEachIndexed { i, label ->
            val r = RadioButton(this); r.id = 710 + i; r.text = label; r.setTextColor(Colors.text(this)); r.setPadding(dp(2), dp(7), dp(2), dp(7)); directionGroup.addView(r)
        }
        directionGroup.check(710 + Prefs.direction(this))
        directionGroup.setOnCheckedChangeListener { _, id -> if (id >= 710) Prefs.setDirection(this, id - 710); UiText.applyDirection(col, this) }
        addSetting(directionGroup)
        note(UiText.text(this, "Changes apply immediately where possible; reopen the launcher if an old screen does not update."))

        head("ICONS")
        val rg = RadioGroup(this)
        val names = listOf(UiText.text(this, "Small"), UiText.text(this, "Normal"), UiText.text(this, "Large"))
        names.forEachIndexed { i, n ->
            val r = RadioButton(this)
            r.text = n
            r.id = 100 + i
            r.setTextColor(Colors.text(this))
            r.setPadding(dp(2), dp(7), dp(2), dp(7))
            rg.addView(r)
        }
        rg.check(100 + Prefs.iconMode(this))
        rg.setOnCheckedChangeListener { _, id -> Prefs.setIconMode(this, id - 100) }
        addSetting(rg)
        sw(UiText.text(this, "App drawer themed icons"), Prefs.K_THEMED, false)
        note(UiText.text(this, "Uses the wallpaper colours for apps that provide a monochrome icon (Android 13+)."))
        sw(UiText.text(this, "Show notification dots"), Prefs.K_DOTS, true)
        val notifOn = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        if (!notifOn) btn(UiText.text(this, "Allow notification access (needed for dots)")) { go(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        head("LAYOUT")
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

        head("APPEARANCE")
        sw(UiText.text(this, "Enable UI blur"), Prefs.K_BLUR, false)
        note(UiText.text(this, "Blurs only overlay windows such as the taskbar and menus. When off, solid Material You colours are used."))
        sw(UiText.text(this, "Show keyboard when app drawer opens"), Prefs.K_DRAWER_IME, false)
        note(UiText.text(this, "When off, the app drawer opens without automatically showing the keyboard. Back should hide the keyboard before closing the drawer."))

        head("TASKBAR / DOCK")
        val sw600 = resources.configuration.smallestScreenWidthDp
        note(UiText.text(this, "Smallest width") + ": $sw600 dp — " + UiText.text(this, if (sw600 >= 600) "tablet mode: the dock is merged into the taskbar (over all apps)." else "phone mode: normal dock on the home screen. At 600 dp or more the dock becomes the taskbar."))
        sw(UiText.text(this, "Show taskbar / dock"), Prefs.K_TASKBAR, true)
        sw(UiText.text(this, "Show Back / Home / Recents buttons"), Prefs.K_NAV, true)
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
        btn(UiText.text(this, "Turn on taskbar (Accessibility settings)")) { go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        head("PERMISSIONS")
        btn(UiText.text(this, if (Environment.isExternalStorageManager()) "All files access: allowed" else "Allow all files access (Desktop folder)")) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { go(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
        val c = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        btn(UiText.text(this, if (c) "Contacts search: allowed" else "Allow contacts (for search)")) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1)
        }
        btn(UiText.text(this, "Set as default home app")) { go(Intent(Settings.ACTION_HOME_SETTINGS)) }

        head("DESKTOP")
        btn(UiText.text(this, "Restore removed desktop items")) {
            Prefs.setList(this, Prefs.K_HIDDEN, emptyList()); toast(UiText.text(this, "Restored"))
        }
        btn(UiText.text(this, "Wallpaper & style")) { Launch.wallpapers(this) }
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g); build()
    }
}
