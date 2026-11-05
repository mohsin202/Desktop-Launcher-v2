package com.android.desktopfiles

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Trash for files and folders (same as the launcher's, so both see the same bin). A trashed item stays in its own folder and is
 * renamed ".trashed-<expiry>-<name>" (expiry = Unix time 30 days ahead). Expired items are deleted when the list is read.
 */
object TrashStore {
    private val PAT = Regex("^\\.trashed-(\\d+)-(.+)$")
    const val DAYS = 30

    class Item(val file: File, val name: String, val expiry: Long) {
        val daysLeft: Int get() = maxOf(0, Math.ceil((expiry - System.currentTimeMillis() / 1000) / 86400.0).toInt())
        val size: Long get() = try { if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length() } catch (_: Exception) { 0L }
    }

    private fun scan(c: Context, vararg paths: String) {
        try { MediaScannerConnection.scanFile(c.applicationContext, paths, null, null) } catch (_: Exception) {}
    }

    /** Runs on a worker thread. */
    fun list(c: Context): List<Item> {
        val out = LinkedHashMap<String, Item>()
        fun add(f: File) {
            val m = PAT.matchEntire(f.name) ?: return
            if (!f.exists() || out.containsKey(f.absolutePath)) return
            out[f.absolutePath] = Item(f, m.groupValues[2], m.groupValues[1].toLongOrNull() ?: 0L)
        }
        try {
            val b = Bundle()
            b.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            c.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.Files.FileColumns.DATA), b, null)?.use {
                while (it.moveToNext()) it.getString(0)?.let { p -> add(File(p)) }
            }
        } catch (_: Exception) {}
        if (Environment.isExternalStorageManager()) {
            fun walk(d: File, depth: Int) {
                if (depth > 6) return
                val kids = try { d.listFiles() } catch (_: Exception) { null } ?: return
                for (k in kids) {
                    if (k.name.startsWith(".trashed-")) { add(k); continue }
                    if (k.isDirectory && !(k.name == "Android" && depth == 0)) walk(k, depth + 1)
                }
            }
            walk(Fs.ext(), 0)
        }
        val now = System.currentTimeMillis() / 1000
        val keep = ArrayList<Item>()
        for (it in out.values) if (it.expiry in 1 until now) deleteForever(c, it) else keep += it
        return keep.sortedByDescending { it.expiry }
    }

    fun trash(c: Context, f: File): Boolean {
        val exp = System.currentTimeMillis() / 1000 + DAYS * 86400L
        return try {
            val t = File(f.parentFile, ".trashed-$exp-${f.name}")
            val ok = f.renameTo(t)
            if (ok && t.isFile) scan(c, f.absolutePath, t.absolutePath)
            ok
        } catch (_: Exception) { false }
    }

    fun restore(c: Context, it: Item): Boolean = try {
        val t = Fs.unique(it.file.parentFile ?: Fs.desktop(), it.name)
        val ok = it.file.renameTo(t)
        if (ok && t.isFile) scan(c, it.file.absolutePath, t.absolutePath)
        ok
    } catch (_: Exception) { false }

    fun deleteForever(c: Context, it: Item): Boolean = try {
        val wasFile = it.file.isFile
        val ok = it.file.deleteRecursively()
        if (wasFile) scan(c, it.file.absolutePath)
        ok
    } catch (_: Exception) { false }
}
