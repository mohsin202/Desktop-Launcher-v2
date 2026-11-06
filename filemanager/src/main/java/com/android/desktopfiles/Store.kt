package com.android.desktopfiles

import android.content.Context
import android.os.Environment
import java.io.File

/** Small settings store (SharedPreferences "files"). Lists are kept as lines of text. */
object Store {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("files", Context.MODE_PRIVATE)

    fun str(c: Context, k: String, d: String): String = sp(c).getString(k, d) ?: d
    fun setStr(c: Context, k: String, v: String) { sp(c).edit().putString(k, v).apply() }
    fun bool(c: Context, k: String, d: Boolean): Boolean = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply() }
    fun has(c: Context, k: String): Boolean = sp(c).contains(k)

    fun list(c: Context, k: String): List<String> = (sp(c).getString(k, "") ?: "").split("\n").filter { it.isNotEmpty() }
    fun setList(c: Context, k: String, v: List<String>) { sp(c).edit().putString(k, v.joinToString("\n")).apply() }

    private fun ext() = Environment.getExternalStorageDirectory().absolutePath

    /** Sidebar / Quick access items: "dir:<path>" or "sp:<kind>" (apps, trash). The usual places are there from the start. */
    fun pins(c: Context): List<String> {
        if (!has(c, "pins")) {
            val e = ext()
            setList(c, "pins", listOf("dir:$e/Desktop", "dir:$e/Download", "sp:documents", "sp:pictures", "sp:music", "sp:videos", "sp:apps", "sp:trash"))
            setStr(c, "pinsV", "2")
        } else if (str(c, "pinsV", "1") != "2") {
            // Documents / Pictures / Music / Movies now show every folder on the device that holds such files
            val e = ext()
            val map = mapOf("dir:$e/Documents" to "sp:documents", "dir:$e/Pictures" to "sp:pictures", "dir:$e/Music" to "sp:music", "dir:$e/Movies" to "sp:videos")
            val l = list(c, "pins").map { map[it] ?: it }.distinct().toMutableList()
            if ("sp:apps" !in l) l.add("sp:apps")
            setList(c, "pins", l); setStr(c, "pinsV", "2")
        }
        return list(c, "pins")
    }

    fun pin(c: Context, item: String) { val l = pins(c); if (item !in l) setList(c, "pins", l + item) }
    fun unpin(c: Context, item: String) { setList(c, "pins", pins(c).filter { it != item }) }

    /** Folders that were opened lately (the address bar's drop-down). */
    fun recents(c: Context): List<String> = list(c, "recents")
    fun addRecent(c: Context, path: String) {
        setList(c, "recents", (listOf(path) + recents(c).filter { it != path }).take(12))
    }

    fun collapsed(c: Context, key: String, default: Boolean = false): Boolean = bool(c, "col:$key", default)
    fun setCollapsed(c: Context, key: String, v: Boolean) = setBool(c, "col:$key", v)
}

/** Where a tab is. */
sealed class Loc {
    object Home : Loc()
    data class Dir(val path: String, val only: String = "") : Loc()
    data class Search(val base: String, val q: String) : Loc()
    object Trash : Loc()
    /** Reserved for the next part (Documents / Pictures / Music / Videos grouped by folder, Apps). */
    data class Special(val kind: String) : Loc()
}

class Tab(var loc: Loc) {
    /** Second pane (dual pane view) */
    var right: Loc? = null
    val back = ArrayList<Loc>()
    val fwd = ArrayList<Loc>()
}

/** What a page (Home, a folder, the Recycle Bin) offers to the window around it. */
interface Page {
    val view: android.view.View
    fun refresh()
    fun destroy() {}
    /** Keyboard: returns true when the key was used. */
    fun onKey(code: Int, ctrl: Boolean, shift: Boolean): Boolean = false
}

interface Host {
    val act: android.app.Activity
    fun go(loc: Loc)
    fun newTab(loc: Loc)
    fun newWindow(loc: Loc)
    fun refreshAll()
    fun pinsChanged()
    fun showStatus()
    fun contentWidthDp(): Int
    fun openFile(f: File)
    /** The page tells which items are selected (the Details pane shows them). */
    fun selectionChanged(files: List<File>) {}
    /** Build the page of this tab again (the view type changed). */
    fun relayout() {}
    /** Dual pane: the folder shown by the other pane, or null. */
    fun otherPaneDir(pane: Int): File? = null
}
