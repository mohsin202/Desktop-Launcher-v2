package com.android.desktopfiles

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Send files to another phone / computer running Desktop Files over the same Wi-Fi or hotspot.
 * Finding each other: every 2 seconds each device broadcasts a small UDP beacon (id, name, TCP port); a device that has not been heard
 * for 8 seconds disappears. Sending: one TCP connection, a header listing the items, the receiver accepts, then the bytes follow in big blocks.
 * Nothing leaves the local network. Not encrypted: the receiver's accept dialog (and the device name) is the only check.
 */
object Nearby {
    const val UDP = 48211
    private const val MAGIC = "DFN1"

    class Peer(val id: String, var name: String, var ip: String, var port: Int, var type: String, @Volatile var seen: Long)

    /** One thing to send. [kind]: photo video music app folder file contact sms. [rel] is the name (folders: the path below the folder's parent). */
    class Item(val kind: String, val rel: String, val size: Long, val open: () -> InputStream)

    class Xfer(val id: Long, val sent: Boolean, val peerId: String, val peerName: String, val name: String, val kind: String, val size: Long) {
        @Volatile var done = 0L
        /** 0 waiting, 1 running, 2 done, 3 failed, 4 declined */
        @Volatile var state = 0
        @Volatile var path = ""
        val time = System.currentTimeMillis()
    }

    val xfers = CopyOnWriteArrayList<Xfer>()
    private val ids = AtomicLong(1)
    private val peerMap = java.util.concurrent.ConcurrentHashMap<String, Peer>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var users = 0
    private var ctx: Context? = null
    private var udp: DatagramSocket? = null
    private var server: ServerSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var port = 0
    @Volatile private var pending = false

    /** Set by the UI: asks the person whether to accept; reports (accept, always). */
    var askAccept: ((from: String, count: Int, size: Long, reply: (Boolean, Boolean) -> Unit) -> Unit)? = null

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    fun changed() {
        if (pending) return
        pending = true
        main.postDelayed({ pending = false; listeners.forEach { try { it() } catch (_: Exception) {} } }, 150)
    }

    fun myId(c: Context): String = Store.str(c, "nearId", "").ifEmpty { java.util.UUID.randomUUID().toString().take(8).also { Store.setStr(c, "nearId", it) } }
    fun myName(c: Context): String = Store.str(c, "nearName", "").ifEmpty { Build.MODEL ?: "Android" }
    private fun myType(c: Context) = if (c.resources.configuration.smallestScreenWidthDp >= 600) "pc" else "phone"

    fun peers(): List<Peer> { val now = System.currentTimeMillis(); return peerMap.values.filter { now - it.seen < 8000 }.sortedBy { it.name.lowercase() } }
    fun peer(id: String): Peer? = peers().firstOrNull { it.id == id }
    fun connected() = peers().isNotEmpty()

    fun fraction(peerId: String): Float? {
        var t = 0L; var d = 0L
        for (x in xfers) if (x.peerId == peerId && (x.state == 0 || x.state == 1)) { t += x.size; d += x.done }
        return if (xfers.none { it.peerId == peerId && (it.state == 0 || it.state == 1) }) null else if (t <= 0) 0f else (d.toFloat() / t).coerceIn(0f, 1f)
    }

    fun trusted(c: Context, id: String) = Store.list(c, "nearTrust").contains(id)
    private fun trust(c: Context, id: String) { Store.setList(c, "nearTrust", (Store.list(c, "nearTrust") + id).distinct()) }

    fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni -> ni.interfaceAddresses.mapNotNull { a -> a.address.takeIf { it is java.net.Inet4Address }?.hostAddress } }
    } catch (_: Exception) { emptyList() }

    fun addManual(c: Context, ip: String) { Store.setList(c, "nearManual", (Store.list(c, "nearManual") + ip.trim()).distinct()); ping(ip.trim()) }

    private fun ping(ip: String) { Thread { try { val b = beacon(); DatagramSocket().use { it.send(DatagramPacket(b, b.size, InetAddress.getByName(ip), UDP)) } } catch (_: Exception) {} }.start() }

    private fun beacon(): ByteArray {
        val c = ctx ?: return ByteArray(0)
        return ("DFN1|" + myId(c) + "|" + myName(c).replace("|", " ") + "|" + port + "|" + myType(c)).toByteArray(Charsets.UTF_8)
    }

    // ---------- life cycle: runs while a window is in front ----------
    @Synchronized fun resume(c: Context) {
        ctx = c.applicationContext
        users++
        // devices heard a moment ago stay listed while a picker or dialog was in front (they expire 8 s after the next silence)
        val now = System.currentTimeMillis(); peerMap.values.forEach { it.seen = now }
        if (running) return
        startThreads(c)
    }

    /** Starts the sockets again (after the local-network permission was granted, which Android 17 asks for on first start). */
    @Synchronized fun restart(c: Context) {
        if (users <= 0) return
        running = false
        try { udp?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        startThreads(c)
    }

    private fun startThreads(c: Context) {
        running = true
        try {
            val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("deskfiles").also { it.setReferenceCounted(false); it.acquire() }
        } catch (_: Exception) {}
        Thread(::serve, "near-server").also { it.isDaemon = true }.start()
        Thread(::listenUdp, "near-listen").also { it.isDaemon = true }.start()
        Thread(::announce, "near-announce").also { it.isDaemon = true }.start()
    }

    @Synchronized fun pause() {
        users = maxOf(0, users - 1)
        if (users > 0 || Jobs.active()) return
        running = false
        try { udp?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        try { lock?.release() } catch (_: Exception) {}
        changed()
    }

    private fun bcasts(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try { for (ni in NetworkInterface.getNetworkInterfaces()) { if (!ni.isUp || ni.isLoopback) continue; for (ia in ni.interfaceAddresses) ia.broadcast?.let { out.add(it) } } } catch (_: Exception) {}
        try { out.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        return out.toList()
    }

    private fun announce() {
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                while (running) {
                    if (port != 0) {
                        val b = beacon()
                        for (a in bcasts()) try { s.send(DatagramPacket(b, b.size, a, UDP)) } catch (_: Exception) {}
                        for (ip in Store.list(ctx ?: break, "nearManual")) try { s.send(DatagramPacket(b, b.size, InetAddress.getByName(ip), UDP)) } catch (_: Exception) {}
                    }
                    Thread.sleep(2000)
                }
            }
        } catch (_: Exception) {}
    }

    private fun listenUdp() {
        try {
            val s = DatagramSocket(null); s.reuseAddress = true; s.bind(InetSocketAddress(UDP)); s.broadcast = true; udp = s
            val buf = ByteArray(512)
            val mine = ctx?.let { myId(it) } ?: ""
            var lastCount = -1
            while (running) {
                val pk = DatagramPacket(buf, buf.size)
                s.soTimeout = 2500
                try { s.receive(pk) } catch (_: java.net.SocketTimeoutException) { if (peers().size != lastCount) { lastCount = peers().size; changed() }; continue }
                val parts = String(pk.data, 0, pk.length, Charsets.UTF_8).split("|")
                if (parts.size < 5 || parts[0] != MAGIC || parts[1] == mine) continue
                val prt = parts[3].toIntOrNull() ?: continue
                val ip = pk.address.hostAddress ?: continue
                val known = peerMap[parts[1]]
                if (known == null) peerMap[parts[1]] = Peer(parts[1], parts[2], ip, prt, parts[4], System.currentTimeMillis()).also { changed() }
                else { val was = System.currentTimeMillis() - known.seen > 8000; known.name = parts[2]; known.ip = ip; known.port = prt; known.type = parts[4]; known.seen = System.currentTimeMillis(); if (was) changed() }
                if (peers().size != lastCount) { lastCount = peers().size; changed() }
            }
        } catch (_: Exception) {}
    }

    private fun serve() {
        try {
            val ss = ServerSocket(); ss.reuseAddress = true; ss.receiveBufferSize = 4 shl 20; ss.bind(InetSocketAddress(0)); server = ss; port = ss.localPort
            while (running) { val s = ss.accept(); Thread { receive(s) }.also { it.isDaemon = true }.start() }
        } catch (_: Exception) {}
    }

    // ---------- receiving ----------
    private val DL get() = File(Fs.ext(), "Download/DesktopFiles")

    private fun baseFor(kind: String): File = when (kind) {
        "photo" -> File(Fs.ext(), "Pictures/desktopfiles.photo")
        "video" -> File(Fs.ext(), "Movies/desktopfiles.videos")
        "music" -> File(Fs.ext(), "Music/desktopfiles.music")
        "app" -> File(DL, "app")
        "folder" -> File(DL, "folder")
        "contact" -> File(DL, "contacts")
        "sms" -> File(DL, "messages")
        else -> File(DL, "files")
    }

    private fun receive(s: Socket) {
        val c = ctx ?: run { s.close(); return }
        try {
            s.tcpNoDelay = true; s.soTimeout = 20000
            val din = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 20))
            if (din.readUTF() != MAGIC) { s.close(); return }
            val pid = din.readUTF(); val pname = din.readUTF(); val n = din.readInt()
            if (n < 0 || n > 500000) { s.close(); return }
            val kinds = Array(n) { "" }; val rels = Array(n) { "" }; val sizes = LongArray(n)
            var total = 0L
            for (i in 0 until n) { kinds[i] = din.readUTF(); rels[i] = din.readUTF(); sizes[i] = din.readLong(); total += sizes[i] }
            val ok = if (trusted(c, pid)) true else {
                val latch = CountDownLatch(1); var yes = false
                val ask = askAccept
                if (ask == null) false else {
                    main.post { ask(pname, n, total) { a, always -> yes = a; if (a && always) trust(c, pid); latch.countDown() } }
                    latch.await(60, TimeUnit.SECONDS); yes
                }
            }
            val out = s.getOutputStream(); out.write(if (ok) 1 else 0); out.flush()
            if (!ok) { s.close(); return }
            val rec = Array(n) { Xfer(ids.getAndIncrement(), false, pid, pname, rels[it].substringAfterLast('/'), kinds[it], sizes[it]) }
            rec.forEach { xfers.add(it) }; prune(); changed()
            val job = Job("Receiving " + n + " item(s) from " + pname); job.total = total
            Jobs.start(job, { j ->
                val buf = ByteArray(1 shl 20)
                val tops = HashMap<String, String>()
                try {
                    s.soTimeout = 30000
                    for (i in 0 until n) {
                        val x = rec[i]
                        if (j.cancelled) { x.state = 3; continue }
                        x.state = 1; j.detail = x.name; changed()
                        val segs = rels[i].replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
                        if (segs.isEmpty()) { skipBytes(din, sizes[i]); x.state = 3; continue }
                        val base = baseFor(kinds[i]); base.mkdirs()
                        var dir = base
                        if (kinds[i] == "folder" && segs.size > 1) {
                            val top = tops.getOrPut(segs[0]) { Fs.unique(base, segs[0]).name }
                            dir = File(base, top); for (k in 1 until segs.size - 1) dir = File(dir, segs[k])
                        } else for (k in 0 until segs.size - 1) dir = File(dir, segs[k])
                        dir.mkdirs()
                        val target = if (kinds[i] == "folder" && segs.size > 1) File(dir, segs.last()).let { if (it.exists()) Fs.unique(dir, segs.last()) else it } else Fs.unique(dir, segs.last())
                        try {
                            FileOutputStreamX(target).use { o ->
                                var left = sizes[i]
                                while (left > 0) {
                                    if (j.cancelled) throw IOException("cancelled")
                                    val r = din.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                    if (r < 0) throw IOException("connection closed")
                                    o.write(buf, 0, r); left -= r; x.done += r; j.done += r
                                }
                            }
                            x.path = target.absolutePath; x.state = 2
                            remember(c, x, target)
                        } catch (e: Exception) {
                            target.delete(); x.state = 3; j.failed++; j.detail = e.message ?: "Failed"
                            for (k in i + 1 until n) rec[k].state = 3
                            break
                        }
                        changed()
                    }
                    if (j.failed == 0) { s.getOutputStream().write(1); s.getOutputStream().flush() }
                } finally { try { s.close() } catch (_: Exception) {}; for (x in rec) if (x.state < 2) x.state = 3; changed() }
            }, { changed() })
        } catch (_: Exception) { try { s.close() } catch (_: Exception) {} }
    }

    private class FileOutputStreamX(f: File) : java.io.FilterOutputStream(java.io.FileOutputStream(f)) {
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
    }

    private fun skipBytes(i: InputStream, n: Long) { var left = n; val t = ByteArray(65536); while (left > 0) { val r = i.read(t, 0, minOf(t.size.toLong(), left).toInt()); if (r < 0) return; left -= r } }

    private fun prune() { while (xfers.size > 600) { val o = xfers.firstOrNull { it.state >= 2 } ?: break; xfers.remove(o) } }

    // ---------- history of what was received ----------
    private fun remember(c: Context, x: Xfer, f: File) {
        val line = listOf(System.currentTimeMillis(), x.peerName.replace('\t', ' '), x.kind, x.size, f.absolutePath, f.name).joinToString("\t")
        synchronized(this) { Store.setList(c, "nearHist", (listOf(line) + Store.list(c, "nearHist")).take(400)) }
        if (x.kind == "photo" || x.kind == "video" || x.kind == "music") try { android.media.MediaScannerConnection.scanFile(c, arrayOf(f.absolutePath), null, null) } catch (_: Exception) {}
    }

    class Past(val time: Long, val peer: String, val kind: String, val size: Long, val path: String, val name: String)

    fun history(c: Context): List<Past> = Store.list(c, "nearHist").mapNotNull { l ->
        val p = l.split("\t"); if (p.size < 6) null else Past(p[0].toLongOrNull() ?: 0, p[1], p[2], p[3].toLongOrNull() ?: 0, p[4], p[5])
    }

    fun clearHistory(c: Context) { Store.setList(c, "nearHist", emptyList()) }

    // ---------- sending ----------
    /** Sends what [build] produces (it runs on the worker thread, so big folders and packing do not block the screen). */
    fun send(c: Context, peer: Peer, title: String, build: (Job) -> List<Item>) {
        val job = Job(title + " → " + peer.name)
        var rec: List<Xfer> = emptyList()
        Jobs.start(job, { j ->
            val items = build(j)
            if (items.isEmpty() || j.cancelled) { if (items.isEmpty()) { j.failed++; j.detail = "Nothing to send" }; return@start }
            rec = items.map { Xfer(ids.getAndIncrement(), true, peer.id, peer.name, it.rel.substringAfterLast('/'), it.kind, it.size) }
            rec.forEach { xfers.add(it) }; prune(); j.total = items.sumOf { it.size }; changed()
            val s = Socket()
            try {
                s.sendBufferSize = 4 shl 20; s.tcpNoDelay = true
                s.connect(InetSocketAddress(peer.ip, peer.port), 6000)
                val dos = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 20))
                dos.writeUTF(MAGIC); dos.writeUTF(myId(c)); dos.writeUTF(myName(c)); dos.writeInt(items.size)
                for (it in items) { dos.writeUTF(it.kind); dos.writeUTF(it.rel); dos.writeLong(it.size) }
                dos.flush()
                s.soTimeout = 70000
                val ans = s.getInputStream().read()
                if (ans != 1) { rec.forEach { it.state = 4 }; j.failed++; j.detail = if (ans == 0) "Declined by " + peer.name else "No answer from " + peer.name; return@start }
                val buf = ByteArray(1 shl 20)
                for ((i, it) in items.withIndex()) {
                    val x = rec[i]
                    if (j.cancelled) { x.state = 3; continue }
                    x.state = 1; j.detail = x.name; changed()
                    try {
                        it.open().use { ins ->
                            var left = it.size
                            while (left > 0) {
                                if (j.cancelled) throw IOException("cancelled")
                                val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (r < 0) throw IOException("file changed while sending")
                                dos.write(buf, 0, r); left -= r; x.done += r; j.done += r
                            }
                        }
                        x.state = 2
                    } catch (e: IOException) {
                        x.state = 3; j.failed++; j.detail = e.message ?: "Failed"
                        if (e is java.net.SocketException || j.cancelled) { for (k in i + 1 until items.size) rec[k].state = 3; return@start }
                        // a file we could not read: the receiver still waits for its bytes, so the stream cannot go on
                        for (k in i + 1 until items.size) rec[k].state = 3
                        return@start
                    }
                    changed()
                }
                dos.flush()
                s.soTimeout = 60000
                s.getInputStream().read()
            } catch (e: Exception) {
                for (x in rec) if (x.state < 2) x.state = 3
                j.failed++; j.detail = if (e is java.net.ConnectException || e is java.net.SocketTimeoutException) "Could not reach " + peer.name else (e.message ?: "Failed")
            } finally { try { s.close() } catch (_: Exception) {}; for (x in rec) if (x.state < 2) x.state = 3; changed() }
        }, { changed() })
        c.let { Jobs.changed() }
    }

    // ---------- what can be sent ----------
    fun kindOf(f: File): String {
        val e = f.extension.lowercase()
        return when {
            Fs.isImage(f) -> "photo"
            Fs.isVideo(f) -> "video"
            e in Kinds.music -> "music"
            e == "apk" || e in Apks.ZIPS -> "app"
            else -> "file"
        }
    }

    fun itemsOf(files: List<File>): List<Item> {
        val out = ArrayList<Item>()
        fun walk(f: File, rel: String) {
            if (f.isDirectory) f.listFiles()?.sortedBy { it.name }?.forEach { walk(it, rel + "/" + it.name) }
            else if (f.isFile) out.add(Item("folder", rel, f.length()) { FileInputStream(f) })
        }
        for (f in files) {
            val real = Fs.target(f) ?: f
            if (real.isDirectory) walk(real, real.name)
            else if (real.isFile) out.add(Item(kindOf(real), real.name, real.length()) { FileInputStream(real) })
        }
        return out
    }

    /** The files below a folder the person allowed through Android's folder picker (Android/data, Android/obb…). */
    fun itemsOfTree(c: Context, tree: Uri): List<Item> {
        val out = ArrayList<Item>()
        val res = c.contentResolver
        val root = DocumentsContract.getTreeDocumentId(tree)
        val top = root.substringAfterLast('/').substringAfterLast(':').ifEmpty { "folder" }
        fun walk(id: String, prefix: String) {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
            val rows = ArrayList<Array<Any?>>()
            res.query(uri, cols, null, null, null)?.use { while (it.moveToNext()) rows.add(arrayOf(it.getString(0), it.getString(1), it.getString(2), if (it.isNull(3)) -1L else it.getLong(3))) }
            for (r in rows) {
                val did = r[0] as String; val nm = r[1] as String
                if (r[2] == DocumentsContract.Document.MIME_TYPE_DIR) walk(did, "$prefix$nm/")
                else {
                    val du = DocumentsContract.buildDocumentUriUsingTree(tree, did)
                    var sz = r[3] as Long
                    if (sz < 0) sz = try { res.openAssetFileDescriptor(du, "r")?.use { it.length } ?: 0L } catch (_: Exception) { 0L }
                    out.add(Item("folder", prefix + nm, sz) { res.openInputStream(du) ?: throw IOException("cannot open") })
                }
            }
        }
        walk(root, "$top/")
        return out
    }

    // ---------- permissions asked on the spot ----------
    private var permCb: (() -> Unit)? = null
    fun withPermission(act: android.app.Activity, perm: String, then: () -> Unit) {
        if (act.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) { then(); return }
        permCb = then
        act.requestPermissions(arrayOf(perm), 7740)
    }
    fun permResult(code: Int, granted: Boolean, act: android.app.Activity) {
        if (code != 7740) return
        val cb = permCb; permCb = null
        if (granted) cb?.invoke() else act.toast("Permission not given")
    }
}

