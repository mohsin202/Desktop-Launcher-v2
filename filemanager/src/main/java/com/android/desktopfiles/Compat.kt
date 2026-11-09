package com.android.desktopfiles

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import java.io.File
import java.util.concurrent.Executor

/* Small bridges so the app runs from Android 7 (API 24) up to the newest version: each one uses the modern API where it exists and an older equivalent otherwise. */

private val uiHandler = Handler(Looper.getMainLooper())
private val uiExec = Executor { uiHandler.post(it) }

/** Runs work on the main thread (Context.getMainExecutor only exists from Android 9). */
val Context.mainExec: Executor get() = uiExec

/** Hover/long-press label (View.tooltipText only exists from Android 8). */
fun View.tip(s: String) { if (Build.VERSION.SDK_INT >= 26) tooltipText = s }

/** All files access: the special permission from Android 11, the ordinary storage permission before that. */
fun Context.hasAllFiles(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED

object Compat {
    val MUTABLE: Int get() = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    /** A receiver only this app can send to (the flag exists from Android 13; before that the intent is limited by its package). */
    fun registerPrivate(c: Context, r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else c.registerReceiver(r, f)
    }

    fun isLink(f: File): Boolean = try {
        if (Build.VERSION.SDK_INT >= 26) java.nio.file.Files.isSymbolicLink(f.toPath())
        else { val par = f.parentFile; val c = if (par == null) f else File(par.canonicalFile, f.name); c.canonicalFile != c.absoluteFile }
    } catch (_: Throwable) { false }

    /** "a/./b/../c" → "a/c" without touching the disk. */
    fun normalPath(p: String): String {
        val out = ArrayList<String>()
        for (s in p.split('/')) when (s) { "", "." -> {}; ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1); else -> out.add(s) }
        return "/" + out.joinToString("/")
    }
}
