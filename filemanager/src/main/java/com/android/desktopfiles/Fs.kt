package com.android.desktopfiles

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fs {
    fun ext(): File = Environment.getExternalStorageDirectory()

    /** The launcher's Desktop folder: shortcuts and copies made "to Desktop" go here. */
    fun desktop(): File = File(ext(), "Desktop").also { it.mkdirs() }

    fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val e = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 2
        while (f.exists()) { f = File(dir, "$base ($i)$e"); i++ }
        return f
    }

    fun mime(f: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    fun typeName(f: File): String {
        if (f.isDirectory) return "File folder"
        val e = f.extension
        if (e.equals("desktoplink", true)) return "Shortcut"
        return if (e.isEmpty()) "File" else e.uppercase() + " File"
    }

    fun size(c: Context, b: Long): String = Formatter.formatFileSize(c, b)

    private val df = SimpleDateFormat("dd/MM/yyyy h:mm a", Locale.getDefault())
    fun date(ms: Long): String = df.format(Date(ms))

    /** The file a shortcut (.desktoplink) points to. */
    fun target(f: File): File? = try {
        if (f.extension != "desktoplink") null else f.readLines().getOrNull(1)?.let { File(it) }
    } catch (_: Exception) { null }

    /** Name shown for a file: shortcuts without their ending. */
    fun shown(f: File): String = if (f.extension == "desktoplink") f.name.removeSuffix(".desktoplink") else f.name

    fun makeShortcut(target: File): File? = try {
        val dest = unique(desktop(), target.name + ".desktoplink")
        dest.writeText("DESKTOP_SHORTCUT\n" + target.absolutePath)
        dest
    } catch (_: Exception) { null }

    fun isImage(f: File) = f.extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    fun isVideo(f: File) = f.extension.lowercase() in setOf("mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v")

    fun uri(f: File): Uri = Uri.Builder().scheme("content").authority(FileProv.AUTH).path(f.absolutePath).build()

    /** Opens a file with whatever app can show it (folders are the caller's job). */
    fun view(c: Context, f: File, chooser: Boolean = false): Boolean {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(f), mime(f))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            c.startActivity(if (chooser) Intent.createChooser(i, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else i); true
        } catch (_: Exception) { c.toast("No app can open this file"); false }
    }

    fun share(c: Context, files: List<File>) {
        val list = files.filter { it.isFile }
        if (list.isEmpty()) { c.toast("Only files can be shared"); return }
        val i = if (list.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri(list[0])).setType(mime(list[0]))
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(list.map { uri(it) })).setType("*/*")
        try { c.startActivity(Intent.createChooser(i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { c.toast("Could not share") }
    }
}

fun Context.toast(s: String) = android.widget.Toast.makeText(applicationContext, s, android.widget.Toast.LENGTH_SHORT).show()

/** A drive, partition or storage shown as a card. */
class Vol(val name: String, val dir: File?, val kind: Kind, val total: Long, val free: Long) {
    enum class Kind { ROOT, INTERNAL, SD, USB, PHONE }
}

object Vols {
    fun list(c: Context): List<Vol> {
        val out = ArrayList<Vol>()
        out += Vol("Root", File("/"), Vol.Kind.ROOT, 0, 0)
        try {
            val sm = c.getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) {
                val d = v.directory ?: continue
                if (v.state != android.os.Environment.MEDIA_MOUNTED && v.state != android.os.Environment.MEDIA_MOUNTED_READ_ONLY) continue
                var total = 0L; var free = 0L
                try { val s = StatFs(d.path); total = s.totalBytes; free = s.availableBytes } catch (_: Exception) {}
                val desc = try { v.getDescription(c) } catch (_: Exception) { null } ?: d.name
                if (v.isPrimary) out += Vol("Internal storage", d, Vol.Kind.INTERNAL, total, free)
                else out += Vol(desc, d, if (desc.contains("usb", true)) Vol.Kind.USB else Vol.Kind.SD, total, free)
            }
        } catch (_: Exception) {}
        if (out.none { it.kind == Vol.Kind.INTERNAL }) {
            val d = Fs.ext(); var total = 0L; var free = 0L
            try { val s = StatFs(d.path); total = s.totalBytes; free = s.availableBytes } catch (_: Exception) {}
            out.add(1, Vol("Internal storage", d, Vol.Kind.INTERNAL, total, free))
        }
        return out
    }
}

/** Root access (su): needed for the Root card. Listing only; checked once in the background. */
object RootSu {
    @Volatile var state = 0 // 0 not checked yet, 1 root available, 2 no root

    fun check(done: (Boolean) -> Unit) {
        Thread {
            val ok = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val t = Thread { try { Thread.sleep(8000); p.destroy() } catch (_: Exception) {} }
                t.isDaemon = true; t.start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                out.contains("uid=0")
            } catch (_: Exception) { false }
            state = if (ok) 1 else 2
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(ok) }
        }.start()
    }

    /** Names in a folder that the app itself cannot read, through su. Directories end with "/". */
    fun list(path: String): List<File>? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls -1Ap '" + path.replace("'", "'\\''") + "'"))
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        lines.filter { it.isNotBlank() }.map { File(path, it.removeSuffix("/")) }
    } catch (_: Exception) { null }
}
