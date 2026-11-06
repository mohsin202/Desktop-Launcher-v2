package com.android.desktopfiles

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.File

/**
 * Clipboard shared with the Desktop Launcher: label "desktop-files", text "DESKTOPFILES\n(copy|cut)\n<paths>".
 * The same text is also kept in /sdcard/.deskclip (valid for an hour), because some systems hide the clipboard
 * from an app that is not in front yet.
 */
object Clip {
    const val LABEL = "desktop-files"
    const val MAGIC = "DESKTOPFILES"
    private const val TTL = 60 * 60 * 1000L

    class Content(val cut: Boolean, val files: List<File>)

    private val shareFile: File get() = File(Fs.ext(), ".deskclip")
    private fun fileFresh(): Boolean = try { shareFile.exists() && System.currentTimeMillis() - shareFile.lastModified() < TTL } catch (_: Exception) { false }

    fun set(c: Context, files: List<File>, cut: Boolean) {
        if (files.isEmpty()) return
        val text = MAGIC + "\n" + (if (cut) "cut" else "copy") + "\n" + files.joinToString("\n") { it.absolutePath }
        try { c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(LABEL, text)) } catch (_: Exception) {}
        try { shareFile.writeText(text) } catch (_: Exception) {}
    }

    fun has(c: Context): Boolean = (try {
        c.getSystemService(ClipboardManager::class.java).primaryClipDescription?.label?.toString() == LABEL
    } catch (_: Exception) { false }) || fileFresh()

    private fun text(c: Context): String? {
        try {
            val clip = c.getSystemService(ClipboardManager::class.java).primaryClip
            if (clip != null && clip.description.label?.toString() == LABEL) clip.getItemAt(0).text?.toString()?.let { return it }
        } catch (_: Exception) {}
        return if (fileFresh()) try { shareFile.readText() } catch (_: Exception) { null } else null
    }

    fun read(c: Context): Content? {
        val lines = text(c)?.lines() ?: return null
        if (lines.size < 3 || lines[0] != MAGIC) return null
        val files = lines.drop(2).filter { it.isNotBlank() }.map { File(it) }.filter { it.exists() || RootSu.state == 1 }
        return if (files.isEmpty()) null else Content(lines[1] == "cut", files)
    }

    fun clear(c: Context) {
        try { c.getSystemService(ClipboardManager::class.java).clearPrimaryClip() } catch (_: Exception) {}
        try { shareFile.delete() } catch (_: Exception) {}
    }
}