/** Contacts and text messages written as files that other apps can import (a .vcf and an SMS Backup & Restore style .xml). */
object NearExport {
    fun contacts(c: Context, out: File): Int {
        val cr = c.contentResolver
        class P(val name: String) { val phones = LinkedHashSet<String>(); val mails = LinkedHashSet<String>() }
        val map = LinkedHashMap<Long, P>()
        cr.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf("contact_id", "display_name", "data1"), null, null, "display_name")?.use {
            while (it.moveToNext()) map.getOrPut(it.getLong(0)) { P(it.getString(1) ?: "") }.phones.add(it.getString(2) ?: "")
        }
        cr.query(android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf("contact_id", "display_name", "data1"), null, null, null)?.use {
            while (it.moveToNext()) map.getOrPut(it.getLong(0)) { P(it.getString(1) ?: "") }.mails.add(it.getString(2) ?: "")
        }
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            for (p in map.values) {
                w.write("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:" + esc(p.name) + "\r\nN:" + esc(p.name) + ";;;;\r\n")
                for (t in p.phones) w.write("TEL;TYPE=CELL:" + t + "\r\n")
                for (m in p.mails) w.write("EMAIL:" + m + "\r\n")
                w.write("END:VCARD\r\n")
            }
        }
        return map.size
    }

    fun messages(c: Context, out: File): Int {
        fun x(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").filter { it.code >= 32 || it == '\n' || it == '\t' || it == '\r' }
        var n = 0
        val body = java.io.File.createTempFile("sms", ".part", c.cacheDir)
        body.bufferedWriter(Charsets.UTF_8).use { w ->
            c.contentResolver.query(Uri.parse("content://sms"), arrayOf("address", "date", "type", "body", "read", "date_sent"), null, null, "date")?.use {
                while (it.moveToNext()) {
                    w.write("  <sms protocol=\"0\" address=\"" + x(it.getString(0)) + "\" date=\"" + it.getLong(1) + "\" type=\"" + it.getInt(2) + "\" subject=\"null\" body=\"" + x(it.getString(3)) +
                        "\" toa=\"null\" sc_toa=\"null\" service_center=\"null\" read=\"" + it.getInt(4) + "\" status=\"-1\" locked=\"0\" date_sent=\"" + it.getLong(5) + "\" />\n")
                    n++
                }
            }
        }
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<smses count=\"$n\">\n")
            body.bufferedReader(Charsets.UTF_8).use { r -> r.copyTo(w) }
            w.write("</smses>\n")
        }
        body.delete()
        return n
    }
}
