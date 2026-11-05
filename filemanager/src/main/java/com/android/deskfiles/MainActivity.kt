package com.android.deskfiles

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Test file manager: browse folders, Copy, Cut, Paste. Nothing else.
 * Copy / Cut write the system clipboard in the same format the Desktop Launcher uses, so files can be copied or cut
 * here and pasted on the launcher's desktop, and the other way round.
 */
class MainActivity : Activity() {
    companion object {
        /** Clipboard label and first line: "DESKTOPFILES", then "copy" or "cut", then one absolute path per line. */
        const val LABEL = "desktop-files"
        const val MAGIC = "DESKTOPFILES"
    }

    private lateinit var pathTv: TextView
    private lateinit var list: ListView
    private lateinit var copyBtn: Button
    private lateinit var cutBtn: Button
    private lateinit var pasteBtn: Button
    private lateinit var banner: TextView
    private var dir: File = Environment.getExternalStorageDirectory()
    private var entries: List<File> = emptyList()
    private val selected = LinkedHashSet<String>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        actionBar?.hide()
        window.setDecorFitsSystemWindows(false)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left + dp(12), bars.top + dp(8), bars.right + dp(12), bars.bottom + dp(8))
            ins
        }

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val up = Button(this); up.text = "↑"; up.isAllCaps = false
        up.setOnClickListener { goUp() }
        top.addView(up, LinearLayout.LayoutParams(dp(56), dp(48)))
        val desk = Button(this); desk.text = "Desktop"; desk.isAllCaps = false
        desk.setOnClickListener { open(File(Environment.getExternalStorageDirectory(), "Desktop").also { it.mkdirs() }) }
        top.addView(desk, LinearLayout.LayoutParams(-2, dp(48)))
        pathTv = TextView(this)
        pathTv.textSize = 13f
        pathTv.maxLines = 2
        pathTv.setPadding(dp(8), 0, 0, 0)
        top.addView(pathTv, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        copyBtn = Button(this); copyBtn.text = "Copy"; copyBtn.isAllCaps = false
        cutBtn = Button(this); cutBtn.text = "Cut"; cutBtn.isAllCaps = false
        pasteBtn = Button(this); pasteBtn.text = "Paste"; pasteBtn.isAllCaps = false
        copyBtn.setOnClickListener { setClip(false) }
        cutBtn.setOnClickListener { setClip(true) }
        pasteBtn.setOnClickListener { paste() }
        for (x in listOf(copyBtn, cutBtn, pasteBtn)) bar.addView(x, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        banner = TextView(this)
        banner.text = "Tap here: allow access to all files"
        banner.setPadding(dp(8), dp(10), dp(8), dp(10))
        banner.setTextColor(Color.WHITE)
        banner.setBackgroundColor(0xFFB3261E.toInt())
        banner.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
        root.addView(banner, LinearLayout.LayoutParams(-1, -2))

        list = ListView(this)
        list.adapter = adapter
        list.divider = null
        list.setOnItemClickListener { _, _, p, _ ->
            val f = entries[p]
            if (f.isDirectory && selected.isEmpty()) open(f) else toggle(f)
        }
        list.setOnItemLongClickListener { _, _, p, _ -> toggle(entries[p]); true }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        banner.visibility = if (Environment.isExternalStorageManager()) View.GONE else View.VISIBLE
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (selected.isNotEmpty()) { selected.clear(); refresh() } else if (!goUp()) super.onBackPressed() }

    private fun goUp(): Boolean {
        val root = Environment.getExternalStorageDirectory()
        if (dir.absolutePath == root.absolutePath) return false
        open(dir.parentFile ?: return false)
        return true
    }

    private fun open(d: File) { dir = d; selected.clear(); refresh() }

    private fun toggle(f: File) {
        if (!selected.add(f.absolutePath)) selected.remove(f.absolutePath)
        refresh()
    }

    private fun refresh() {
        pathTv.text = dir.absolutePath
        entries = (dir.listFiles()?.toList() ?: emptyList()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        adapter.notifyDataSetChanged()
        val any = selected.isNotEmpty()
        copyBtn.isEnabled = any; cutBtn.isEnabled = any
        copyBtn.alpha = if (any) 1f else 0.4f; cutBtn.alpha = if (any) 1f else 0.4f
        val has = hasClip()
        pasteBtn.isEnabled = true; pasteBtn.alpha = if (has) 1f else 0.6f
    }

    // ---- clipboard (shared with the launcher)
    // Same file the launcher writes: some systems hide the clipboard from an app that is not in front yet.
    private val shareFile: File get() = File(Environment.getExternalStorageDirectory(), ".deskclip")
    private fun fileFresh(): Boolean = try { shareFile.exists() && System.currentTimeMillis() - shareFile.lastModified() < 60 * 60 * 1000L } catch (_: Exception) { false }

    private fun hasClip(): Boolean = (try {
        getSystemService(ClipboardManager::class.java).primaryClipDescription?.label?.toString() == LABEL
    } catch (_: Exception) { false }) || fileFresh()

    private fun clipText(): String? {
        try {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            if (clip != null && clip.description.label?.toString() == LABEL) clip.getItemAt(0).text?.toString()?.let { return it }
        } catch (_: Exception) {}
        return if (fileFresh()) try { shareFile.readText() } catch (_: Exception) { null } else null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::pasteBtn.isInitialized) refresh()
    }

    private fun setClip(cut: Boolean) {
        if (selected.isEmpty()) return
        val text = MAGIC + "\n" + (if (cut) "cut" else "copy") + "\n" + selected.joinToString("\n")
        try { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(LABEL, text)) } catch (_: Exception) {}
        try { shareFile.writeText(text) } catch (_: Exception) {}
        toast(if (cut) "Cut" else "Copied")
        selected.clear()
        refresh()
    }

    private fun paste() {
        val cm = getSystemService(ClipboardManager::class.java)
        val lines = clipText()?.lines() ?: return toast("Nothing to paste")
        if (lines.size < 3 || lines[0] != MAGIC) return toast("Nothing to paste")
        val cut = lines[1] == "cut"
        val paths = lines.drop(2).filter { it.isNotBlank() }
        val target = dir
        Thread {
            var n = 0
            for (p in paths) {
                val src = File(p)
                if (!src.exists()) continue
                // never paste a folder into itself
                if (src.isDirectory && target.absolutePath.startsWith(src.absolutePath)) continue
                val dst = unique(target, src.name)
                try {
                    if (cut) { if (!src.renameTo(dst)) { copyRec(src, dst); src.deleteRecursively() } } else copyRec(src, dst)
                    n++
                } catch (_: Exception) {}
            }
            runOnUiThread {
                if (cut && n > 0) { try { cm.clearPrimaryClip() } catch (_: Exception) {}; try { shareFile.delete() } catch (_: Exception) {} }
                toast(if (n > 0) "Pasted" else "Nothing pasted")
                refresh()
            }
        }.start()
    }

    private fun unique(d: File, name: String): File {
        var f = File(d, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 2
        while (f.exists()) { f = File(d, "$base ($i)$ext"); i++ }
        return f
    }

    private fun copyRec(src: File, dst: File) {
        if (src.isDirectory) { dst.mkdirs(); src.listFiles()?.forEach { copyRec(it, File(dst, it.name)) } }
        else src.copyTo(dst, overwrite = false)
    }

    // ---- list rows
    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(p: Int) = entries[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val f = entries[p]
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(8), dp(10), dp(8), dp(10))
            val sel = f.absolutePath in selected
            if (sel) row.setBackgroundColor(0x33888888)
            val ic = TextView(this@MainActivity); ic.text = if (f.isDirectory) "📁" else "📄"; ic.textSize = 22f
            row.addView(ic, LinearLayout.LayoutParams(dp(40), -2))
            val col = LinearLayout(this@MainActivity); col.orientation = LinearLayout.VERTICAL
            val n = TextView(this@MainActivity); n.text = f.name; n.textSize = 15f; n.maxLines = 1
            n.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            col.addView(n)
            val s = TextView(this@MainActivity); s.textSize = 12f; s.alpha = 0.6f
            s.text = if (f.isDirectory) "Folder" else Formatter.formatShortFileSize(this@MainActivity, f.length())
            col.addView(s)
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            val t = TextView(this@MainActivity); t.text = if (sel) "●" else "○"; t.textSize = 22f
            t.setPadding(dp(12), 0, dp(12), 0)
            t.setOnClickListener { toggle(f) }
            row.addView(t)
            return row
        }
    }
}
