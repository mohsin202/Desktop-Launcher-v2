package com.android.desktopfiles

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * Opens archives as folders: the archive is unpacked once into a hidden cache folder and that folder is shown in a new tab,
 * so listing, copy, paste, drag and the rest all work as usual. zip / tar / tar.gz are read here, ISO (and .img that holds an
 * ISO 9660 disc) is read by a small parser, 7z / tar.bz2 / tar.xz go through commons-compress (looked up when needed).
 * Changes made inside the opened folder are NOT written back into the archive.
 */
object Unpack {
    private val TAR_GZ = listOf(".tar.gz", ".tgz")
    private val TAR_BZ = listOf(".tar.bz2", ".tbz2", ".tbz")
    private val TAR_XZ = listOf(".tar.xz", ".txz")

    fun kind(f: File): String {
        val n = f.name.lowercase()
        return when {
            n.endsWith(".zip") || n.endsWith(".jar") -> "zip"
            TAR_GZ.any { n.endsWith(it) } -> "tgz"
            TAR_BZ.any { n.endsWith(it) } -> "tbz"
            TAR_XZ.any { n.endsWith(it) } -> "txz"
            n.endsWith(".tar") -> "tar"
            n.endsWith(".7z") -> "7z"
            n.endsWith(".iso") || n.endsWith(".img") -> "iso"
            else -> ""
        }
    }

    fun supported(f: File) = f.isFile && kind(f).isNotEmpty()

    private fun baseName(f: File): String {
        val n = f.name
        val l = n.lowercase()
        for (e in TAR_GZ + TAR_BZ + TAR_XZ) if (l.endsWith(e)) return n.dropLast(e.length).ifEmpty { n }
        return n.substringBeforeLast('.', n).ifEmpty { n }
    }

    private fun cacheDir(f: File): File {
        val key = Integer.toHexString((f.absolutePath + "|" + f.length() + "|" + f.lastModified()).hashCode())
        return File(File(Fs.ext(), ".deskphone/arc"), key)
    }

    /** Opens [f] in a new tab (unpacking first if needed). */
    fun openInTab(host: Host, f: File) {
        if (!supported(f)) { host.act.toast("This file type can't be opened as a folder"); return }
        val dest = File(cacheDir(f), baseName(f))
        if (dest.isDirectory && (dest.list()?.isNotEmpty() == true)) { host.newTab(Loc.Dir(dest.absolutePath)); return }
        val job = Job("Opening " + f.name)
        Jobs.start(job, { j -> extract(f, dest, j); if (j.failed > 0 && !j.cancelled) { /* keep what was read */ } }) {
            if (job.cancelled) dest.deleteRecursively()
            else if (dest.isDirectory && (dest.list()?.isNotEmpty() == true)) host.newTab(Loc.Dir(dest.absolutePath))
            else host.act.toast(job.detail.ifEmpty { "Could not open the archive" })
        }
        host.showStatus()
    }

    /** Plain extraction (for "Extract here" / "Extract to folder"). */
    fun extract(f: File, dest: File, job: Job) {
        dest.mkdirs()
        try {
            when (kind(f)) {
                "zip" -> Ops.unzip(f, dest, job)
                "tar" -> BufferedInputStream(f.inputStream(), 1 shl 16).use { tar(it, f, dest, job) }
                "tgz" -> GZIPInputStream(BufferedInputStream(f.inputStream(), 1 shl 16), 1 shl 16).use { tar(it, f, dest, job) }
                "tbz" -> wrapped("org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream", f).use { tar(it, f, dest, job) }
                "txz" -> wrapped("org.apache.commons.compress.compressors.xz.XZCompressorInputStream", f).use { tar(it, f, dest, job) }
                "7z" -> sevenZ(f, dest, job)
                "iso" -> iso(f, dest, job)
                else -> { job.failed++; job.detail = "Unsupported archive" }
            }
        } catch (e: java.util.zip.ZipException) {
            job.failed++; job.detail = "Could not read it (password-protected or damaged)"
        } catch (e: ClassNotFoundException) {
            job.failed++; job.detail = "This format needs the commons-compress library"
        } catch (e: Throwable) {
            job.failed++; job.detail = (e.cause ?: e).message ?: "Could not read the archive"
        }
    }

