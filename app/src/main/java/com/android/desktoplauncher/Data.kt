package com.android.desktoplauncher

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.provider.OpenableColumns
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppRepo.register(this)
        Thread { AppRepo.load(this) }.start()
    }
}

data class AppInfo(val label: String, val comp: ComponentName) {
    val pkg: String get() = comp.packageName
}

object AppRepo {
    @Volatile private var cache: List<AppInfo>? = null

    fun apps(c: Context): List<AppInfo> = cache ?: load(c)

    fun load(c: Context): List<AppInfo> {
        val la = c.getSystemService(LauncherApps::class.java)
        val l = la.getActivityList(null, Process.myUserHandle())
            .map { AppInfo(it.label.toString(), it.componentName) }
            .sortedBy { it.label.lowercase() }
        cache = l
        if (!Prefs.has(c, Prefs.K_PINNED)) {
            val prefer = listOf("com.android.chrome", "com.google.android.apps.messaging", "com.google.android.dialer",
                "com.android.camera2", "com.google.android.apps.photos", "com.android.documentsui", "com.google.android.documentsui")
            val picked = (prefer.mapNotNull { p -> l.firstOrNull { it.pkg == p } } + l).distinctBy { it.comp }.take(4)
            Prefs.setList(c, Prefs.K_PINNED, picked.map { it.comp.flattenToString() }, fire = false)
        }
        return l
    }

    fun find(c: Context, comp: ComponentName): AppInfo? = apps(c).firstOrNull { it.comp == comp }
    fun byPkg(c: Context, pkg: String): AppInfo? = apps(c).firstOrNull { it.pkg == pkg }

    fun register(c: Context) {
        val la = c.getSystemService(LauncherApps::class.java)
        la.registerCallback(object : LauncherApps.Callback() {
            private fun changed() { cache = null; Thread { load(c) ; Bus.fire() }.start() }
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) = changed()
            override fun onPackageAdded(packageName: String?, user: UserHandle?) = changed()
            override fun onPackageChanged(packageName: String?, user: UserHandle?) = changed()
            override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = changed()
            override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = changed()
        })
    }
}

object NotifState {
    @Volatile var counts: Map<String, Int> = emptyMap()
    fun has(pkg: String) = (counts[pkg] ?: 0) > 0
}

class NotifService : NotificationListenerService() {
    override fun onListenerConnected() { refresh() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { refresh() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { refresh() }

    private fun refresh() {
        val m = HashMap<String, Int>()
        try {
            activeNotifications?.forEach {
                val n = it.notification
                val summary = (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0
                if (!it.isOngoing && !summary) m[it.packageName] = (m[it.packageName] ?: 0) + 1
            }
        } catch (_: Exception) {}
        NotifState.counts = m
        Bus.fire()
    }
}

/** Cut / copy / paste of desktop files. */
object Clip {
    var files: List<File> = emptyList()
    var cut = false

    /** Shared with the Desk Files app: label + "DESKTOPFILES\n(copy|cut)\n<path>..." as plain text. */
    const val LABEL = "desktop-files"
    const val MAGIC = "DESKTOPFILES"

    fun set(c: Context, f: List<File>, cut: Boolean) {
        files = f
        this.cut = cut
        if (f.isEmpty()) return
        try {
            val cm = c.getSystemService(ClipboardManager::class.java)
            val text = MAGIC + "\n" + (if (cut) "cut" else "copy") + "\n" + f.joinToString("\n") { it.absolutePath }
            cm.setPrimaryClip(ClipData.newPlainText(LABEL, text))
            writeShared(text)
        } catch (_: Exception) {}
    }

    // Some systems only let the app in front read the clipboard (or hide it from other apps). So the same text is also kept in a
    // small file that both apps can read; it is valid for an hour and removed after a Cut has been pasted.
    private val shareFile: File get() = File(android.os.Environment.getExternalStorageDirectory(), ".deskclip")
    private const val SHARE_TTL = 60 * 60 * 1000L

    private fun writeShared(text: String) { try { shareFile.writeText(text) } catch (_: Exception) {} }
    private fun clearShared() { try { shareFile.delete() } catch (_: Exception) {} }
    private fun sharedFileFresh(): Boolean = try {
        val f = shareFile; f.exists() && System.currentTimeMillis() - f.lastModified() < SHARE_TTL
    } catch (_: Exception) { false }

    private fun sharedLabel(c: Context): Boolean = try {
        c.getSystemService(ClipboardManager::class.java).primaryClipDescription?.label?.toString() == LABEL
    } catch (_: Exception) { false } || sharedFileFresh()

    /** The shared text: from the clipboard when it carries our label, otherwise from the file. */
    private fun sharedText(c: Context): String? {
        try {
            val cm = c.getSystemService(ClipboardManager::class.java)
            val clip = cm.primaryClip
            if (clip != null && clip.description.label?.toString() == LABEL) clip.getItemAt(0).text?.toString()?.let { return it }
        } catch (_: Exception) {}
        return if (sharedFileFresh()) try { shareFile.readText() } catch (_: Exception) { null } else null
    }

    fun clear() { files = emptyList(); cut = false }

    /** True when something can be pasted. Does not read clip contents (avoids the clipboard toast). */
    fun hasAny(c: Context): Boolean {
        if (files.isNotEmpty() || sharedLabel(c)) return true
        return try {
            val d = c.getSystemService(ClipboardManager::class.java).primaryClipDescription
            d != null && d.mimeTypeCount > 0 && !d.hasMimeType("text/*")
        } catch (_: Exception) { false }
    }

    /** Runs on a worker thread. Returns number of pasted items. */
    fun paste(c: Context, dest: File): Int {
        var n = 0
        val cm0 = c.getSystemService(ClipboardManager::class.java)
        if (sharedLabel(c)) {
            val lines = sharedText(c)?.lines()
            if (lines != null && lines.size >= 3 && lines[0] == MAGIC) {
                val isCut = lines[1] == "cut"
                for (p in lines.drop(2)) {
                    if (p.isBlank()) continue
                    val src = File(p)
                    if (!src.exists()) continue
                    if (src.isDirectory && dest.absolutePath.startsWith(src.absolutePath)) continue
                    val target = Files.unique(dest, src.name)
                    try {
                        if (isCut) Files.move(src, target) else Files.copyRec(src, target)
                        n++
                    } catch (_: Exception) {}
                }
                if (isCut && n > 0) { clear(); clearShared(); try { cm0.clearPrimaryClip() } catch (_: Exception) {} }
                return n
            }
        }
        if (files.isNotEmpty()) {
            for (src in files) {
                if (!src.exists()) continue
                val target = Files.unique(dest, src.name)
                try {
                    if (cut) Files.move(src, target) else Files.copyRec(src, target)
                    n++
                } catch (_: Exception) {}
            }
            if (cut) clear()
            return n
        }
        val clip = c.getSystemService(ClipboardManager::class.java).primaryClip ?: return 0
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val uri = item.uri ?: item.intent?.data ?: continue
            try {
                if (uri.scheme == "file") {
                    val src = File(uri.path ?: continue)
                    Files.copyRec(src, Files.unique(dest, src.name)); n++
                } else {
                    val name = displayName(c, uri)
                    val out = Files.unique(dest, name)
                    c.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    n++
                }
            } catch (_: Exception) {}
        }
        return n
    }

    private fun displayName(c: Context, uri: Uri): String {
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { n -> if (n.isNotBlank()) return n }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }
}
