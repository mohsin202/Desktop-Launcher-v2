package com.android.desktopfiles

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Makes archives: zip (levels, ZipCrypto and AES passwords), tar, tar.gz written here; 7z, tar.bz2, tar.xz and tar.lz4 through the
 * Apache commons-compress library (looked up when needed, so a missing library only disables those formats). Volumes are cut afterwards.
 */
object Archiver {
    val FORMATS = listOf("7z", "zip", "tar", "tar.bz2", "tar.gz", "tar.xz", "tar.lz4")
    val LEVELS = listOf("No compression", "Fastest", "Fast", "Normal", "Maximum", "Ultra")
    /** Encryption: 0 none, 1 ZipCrypto, 2..4 AES-128 / 192 / 256 (zip only). */
    val ENCRYPTION = listOf("No encryption", "Data (ZipCrypto)", "Data (AES-128)", "Data (AES-192)", "Data (AES-256)")

    private val ZIP_LV = intArrayOf(0, 1, 3, 6, 8, 9)
    private val BZ_LV = intArrayOf(1, 1, 3, 6, 9, 9)

    class Opts(val format: String, val level: Int, val enc: Int, val password: String)

    class Entry(val file: File, val name: String, val dir: Boolean)

    /** Every file and folder under [files], names relative to the folder that holds them. */
    fun entries(files: List<File>): List<Entry> {
        val out = ArrayList<Entry>()
        fun add(f: File, name: String) {
            if (f.isDirectory) {
                val kids = f.listFiles()?.sortedBy { it.name }
                out.add(Entry(f, "$name/", true))
                if (kids != null) for (k in kids) add(k, name + "/" + k.name)
            } else out.add(Entry(f, name, false))
        }
        for (f in files) add(f, f.name)
        return out
    }

    fun totalSize(files: List<File>): Long = entries(files).filter { !it.dir }.sumOf { it.file.length() }

    /** Writes one archive. [tmp] is a folder for temporary files (password-protected zip). */
    fun create(files: List<File>, out: File, o: Opts, job: Job, tmp: File) {
        val es = entries(files)
        try {
            when {
                o.format == "zip" && o.enc != 0 -> zipEncrypted(es, out, o, job, tmp)
                o.format == "zip" -> zipPlain(es, out, o, job)
                o.format == "7z" -> sevenZ(es, out, o, job)
                else -> tarFamily(es, out, o, job)
            }
        } catch (e: java.lang.reflect.InvocationTargetException) { throw (e.cause ?: e) }
        if (job.cancelled) out.delete()
    }

