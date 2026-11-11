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

    fun makeShortcut(target: File, into: File? = null): File? = try {
        val dest = unique(into ?: desktop(), target.name + ".desktoplink")
        dest.writeText("DESKTOP_SHORTCUT\n" + target.absolutePath)
        dest
    } catch (_: Exception) { null }

    fun isImage(f: File) = f.extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    fun isVideo(f: File) = f.extension.lowercase() in setOf("mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v")
    fun isAudio(f: File) = f.extension.lowercase() in setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "wma", "amr", "mid", "midi")

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

fun Context.toast(s: String) = android.widget.Toast.makeText(applicationContext, T.t(s), android.widget.Toast.LENGTH_SHORT).show()

/** A drive, partition or storage shown as a card. */
class Vol(val name: String, val dir: File?, val kind: Kind, val total: Long, val free: Long) {
    enum class Kind { ROOT, INTERNAL, SD, USB, PHONE }
}

object Vols {
    /** The folder a storage volume is mounted at: VolumeDirectory from Android 11, the volume's id under /storage before that. */
    fun dirOf(v: android.os.storage.StorageVolume): File? =
        if (android.os.Build.VERSION.SDK_INT >= 30) v.directory
        else if (v.isPrimary) android.os.Environment.getExternalStorageDirectory()
        else v.uuid?.let { File("/storage/$it") }?.takeIf { it.exists() }

    fun list(c: Context): List<Vol> {
        val out = ArrayList<Vol>()
        out += Vol("Root", File("/"), Vol.Kind.ROOT, 0, 0)
        try {
            val sm = c.getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) {
                val d = dirOf(v) ?: continue
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

/** A file or folder read through su: knows its kind, size and date from the listing (the app itself cannot look at it). */
class RootFile(path: String, private val dir: Boolean, private val sz: Long, private val mt: Long) : File(path) {
    override fun isDirectory() = dir
    override fun isFile() = !dir
    override fun length() = sz
    override fun lastModified() = mt
    override fun exists() = true
}

/** Root access (su): lets the Root card and system folders be listed, opened, copied, moved, renamed and deleted. */
object RootSu {
    @Volatile var state = 0 // 0 not checked yet, 1 root available, 2 no root

    fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"
    fun q(f: File) = q(f.absolutePath)

    private fun probe(): Boolean {
        val ok = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val t = Thread { try { Thread.sleep(8000); p.destroy() } catch (_: Exception) {} }
            t.isDaemon = true; t.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("uid=0")
        } catch (_: Exception) { false }
        state = if (ok) 1 else 2
        return ok
    }

    fun check(done: (Boolean) -> Unit) {
        Thread {
            val ok = probe()
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(ok) }
        }.start()
    }

    /** Checks (once) and tells whether root works. Call from a worker thread. */
    fun ensure(): Boolean { if (state == 0) probe(); return state == 1 }

    /** Runs one command as root; true when it succeeded. Worker thread only. */
    fun sh(cmd: String): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "$cmd 2>&1"))
        p.inputStream.readBytes()
        p.waitFor() == 0
    } catch (_: Exception) { false }

    /** Runs one command as root and returns (succeeded, all its output). Worker thread only. */
    fun out(cmd: String): Pair<Boolean, String> = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "$cmd 2>&1"))
        val t = p.inputStream.bufferedReader().readText()
        (p.waitFor() == 0) to t.trim()
    } catch (e: Exception) { false to (e.message ?: "") }

    /** The mounted file system a path lives on. */
    class Mnt(val mp: String, val dev: String, val rw: Boolean)

    fun mountOf(path: String): Mnt? {
        val (ok, txt) = out("cat /proc/mounts")
        if (!ok) return null
        var best: Mnt? = null
        for (l in txt.lines()) {
            val a = l.split(" ")
            if (a.size < 4) continue
            val mp = a[1].replace("\\040", " ")
            if (!(path == mp || mp == "/" || path.startsWith("$mp/"))) continue
            if (best == null || mp.length >= best.mp.length) best = Mnt(mp, a[0], a[3].split(",").contains("rw"))
        }
        return best
    }

    /** Remounts a file system read-write or read-only (tries the usual spellings). */
    fun remount(m: Mnt, rw: Boolean): Pair<Boolean, String> {
        val mode = if (rw) "rw" else "ro"
        var r = out("mount -o remount,$mode " + q(m.mp))
        if (!r.first) r = out("mount -o $mode,remount " + q(m.mp))
        if (!r.first) r = out("mount -o remount,$mode " + q(m.dev) + " " + q(m.mp))
        return r
    }

    /** Mode (octal), owner and group of a file. */
    class Perm(val mode: String, val owner: String, val group: String)

    fun permOf(path: String): Perm? {
        val (ok, txt) = out("stat -c '%a|%U|%G' " + q(path))
        if (!ok) return null
        val a = txt.lines().last().split("|")
        return if (a.size == 3) Perm(a[0], a[1], a[2]) else null
    }

    fun exists(path: String): Boolean = sh("test -e " + q(path))
    fun exists(f: File): Boolean = f.exists() || (state == 1 && !(f.parentFile?.canRead() ?: false) && exists(f.absolutePath))

    /** The items of a folder that the app cannot read itself, with kind / size / date. */
    fun list(path: String): List<File>? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cd " + q(path) + " && stat -L -c '%F|%s|%Y|%n' .[!.]* ..?* * 2>/dev/null"))
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        val out = ArrayList<File>(); val seen = HashSet<String>()
        for (l in lines) {
            val a = l.split("|", limit = 4)
            if (a.size < 4 || a[3] == "." || a[3] == ".." || !seen.add(a[3])) continue
            out.add(RootFile(File(path, a[3]).path, a[0].startsWith("directory"), a[1].toLongOrNull() ?: 0L, (a[2].toLongOrNull() ?: 0L) * 1000))
        }
        if (out.isNotEmpty()) out else listNames(path)
    } catch (_: Exception) { null }

    private fun listNames(path: String): List<File>? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls -1Ap " + q(path)))
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        lines.filter { it.isNotBlank() }.map { RootFile(File(path, it.removeSuffix("/")).path, it.endsWith("/"), 0L, 0L) }
    } catch (_: Exception) { null }
}
