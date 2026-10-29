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

    fun set(c: Context, f: List<File>, cut: Boolean) {
        files = f
        this.cut = cut
        if (!cut && f.isNotEmpty()) {
            try {
                val cm = c.getSystemService(ClipboardManager::class.java)
                val clip = ClipData.newUri(c.contentResolver, "files", Files.uri(f[0]))
                for (i in 1 until f.size) clip.addItem(ClipData.Item(Files.uri(f[i])))
                cm.setPrimaryClip(clip)
            } catch (_: Exception) {}
        }
    }

    fun clear() { files = emptyList(); cut = false }

    /** True when something can be pasted. Does not read clip contents (avoids the clipboard toast). */
    fun hasAny(c: Context): Boolean {
        if (files.isNotEmpty()) return true
        return try {
            val d = c.getSystemService(ClipboardManager::class.java).primaryClipDescription
            d != null && d.mimeTypeCount > 0 && !d.hasMimeType("text/*")
        } catch (_: Exception) { false }
    }

    /** Runs on a worker thread. Returns number of pasted items. */
    fun paste(c: Context, dest: File): Int {
        var n = 0
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