    private fun wrapped(cls: String, f: File): InputStream =
        Class.forName(cls).getConstructor(InputStream::class.java).newInstance(BufferedInputStream(f.inputStream(), 1 shl 16)) as InputStream

    /** Safe destination for an entry name: nothing may land outside [dest]. */
    private fun target(dest: File, name: String): File? {
        val clean = name.replace('\\', '/').trimStart('/')
        if (clean.isEmpty()) return null
        val t = File(dest, clean)
        return if (t.canonicalPath.startsWith(dest.canonicalPath + File.separator)) t else null
    }

    private fun copyOut(i: InputStream, t: File, size: Long, job: Job, buf: ByteArray, tick: (Int) -> Unit) {
        t.parentFile?.mkdirs()
        t.outputStream().use { o ->
            var left = size
            while (left > 0) {
                if (job.cancelled) return
                val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                o.write(buf, 0, n); left -= n; tick(n)
            }
        }
    }

    // ---------- tar ----------
    private fun readFull(i: InputStream, b: ByteArray): Boolean {
        var p = 0
        while (p < b.size) { val n = i.read(b, p, b.size - p); if (n < 0) return false; p += n }
        return true
    }

    private fun skip(i: InputStream, n: Long) {
        var left = n
        val tmp = ByteArray(8192)
        while (left > 0) { val r = i.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt()); if (r < 0) return; left -= r }
    }

    private fun str(b: ByteArray, off: Int, len: Int): String {
        var e = off; while (e < off + len && b[e].toInt() != 0) e++
        return String(b, off, e - off, Charsets.UTF_8)
    }

    private fun num(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) { var v = (b[off].toInt() and 0x7f).toLong(); for (k in 1 until len) v = (v shl 8) or (b[off + k].toLong() and 0xff); return v }
        val s = str(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLongOrNull(8) ?: 0
    }

    private fun tar(i: InputStream, src: File, dest: File, job: Job) {
        job.total = src.length() * (if (kind(src) == "tar") 1 else 3)   // rough: unpacked data is larger than the file
        val buf = ByteArray(1 shl 16)
        val h = ByteArray(512)
        var longName: String? = null
        var paxName: String? = null
        while (!job.cancelled && readFull(i, h)) {
            if (h.all { it.toInt() == 0 }) break
            var name = str(h, 0, 100)
            val prefix = str(h, 345, 155)
            if (prefix.isNotEmpty() && String(h, 257, 5) == "ustar") name = "$prefix/$name"
            val size = num(h, 124, 12)
            val type = h[156].toInt().toChar()
            val pad = (512 - size % 512) % 512
            when (type) {
                'L' -> { val b = ByteArray(size.toInt()); readFull(i, b); longName = str(b, 0, b.size); skip(i, pad) }
                'x' -> {
                    val b = ByteArray(size.toInt()); readFull(i, b); skip(i, pad)
                    val txt = String(b, Charsets.UTF_8)
                    txt.split('\n').forEach { ln -> val sp = ln.indexOf(' '); val eq = ln.indexOf('=') ; if (sp > 0 && eq > sp && ln.substring(sp + 1, eq) == "path") paxName = ln.substring(eq + 1) }
                }
                'g' -> skip(i, size + pad)
                else -> {
                    val real = paxName ?: longName ?: name
                    longName = null; paxName = null
                    val t = target(dest, real)
                    when {
                        t == null -> { skip(i, size + pad); if (real.isNotBlank()) job.failed++ }
                        type == '5' || real.endsWith("/") -> { t.mkdirs(); skip(i, size + pad) }
                        type == '0' || type == '\u0000' || type == '7' -> {
                            job.detail = t.name
                            copyOut(i, t, size, job, buf) { job.done = minOf(job.total, job.done + it) }
                            skip(i, pad)
                            val mt = num(h, 136, 12); if (mt > 0) t.setLastModified(mt * 1000)
                        }
                        else -> skip(i, size + pad)   // links, devices
                    }
                }
            }
        }
    }

    // ---------- 7z ----------
    private fun sevenZ(f: File, dest: File, job: Job) {
        val cls = Class.forName("org.apache.commons.compress.archivers.sevenz.SevenZFile")
        val z = cls.getConstructor(File::class.java).newInstance(f)
        try {
            val next = cls.getMethod("getNextEntry")
            val read = cls.getMethod("read", ByteArray::class.java)
            val ec = Class.forName("org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry")
            val gName = ec.getMethod("getName"); val gDir = ec.getMethod("isDirectory"); val gSize = ec.getMethod("getSize")
            job.total = f.length() * 3
            val buf = ByteArray(1 shl 16)
            while (!job.cancelled) {
                val e = next.invoke(z) ?: break
                val t = target(dest, gName.invoke(e) as String)
                if (t == null) { job.failed++; continue }
                if (gDir.invoke(e) as Boolean) { t.mkdirs(); continue }
                job.detail = t.name
                t.parentFile?.mkdirs()
                var left = gSize.invoke(e) as Long
                t.outputStream().use { o ->
                    while (left > 0 && !job.cancelled) {
                        val n = read.invoke(z, buf) as Int
                        if (n < 0) break
                        o.write(buf, 0, n); left -= n; job.done = minOf(job.total, job.done + n)
                    }
                }
            }
        } finally { try { cls.getMethod("close").invoke(z) } catch (_: Exception) {} }
    }

    // ---------- ISO 9660 (Joliet names when present) ----------
    private fun iso(f: File, dest: File, job: Job) {
        RandomAccessFile(f, "r").use { r ->
            val sec = ByteArray(2048)
            var root: ByteArray? = null; var joliet = false
            var s = 16
            while (s < 64) {
                r.seek(s * 2048L); if (r.read(sec) < 2048) break
                if (String(sec, 1, 5) != "CD001") break
                val t = sec[0].toInt()
                if (t == 255) break
                if (t == 1 && root == null) root = sec.copyOfRange(156, 156 + 34)
                if (t == 2 && sec[88].toInt() == 0x25 && sec[89].toInt() == 0x2f && (sec[90].toInt() == 0x40 || sec[90].toInt() == 0x43 || sec[90].toInt() == 0x45)) { root = sec.copyOfRange(156, 156 + 34); joliet = true }
                s++
            }
            if (root == null) { job.failed++; job.detail = "This image is not an ISO 9660 disc (other file systems are not supported)"; return }
            job.total = f.length()
            val buf = ByteArray(1 shl 16)
            fun le32(b: ByteArray, o: Int) = (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)
            fun walk(extent: Long, size: Long, into: File, depth: Int) {
                if (depth > 40 || job.cancelled) return
                val d = ByteArray(size.toInt().coerceAtLeast(0)); r.seek(extent * 2048); r.readFully(d)
                var p = 0
                while (p < d.size && !job.cancelled) {
                    val len = d[p].toInt() and 0xff
                    if (len == 0) { p = (p / 2048 + 1) * 2048; continue }
                    if (p + len > d.size || len < 34) break
                    val nl = d[p + 32].toInt() and 0xff
                    val isDir = d[p + 25].toInt() and 2 != 0
                    val ext = le32(d, p + 2); val sz = le32(d, p + 10)
                    val raw = d.copyOfRange(p + 33, p + 33 + nl)
                    p += len
                    if (nl == 1 && (raw[0].toInt() == 0 || raw[0].toInt() == 1)) continue
                    var name = if (joliet) String(raw, Charsets.UTF_16BE) else String(raw, Charsets.ISO_8859_1)
                    if (!isDir) { name = name.substringBefore(';'); if (name.endsWith(".")) name = name.dropLast(1) }
                    name = name.replace('/', '_')
                    val t = target(into, name) ?: run { job.failed++; null } ?: continue
                    if (isDir) { t.mkdirs(); walk(ext, sz, t, depth + 1) }
                    else {
                        job.detail = t.name
                        t.parentFile?.mkdirs()
                        r.seek(ext * 2048)
                        t.outputStream().use { o ->
                            var left = sz
                            while (left > 0 && !job.cancelled) {
                                val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) break
                                o.write(buf, 0, n); left -= n; job.done = minOf(job.total, job.done + n)
                            }
                        }
                    }
                }
            }
            walk(le32(root, 2), le32(root, 10), dest, 0)
        }
    }
}
