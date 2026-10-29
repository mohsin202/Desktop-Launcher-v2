package com.android.desktoplauncher

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Files downloaded in the last 24 hours, for the card at the top of the search screen. */
object Downloads {
    const val WINDOW_MS = 24L * 60 * 60 * 1000
    private val SKIP_EXT = setOf("desktoplink", "crdownload", "tmp", "part", "download", "pending")

    class Entry(val file: File, val time: Long)

    /** Local midnight: the card shows only today's items. */
    fun todayStart(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Launcher apps first installed today (Play Store, APK files, anything), newest first. */
    fun newApps(c: Context, all: List<AppInfo>): List<AppInfo> {
        val cutoff = maxOf(todayStart(), Prefs.getLong(c, Prefs.K_DL_DISMISSED, 0L))
        return all.mapNotNull {
            try {
                val t = c.packageManager.getPackageInfo(it.pkg, 0).firstInstallTime
                if (t > cutoff && it.pkg != c.packageName) it to t else null
            } catch (_: Exception) { null }
        }.sortedByDescending { it.second }.map { it.first }.distinctBy { it.pkg }
    }

    fun dismiss(c: Context) = Prefs.setLong(c, Prefs.K_DL_DISMISSED, System.currentTimeMillis())

    /** Targets of the .desktoplink shortcuts that already exist on the Desktop. */
    private fun shortcutTargets(c: Context): Set<String> {
        val out = HashSet<String>()
        try {
            Files.root(c).listFiles()?.filter { it.extension == "desktoplink" }?.forEach {
                try { it.readLines().getOrNull(1)?.let { p -> out += p } } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return out
    }

    /** Newest first. Hidden: older than 24h, older than the last ✕, or already having a desktop shortcut. */
    fun recent(c: Context): List<Entry> {
        val now = System.currentTimeMillis()
        val cutoff = maxOf(todayStart(), Prefs.getLong(c, Prefs.K_DL_DISMISSED, 0L))
        val found = LinkedHashMap<String, Entry>()
        try {
            c.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DATE_ADDED),
                "${MediaStore.Files.FileColumns.DATE_ADDED} >= ?",
                arrayOf((cutoff / 1000).toString()),
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
            )?.use {
                while (it.moveToNext()) {
                    val p = it.getString(0) ?: continue
                    found[p] = Entry(File(p), it.getLong(1) * 1000)
                }
            }
        } catch (_: Exception) {}
        // Direct scan also works when the media index is slow or missing.
        try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).listFiles()?.forEach {
                if (!found.containsKey(it.absolutePath)) found[it.absolutePath] = Entry(it, it.lastModified())
            }
        } catch (_: Exception) {}
        val shortcuts = shortcutTargets(c)
        val ext = Environment.getExternalStorageDirectory().absolutePath
        val desk = try { Files.root(c).absolutePath } catch (_: Exception) { "" }
        return found.values.filter {
            val f = it.file
            val rel = f.absolutePath.removePrefix(ext)
            f.isFile && !rel.split('/').any { seg -> seg.startsWith(".") } &&
                !rel.startsWith("/Android/") && !(desk.isNotEmpty() && f.absolutePath.startsWith("$desk/")) && f.extension.lowercase() !in SKIP_EXT &&
                it.time > cutoff && f.absolutePath !in shortcuts
        }.sortedByDescending { it.time }.take(200)
    }

    fun createShortcut(c: Context, f: File): Boolean = try {
        Files.unique(Files.root(c), f.name + ".desktoplink").writeText("DESKTOP_SHORTCUT\n${f.absolutePath}")
        Bus.fire()
        true
    } catch (_: Exception) { false }

    fun ago(c: Context, time: Long): String {
        val m = ((System.currentTimeMillis() - time) / 60000).coerceAtLeast(0)
        val ur = Prefs.language(c) == 1
        return when {
            m < 1 -> UiText.text(c, "Just now")
            m < 60 -> if (ur) "$m منٹ پہلے" else "$m min ago"
            else -> (m / 60).let { h -> if (ur) "$h گھنٹے پہلے" else if (h == 1L) "1 hour ago" else "$h hours ago" }
        }
    }
}
