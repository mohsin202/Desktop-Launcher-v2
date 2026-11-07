package com.android.desktopfiles

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A small WebDAV client (Nextcloud, ownCloud, many NAS boxes). It speaks HTTP itself over a socket, because the system HTTP class
 * refuses methods like PROPFIND. It needs no Android classes, so it can be tested on a normal computer.
 */
object Dav {
    class Acct(val id: String, val name: String, val url: String, val user: String, val pass: String, val trustAll: Boolean)
    class Entry(val name: String, val path: String, val dir: Boolean, val size: Long, val mtime: Long)
    class DavException(msg: String) : Exception(msg)

    class Resp(val code: Int, val headers: Map<String, String>, private val sock: Socket, val body: InputStream) : Closeable {
        override fun close() { try { sock.close() } catch (_: Exception) {} }
    }

    private class Bounded(private val i: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val b = i.read(); if (b >= 0) left--; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = i.read(b, off, minOf(len.toLong(), left).toInt()); if (n > 0) left -= n; return n
        }
    }

    private class Chunked(private val i: InputStream) : InputStream() {
        private var left = 0L; private var done = false
        private fun next() {
            if (done) return
            var line = readLine(i)
            if (line.isEmpty()) line = readLine(i)   // the CRLF after the previous chunk
            left = line.substringBefore(';').trim().toLongOrNull(16) ?: 0L
            if (left == 0L) { done = true; while (readLine(i).isNotEmpty()) { } }
        }
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left == 0L) next()
            if (done && left == 0L) return -1
            val n = i.read(b, off, minOf(len.toLong(), left).toInt()); if (n > 0) left -= n; return n
        }
    }

    private fun readLine(i: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private val trustAllFactory: SSLSocketFactory by lazy {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS"); ctx.init(null, tm, SecureRandom()); ctx.socketFactory
    }

    private fun enc(path: String): String = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun base(a: Acct): URI = URI(a.url.trim().trimEnd('/'))
    private fun basePath(a: Acct): String = base(a).rawPath ?: ""
    private fun basePathDecoded(a: Acct): String = base(a).path ?: ""

    /** Path on the server (encoded) for a path inside the account. */
    private fun target(a: Acct, path: String): String = (basePath(a) + enc(path)).ifEmpty { "/" }

    private fun open(a: Acct, scheme: String, host: String, port: Int, target: String, method: String, hdr: Map<String, String>,
                     len: Long, writer: ((OutputStream) -> Unit)?, redirects: Int): Resp {
        val https = scheme.equals("https", true)
        val sock = if (https) (if (a.trustAll) trustAllFactory else SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(host, port) else Socket(host, port)
        sock.soTimeout = 60_000
        try {
            val out = sock.getOutputStream().buffered(65536)
            val h = StringBuilder("$method $target HTTP/1.1\r\nHost: $host" + (if (port != (if (https) 443 else 80)) ":$port" else "") + "\r\n")
            if (a.user.isNotEmpty()) h.append("Authorization: Basic " + Base64.getEncoder().encodeToString((a.user + ":" + a.pass).toByteArray()) + "\r\n")
            h.append("User-Agent: DesktopFiles\r\nConnection: close\r\n")
            for ((k, v) in hdr) h.append("$k: $v\r\n")
            if (writer != null || method == "PUT" || method == "PROPFIND" || method == "MKCOL") h.append("Content-Length: $len\r\n")
            h.append("\r\n")
            out.write(h.toString().toByteArray())
            writer?.invoke(out)
            out.flush()
            val inp = BufferedInputStream(sock.getInputStream(), 65536)
            val status = readLine(inp)
            val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: throw DavException("Bad answer from the server")
            val headers = HashMap<String, String>()
            while (true) { val l = readLine(inp); if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
            val body: InputStream = when {
                method == "HEAD" || code == 204 || code == 304 -> ByteArrayInputStream(ByteArray(0))
                headers["transfer-encoding"]?.contains("chunked", true) == true -> Chunked(inp)
                headers["content-length"] != null -> Bounded(inp, headers["content-length"]!!.toLong())
                else -> inp
            }
            val resp = Resp(code, headers, sock, body)
            if (code in listOf(301, 302, 303, 307, 308) && writer == null && redirects > 0) {
                val loc = headers["location"]
                if (loc != null) {
                    resp.close()
                    val u = try { URI(loc) } catch (_: Exception) { null }
                    if (u != null) {
                        val ns = u.scheme ?: scheme; val nh = u.host ?: host
                        val np = if (u.port > 0) u.port else if (u.host == null) port else if (ns.equals("https", true)) 443 else 80
                        val nt = (u.rawPath ?: "/") + (if (u.rawQuery != null) "?" + u.rawQuery else "")
                        return open(a, ns, nh, np, nt, if (code == 303) "GET" else method, hdr, len, null, redirects - 1)
                    }
                }
            }
            return resp
        } catch (e: Exception) { try { sock.close() } catch (_: Exception) {}; throw e }
    }

    private fun call(a: Acct, method: String, path: String, hdr: Map<String, String> = emptyMap(), body: ByteArray? = null,
                     len: Long = body?.size?.toLong() ?: 0L, writer: ((OutputStream) -> Unit)? = null): Resp {
        val u = base(a)
        val scheme = u.scheme ?: "http"
        val port = if (u.port > 0) u.port else if (scheme.equals("https", true)) 443 else 80
        val w: ((OutputStream) -> Unit)? = writer ?: body?.let { b -> { o: OutputStream -> o.write(b) } }
        return open(a, scheme, u.host ?: throw DavException("The address is not valid"), port, target(a, path), method, hdr, len, w, 5)
    }

    private fun check(r: Resp, ok: IntRange = 200..299) {
        if (r.code in ok) return
        r.close()
        throw DavException(when (r.code) { 401, 403 -> "Wrong user name or password (" + r.code + ")"; 404 -> "Not found (404)"; 405 -> "Not allowed (405)"; 409 -> "Folder missing (409)"; 507 -> "No space left on the server"; else -> "Server answered " + r.code })
    }

    private val PROP = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:getcontentlength/>" +
        "<d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>").toByteArray()

    fun list(a: Acct, path: String): List<Entry> {
        val r = call(a, "PROPFIND", path, mapOf("Depth" to "1", "Content-Type" to "application/xml"), PROP)
        try {
            check(r, 200..299)
            val doc = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }.newDocumentBuilder().parse(r.body)
            val nodes = doc.getElementsByTagNameNS("DAV:", "response")
            val df = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            val self = path.trimEnd('/')
            val bp = basePathDecoded(a).trimEnd('/')
            val out = ArrayList<Entry>()
            for (i in 0 until nodes.length) {
                val n = nodes.item(i) as org.w3c.dom.Element
                var href = n.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: continue
                if (href.contains("://")) href = URI(href).rawPath ?: href
                val dec = URLDecoder.decode(href.replace("+", "%2B"), "UTF-8").trimEnd('/')
                val rel = (if (dec.startsWith(bp)) dec.substring(bp.length) else dec)
                if (rel.trimEnd('/') == self || rel.isEmpty() && self.isEmpty()) continue
                val isDir = n.getElementsByTagNameNS("DAV:", "collection").length > 0
                val size = n.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
                val mt = try { df.parse(n.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)?.textContent?.trim() ?: "")?.time ?: 0L } catch (_: Exception) { 0L }
                out.add(Entry(rel.substringAfterLast('/'), rel, isDir, size, mt))
            }
            return out
        } finally { r.close() }
    }

    fun get(a: Acct, path: String, out: OutputStream, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        val r = call(a, "GET", path)
        try {
            check(r)
            val buf = ByteArray(128 * 1024)
            while (true) { if (cancelled()) break; val n = r.body.read(buf); if (n < 0) break; out.write(buf, 0, n); onBytes(n.toLong()) }
        } finally { r.close() }
    }

    fun put(a: Acct, path: String, f: File, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        val r = call(a, "PUT", path, emptyMap(), null, f.length()) { o ->
            f.inputStream().use { i ->
                val buf = ByteArray(128 * 1024)
                while (true) { if (cancelled()) throw DavException("Cancelled"); val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); onBytes(n.toLong()) }
            }
        }
        try { check(r) } finally { r.close() }
    }

    fun mkcol(a: Acct, path: String) { val r = call(a, "MKCOL", path); try { check(r) } finally { r.close() } }
    fun delete(a: Acct, path: String) { val r = call(a, "DELETE", path); try { check(r) } finally { r.close() } }

    fun move(a: Acct, from: String, to: String) {
        val u = base(a)
        val dest = (u.scheme ?: "http") + "://" + u.authority + target(a, to)
        val r = call(a, "MOVE", from, mapOf("Destination" to dest, "Overwrite" to "F"))
        try { check(r) } finally { r.close() }
    }

    /** Used by "Add": reads the top folder to prove the address and password work. */
    fun test(a: Acct) { list(a, "") }
}
