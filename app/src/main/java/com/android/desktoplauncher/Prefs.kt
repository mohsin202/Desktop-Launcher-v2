package com.android.desktoplauncher

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Tiny in-process event bus (launcher activity and taskbar service share a process). */
object Bus {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun add(l: () -> Unit) { listeners.add(l) }
    fun remove(l: () -> Unit) { listeners.remove(l) }
    fun fire() { main.post { for (l in listeners) l() } }
}

object Prefs {
    const val K_DOTS = "dots"
    const val K_THEMED = "themed"
    const val K_TASKBAR = "taskbar"
    const val K_NAV = "nav"
    const val K_TBH = "tbh"
    const val K_PINNED = "pinned"
    const val K_DESKAPPS = "deskApps"
    const val K_HIDDEN = "hidden"
    const val K_WIDGETS = "widgets"
    const val K_FREE = "freeWidgets"   // free-form clocks: "id,fx,fy,fw,fh" (fractions x10000 of the desktop)
    const val K_LWIDGETS = "lwidgets" // built-in launcher widgets: "id:type"
    const val K_BLUR = "blur"
    const val K_IME = "imebtn"
    const val K_POS = "pos"
    const val K_WINKEY = "winkey"
    const val K_SEEDED = "seeded"
    const val K_MODE = "mode"
    const val K_DRAWER_IME = "drawerIme"
    const val K_LANGUAGE = "uiLanguage" // 0 English, 1 Urdu
    const val K_DIRECTION = "uiDirection" // 0 system, 1 LTR, 2 RTL
    const val K_FOLDERS = "folders" // desktop folders (see FolderStore)
    const val K_RECENT = "recentSearches" // last searches, "|" separated
    const val K_DL_DISMISSED = "dlDismissed" // time (ms) when the "downloaded today" card was closed

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("desktop", Context.MODE_PRIVATE)

    fun language(c: Context) = sp(c).getInt(K_LANGUAGE, 0).coerceIn(0, 1)
    fun setLanguage(c: Context, v: Int) { sp(c).edit().putInt(K_LANGUAGE, v.coerceIn(0, 1)).apply(); Bus.fire() }
    fun direction(c: Context) = sp(c).getInt(K_DIRECTION, 0).coerceIn(0, 2)
    fun setDirection(c: Context, v: Int) { sp(c).edit().putInt(K_DIRECTION, v.coerceIn(0, 2)).apply(); Bus.fire() }

    fun iconMode(c: Context) = sp(c).getInt("iconMode", 1).coerceIn(0, 2)
    fun setIconMode(c: Context, v: Int) { sp(c).edit().putInt("iconMode", v).apply(); Bus.fire() }
    fun iconDp(c: Context) = intArrayOf(44, 60, 84)[iconMode(c)]

    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply(); Bus.fire() }

    fun getInt(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) { sp(c).edit().putInt(k, v).apply(); Bus.fire() }
    fun getStr(c: Context, k: String): String? = sp(c).getString(k, null)
    fun setStr(c: Context, k: String, v: String?, fire: Boolean = true) {
        sp(c).edit().apply { if (v == null) remove(k) else putString(k, v) }.apply(); if (fire) Bus.fire()
    }
    fun has(c: Context, k: String) = sp(c).contains(k)
    fun getLong(c: Context, k: String, d: Long) = sp(c).getLong(k, d)
    fun setLong(c: Context, k: String, v: Long) { sp(c).edit().putLong(k, v).apply() }

    /** 0 = automatic (smallest width >= 600dp = desktop), 1 = always phone, 2 = always desktop */
    fun layoutMode(c: Context) = sp(c).getInt("layout", 0)
    fun setLayoutMode(c: Context, v: Int) { sp(c).edit().putInt("layout", v).apply(); Bus.fire() }

    fun tbDp(c: Context) = sp(c).getInt(K_TBH, 48)
    fun setTbDp(c: Context, v: Int) { sp(c).edit().putInt(K_TBH, v).apply(); Bus.fire() }

    fun list(c: Context, k: String): List<String> =
        (sp(c).getString(k, "") ?: "").split("|").filter { it.isNotEmpty() }

    fun setList(c: Context, k: String, v: List<String>, fire: Boolean = true) {
        sp(c).edit().putString(k, v.joinToString("|")).apply()
        if (fire) Bus.fire()
    }

    fun toggleInList(c: Context, k: String, v: String) {
        val l = list(c, k).toMutableList()
        if (!l.remove(v)) l.add(v)
        setList(c, k, l)
    }
}