    private fun copyCount(i: InputStream, o: OutputStream, job: Job, buf: ByteArray) {
        while (true) { if (job.cancelled) break; val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); job.done += n }
    }

    // ---------------- zip, no password ----------------
    private fun zipPlain(es: List<Entry>, out: File, o: Opts, job: Job) {
        val buf = ByteArray(256 * 1024)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out), 256 * 1024)).use { zo ->
            zo.setLevel(ZIP_LV[o.level])
            for (e in es) {
                if (job.cancelled) break
                val ze = ZipEntry(e.name); ze.time = e.file.lastModified()
                zo.putNextEntry(ze)
                if (!e.dir) { job.detail = e.file.name; FileInputStream(e.file).use { copyCount(it, zo, job, buf) } }
                zo.closeEntry()
            }
        }
    }

    // ---------------- zip, password ----------------
    private class Counting(o: OutputStream) : FilterOutputStream(o) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private class Cd(val name: ByteArray, val method: Int, val flags: Int, val time: Int, val date: Int, val crc: Long, val csize: Long, val usize: Long, val offset: Long, val extra: ByteArray, val version: Int, val dir: Boolean)

    private val CRC_TABLE = LongArray(256).also { t -> for (i in 0 until 256) { var c = i.toLong(); repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor 0xEDB88320L else c ushr 1 }; t[i] = c } }
    private fun crcUp(crc: Long, b: Int): Long = CRC_TABLE[((crc xor b.toLong()) and 0xff).toInt()] xor (crc ushr 8)

    private class ZipCryptoKeys(pw: ByteArray) {
        var k0 = 0x12345678L; var k1 = 0x23456789L; var k2 = 0x34567890L
        init { for (b in pw) update(b.toInt() and 0xff) }
        fun update(c: Int) {
            k0 = crcUp(k0, c)
            k1 = (k1 + (k0 and 0xff)) and 0xffffffffL
            k1 = (k1 * 134775813L + 1L) and 0xffffffffL
            k2 = crcUp(k2, (k1 ushr 24).toInt())
        }
        fun streamByte(): Int { val t = (k2 or 2L) and 0xffff; return (((t * (t xor 1L)) ushr 8) and 0xff).toInt() }
        fun encrypt(b: Int): Int { val c = (b and 0xff) xor streamByte(); update(b and 0xff); return c }
    }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun dos(t: Long): Pair<Int, Int> {
        val c = java.util.Calendar.getInstance(); c.timeInMillis = if (t > 0) t else System.currentTimeMillis()
        val y = (c.get(java.util.Calendar.YEAR) - 1980).coerceIn(0, 127)
        val time = (c.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or (c.get(java.util.Calendar.MINUTE) shl 5) or (c.get(java.util.Calendar.SECOND) / 2)
        val date = (y shl 9) or ((c.get(java.util.Calendar.MONTH) + 1) shl 5) or c.get(java.util.Calendar.DAY_OF_MONTH)
        return time to date
    }

    private fun zipEncrypted(es: List<Entry>, outFile: File, o: Opts, job: Job, tmpDir: File) {
        tmpDir.mkdirs()
        val pw = o.password.toByteArray(Charsets.UTF_8)
        val rnd = SecureRandom()
        val aes = o.enc >= 2
        val keyLen = when (o.enc) { 2 -> 16; 3 -> 24; else -> 32 }
        val saltLen = keyLen / 2
        val strength = when (o.enc) { 2 -> 1; 3 -> 2; else -> 3 }
        val level = ZIP_LV[o.level]
        val cds = ArrayList<Cd>()
        val buf = ByteArray(256 * 1024)
        Counting(BufferedOutputStream(FileOutputStream(outFile), 256 * 1024)).use { out ->
            for (e in es) {
                if (job.cancelled) break
                val nameB = e.name.toByteArray(Charsets.UTF_8)
                val (time, date) = dos(e.file.lastModified())
                val offset = out.count
                if (e.dir) {
                    out.write(le32(0x04034b50L)); out.write(le16(20)); out.write(le16(0x800)); out.write(le16(0)); out.write(le16(time)); out.write(le16(date))
                    out.write(le32(0)); out.write(le32(0)); out.write(le32(0)); out.write(le16(nameB.size)); out.write(le16(0)); out.write(nameB)
                    cds.add(Cd(nameB, 0, 0x800, time, date, 0, 0, 0, offset, ByteArray(0), 20, true))
                    continue
                }
                job.detail = e.file.name
                // 1) pack the file into a temporary file (the sizes and the CRC are needed before the header)
                val tmp = File.createTempFile("arc", ".tmp", tmpDir)
                try {
                    val crc = CRC32(); var usize = 0L
                    val def = Deflater(level, true)
                    FileInputStream(e.file).use { i ->
                        val dst: OutputStream = if (level == 0) FileOutputStream(tmp) else DeflaterOutputStream(FileOutputStream(tmp), def, 64 * 1024)
                        dst.use { d ->
                            while (true) {
                                if (job.cancelled) break
                                val n = i.read(buf); if (n < 0) break
                                crc.update(buf, 0, n); d.write(buf, 0, n); usize += n; job.done += n
                            }
                        }
                    }
                    def.end()
                    if (job.cancelled) break
                    val method = if (level == 0) 0 else 8
                    val c0 = tmp.length()
                    val csize = if (aes) saltLen + 2 + c0 + 10 else c0 + 12
                    if (csize >= 0xFFFFFFFFL || usize >= 0xFFFFFFFFL || offset >= 0xFFFFFFFFL) throw java.io.IOException("Too big for a password-protected zip")
                    val flags = 1 or 0x800
                    val extra = if (aes) byteArrayOf(0x01, 0x99.toByte(), 7, 0, 2, 0, 'A'.code.toByte(), 'E'.code.toByte(), strength.toByte(), method.toByte(), 0) else ByteArray(0)
                    val crcField = if (aes) 0L else crc.value
                    val m = if (aes) 99 else method
                    val ver = if (aes) 51 else 20
                    out.write(le32(0x04034b50L)); out.write(le16(ver)); out.write(le16(flags)); out.write(le16(m)); out.write(le16(time)); out.write(le16(date))
                    out.write(le32(crcField)); out.write(le32(csize)); out.write(le32(usize)); out.write(le16(nameB.size)); out.write(le16(extra.size)); out.write(nameB); out.write(extra)
                    // 2) the data, encrypted
                    if (!aes) {
                        val keys = ZipCryptoKeys(pw)
                        val hdr = ByteArray(12); rnd.nextBytes(hdr); hdr[11] = (crc.value ushr 24).toByte()
                        for (k in hdr.indices) hdr[k] = keys.encrypt(hdr[k].toInt()).toByte()
                        out.write(hdr)
                        FileInputStream(tmp).use { i ->
                            while (true) {
                                val n = i.read(buf); if (n < 0) break
                                for (k in 0 until n) buf[k] = keys.encrypt(buf[k].toInt()).toByte()
                                out.write(buf, 0, n)
                            }
                        }
                    } else {
                        val salt = ByteArray(saltLen); rnd.nextBytes(salt)
                        val dk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(String(pw, Charsets.UTF_8).toCharArray(), salt, 1000, (2 * keyLen + 2) * 8)).encoded
                        val encKey = dk.copyOfRange(0, keyLen); val authKey = dk.copyOfRange(keyLen, 2 * keyLen); val pwv = dk.copyOfRange(2 * keyLen, 2 * keyLen + 2)
                        out.write(salt); out.write(pwv)
                        val ecb = Cipher.getInstance("AES/ECB/NoPadding"); ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"))
                        val mac = Mac.getInstance("HmacSHA1"); mac.init(SecretKeySpec(authKey, "HmacSHA1"))
                        val ctr = ByteArray(16); var counter = 1L
                        FileInputStream(tmp).use { i ->
                            val chunk = ByteArray(64 * 1024)
                            while (true) {
                                var got = 0
                                while (got < chunk.size) { val n = i.read(chunk, got, chunk.size - got); if (n < 0) break; got += n }
                                if (got == 0) break
                                var p = 0
                                while (p < got) {
                                    var v = counter++
                                    for (k in 0 until 8) { ctr[k] = v.toByte(); v = v ushr 8 }
                                    val ks = ecb.update(ctr)
                                    val len = minOf(16, got - p)
                                    for (k in 0 until len) chunk[p + k] = (chunk[p + k].toInt() xor ks[k].toInt()).toByte()
                                    p += len
                                }
                                mac.update(chunk, 0, got); out.write(chunk, 0, got)
                                if (got < chunk.size) break
                            }
                        }
                        out.write(mac.doFinal(), 0, 10)
                    }
                    cds.add(Cd(nameB, m, flags, time, date, crcField, csize, usize, offset, extra, ver, false))
                } finally { tmp.delete() }
            }
            // the central directory
            val cdStart = out.count
            for (c in cds) {
                out.write(le32(0x02014b50L)); out.write(le16(c.version)); out.write(le16(c.version)); out.write(le16(c.flags)); out.write(le16(c.method)); out.write(le16(c.time)); out.write(le16(c.date))
                out.write(le32(c.crc)); out.write(le32(c.csize)); out.write(le32(c.usize)); out.write(le16(c.name.size)); out.write(le16(c.extra.size)); out.write(le16(0))
                out.write(le16(0)); out.write(le16(0)); out.write(le32(if (c.dir) 0x10L else 0L)); out.write(le32(c.offset)); out.write(c.name); out.write(c.extra)
            }
            val cdSize = out.count - cdStart
            out.write(le32(0x06054b50L)); out.write(le16(0)); out.write(le16(0)); out.write(le16(cds.size)); out.write(le16(cds.size)); out.write(le32(cdSize)); out.write(le32(cdStart)); out.write(le16(0))
        }
    }

    // ---------------- tar, tar.gz, tar.bz2, tar.xz, tar.lz4 ----------------
    private class Tar(private val out: OutputStream) {
        private var left = 0L
        private fun oct(v: Long, len: Int): ByteArray { val s = java.lang.Long.toOctalString(v).padStart(len - 1, '0'); return (s + "\u0000").toByteArray() }
        private fun header(name: String, size: Long, mtime: Long, type: Char, mode: Int): ByteArray {
            val h = ByteArray(512)
            fun put(b: ByteArray, at: Int, max: Int) { System.arraycopy(b, 0, h, at, minOf(b.size, max)) }
            put(name.toByteArray(Charsets.UTF_8), 0, 100)
            put(oct(mode.toLong(), 8), 100, 8); put(oct(0, 8), 108, 8); put(oct(0, 8), 116, 8)
            put(oct(size, 12), 124, 12); put(oct(mtime / 1000, 12), 136, 12)
            for (i in 148 until 156) h[i] = 0x20
            h[156] = type.code.toByte()
            put("ustar\u0000".toByteArray(), 257, 6); put("00".toByteArray(), 263, 2)
            var sum = 0L; for (b in h) sum += (b.toInt() and 0xff)
            put((java.lang.Long.toOctalString(sum).padStart(6, '0') + "\u0000 ").toByteArray(), 148, 8)
            return h
        }
        private fun pad(n: Long) { val r = (512 - n % 512) % 512; if (r > 0) out.write(ByteArray(r.toInt())) }
        private fun record(k: String, v: String): ByteArray {
            val body = " $k=$v\n".toByteArray(Charsets.UTF_8)
            var len = body.size + 1
            while (len.toString().length + body.size != len) len = len.toString().length + body.size
            return (len.toString()).toByteArray() + body
        }
        fun begin(name: String, size: Long, mtime: Long, dir: Boolean) {
            val nameB = name.toByteArray(Charsets.UTF_8)
            if (nameB.size > 100 || size > 0x1FFFFFFFFL) {
                val recs = ArrayList<ByteArray>()
                if (nameB.size > 100) recs.add(record("path", name))
                if (size > 0x1FFFFFFFFL) recs.add(record("size", size.toString()))
                val data = recs.fold(ByteArray(0)) { a, b -> a + b }
                out.write(header("PaxHeader/" + name.takeLast(60).trimStart('/'), data.size.toLong(), mtime, 'x', 420))
                out.write(data); pad(data.size.toLong())
            }
            out.write(header(if (nameB.size > 100) name.take(30) else name, if (size > 0x1FFFFFFFFL) 0 else size, mtime, if (dir) '5' else '0', if (dir) 493 else 420))
            left = size
        }
        fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
        fun end(size: Long) { pad(size) }
        fun finish() { out.write(ByteArray(1024)) }
    }

    private fun tarStream(format: String, level: Int, raw: OutputStream): OutputStream = when (format) {
        "tar" -> raw
        "tar.gz" -> object : GZIPOutputStream(raw, 64 * 1024) { init { def.setLevel(ZIP_LV[level]) } }
        "tar.bz2" -> wrap("org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream", raw, BZ_LV[level])
        "tar.xz" -> wrap("org.apache.commons.compress.compressors.xz.XZCompressorOutputStream", raw, ZIP_LV[level])
        "tar.lz4" -> wrap("org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream", raw)
        else -> throw IllegalArgumentException(format)
    }

    private fun wrap(cls: String, out: OutputStream, vararg arg: Int): OutputStream = try {
        val k = Class.forName(cls)
        if (arg.isEmpty()) k.getConstructor(OutputStream::class.java).newInstance(out) as OutputStream
        else k.getConstructor(OutputStream::class.java, Int::class.javaPrimitiveType).newInstance(out, arg[0]) as OutputStream
    } catch (e: ClassNotFoundException) { throw java.io.IOException("This format needs a library that is not in the app") }
      catch (e: java.lang.reflect.InvocationTargetException) { throw (e.cause ?: e) }

    private fun tarFamily(es: List<Entry>, out: File, o: Opts, job: Job) {
        val buf = ByteArray(256 * 1024)
        val raw = BufferedOutputStream(FileOutputStream(out), 256 * 1024)
        val s = try { tarStream(o.format, o.level, raw) } catch (e: Throwable) { raw.close(); throw e }
        s.use { st ->
            val t = Tar(st)
            for (e in es) {
                if (job.cancelled) break
                if (e.dir) { t.begin(e.name, 0, e.file.lastModified(), true); continue }
                job.detail = e.file.name
                val size = e.file.length()
                t.begin(e.name, size, e.file.lastModified(), false)
                var sent = 0L
                FileInputStream(e.file).use { i ->
                    while (sent < size) {
                        if (job.cancelled) break
                        val n = i.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt()); if (n < 0) break
                        t.write(buf, 0, n); sent += n; job.done += n
                    }
                }
                // a file that shrank while reading: fill with zeros so the archive stays valid
                if (sent < size && !job.cancelled) { val z = ByteArray(8192); var r = size - sent; while (r > 0) { val n = minOf(z.size.toLong(), r).toInt(); t.write(z, 0, n); r -= n } }
                t.end(size)
            }
            t.finish()
        }
    }

    // ---------------- 7z (commons-compress) ----------------
    private fun sevenZ(es: List<Entry>, out: File, o: Opts, job: Job) {
        val buf = ByteArray(256 * 1024)
        try {
            val cls = Class.forName("org.apache.commons.compress.archivers.sevenz.SevenZOutputFile")
            val sz = cls.getConstructor(File::class.java).newInstance(out)
            val mCls = Class.forName("org.apache.commons.compress.archivers.sevenz.SevenZMethod")
            val method = mCls.getField(if (o.level == 0) "COPY" else "LZMA2").get(null)
            cls.getMethod("setContentCompression", mCls).invoke(sz, method)
            if (o.level > 0) {
                val opt = Class.forName("org.tukaani.xz.LZMA2Options").getConstructor(Int::class.javaPrimitiveType).newInstance(ZIP_LV[o.level])
                val conf = Class.forName("org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration").getConstructor(mCls, Any::class.java).newInstance(method, opt)
                cls.getMethod("setContentMethods", Iterable::class.java).invoke(sz, listOf(conf))
            }
            val create = cls.getMethod("createArchiveEntry", File::class.java, String::class.java)
            val put = cls.getMethod("putArchiveEntry", Class.forName("org.apache.commons.compress.archivers.ArchiveEntry"))
            val write = cls.getMethod("write", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val closeE = cls.getMethod("closeArchiveEntry")
            try {
                for (e in es) {
                    if (job.cancelled) break
                    val ent = create.invoke(sz, e.file, e.name.trimEnd('/'))
                    put.invoke(sz, ent)
                    if (!e.dir) {
                        job.detail = e.file.name
                        FileInputStream(e.file).use { i ->
                            while (true) { if (job.cancelled) break; val n = i.read(buf); if (n < 0) break; write.invoke(sz, buf, 0, n); job.done += n }
                        }
                    }
                    closeE.invoke(sz)
                }
                cls.getMethod("finish").invoke(sz)
            } finally { cls.getMethod("close").invoke(sz) }
        } catch (e: ClassNotFoundException) { throw java.io.IOException("This format needs a library that is not in the app") }
    }

    // ---------------- volumes ----------------
    /** Cuts [file] into name.001, name.002 … of [size] bytes (7-Zip style volumes) and removes the whole file. Returns the parts. */
    fun split(file: File, size: Long, job: Job): List<File> {
        if (size <= 0 || file.length() <= size) return listOf(file)
        val parts = ArrayList<File>()
        val buf = ByteArray(256 * 1024)
        FileInputStream(file).use { i ->
            var idx = 1
            while (true) {
                if (job.cancelled) break
                val part = File(file.parentFile, file.name + "." + idx.toString().padStart(3, '0'))
                var left = size; var wrote = 0L
                BufferedOutputStream(FileOutputStream(part)).use { o ->
                    while (left > 0) {
                        val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break
                        o.write(buf, 0, n); left -= n; wrote += n
                    }
                }
                if (wrote == 0L) { part.delete(); break }
                parts.add(part); idx++
                if (left > 0) break
            }
        }
        if (job.cancelled) parts.forEach { it.delete() } else file.delete()
        return parts
    }
}
