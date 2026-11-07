package com.android.desktopfiles

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import jcifs.smb.SmbFileOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Properties

/** A Windows share (SMB 2/3) through the jcifs-ng library. [server] is "host", "host/share" or "host/share/folder". */
class SmbAcct(val id: String, val name: String, val server: String, val user: String, val pass: String)

class SmbFs(private val a: SmbAcct) : RemoteFs {
    override val name get() = a.name

    private val ctx: CIFSContext by lazy {
        val pr = Properties()
        pr.setProperty("jcifs.smb.client.minVersion", "SMB202")
        pr.setProperty("jcifs.smb.client.maxVersion", "SMB311")
        pr.setProperty("jcifs.smb.client.responseTimeout", "30000")
        pr.setProperty("jcifs.smb.client.connTimeout", "15000")
        val base = BaseContext(PropertyConfiguration(pr))
        if (a.user.isEmpty()) base.withGuestCredentials()
        else {
            val dom = if (a.user.contains('\\')) a.user.substringBefore('\\') else null
            val usr = if (a.user.contains('\\')) a.user.substringAfter('\\') else a.user
            base.withCredentials(NtlmPasswordAuthenticator(dom, usr, a.pass))
        }
    }

    private fun url(path: String, dir: Boolean = false): String {
        val s = "smb://" + a.server.trim().trim('/') + path.trimEnd('/')
        return if (dir) "$s/" else s
    }

    private fun f(path: String, dir: Boolean = false) = SmbFile(url(path, dir), ctx)

    private fun wrap(e: Exception): Exception =
        if (e is SmbAuthException) Dav.DavException("Wrong user name or password")
        else Dav.DavException(e.message ?: "Could not connect")

    override fun list(path: String): List<Dav.Entry> = try {
        val base = path.trimEnd('/')
        f(path, true).listFiles().map {
            val dir = it.isDirectory
            val n = it.name.trimEnd('/')
            Dav.Entry(n, "$base/$n", dir, if (dir) 0L else it.length(), it.lastModified())
        }
    } catch (e: Exception) { throw wrap(e) }

    override fun get(path: String, out: OutputStream, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        try {
            SmbFileInputStream(f(path)).use { i ->
                val buf = ByteArray(128 * 1024)
                while (true) { if (cancelled()) break; val n = i.read(buf); if (n < 0) break; out.write(buf, 0, n); onBytes(n.toLong()) }
            }
        } catch (e: Exception) { throw wrap(e) }
    }

    override fun put(path: String, file: File, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        try {
            SmbFileOutputStream(f(path)).use { o ->
                file.inputStream().use { i ->
                    val buf = ByteArray(128 * 1024)
                    while (true) { if (cancelled()) throw Dav.DavException("Cancelled"); val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); onBytes(n.toLong()) }
                }
            }
        } catch (e: Exception) { throw wrap(e) }
    }

    override fun mkcol(path: String) { try { f(path, true).mkdir() } catch (e: Exception) { throw wrap(e) } }

    override fun delete(path: String) {
        try { val x = f(path); (if (x.isDirectory) f(path, true) else x).delete() } catch (e: Exception) { throw wrap(e) }
    }

    override fun move(from: String, to: String) {
        try { val x = f(from); val dir = x.isDirectory; (if (dir) f(from, true) else x).renameTo(f(to, dir)) } catch (e: Exception) { throw wrap(e) }
    }
}

object SmbAccts {
    private fun e(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun d(s: String) = URLDecoder.decode(s, "UTF-8")

    fun all(c: Context): List<SmbAcct> = Store.list(c, "smb").mapNotNull { line ->
        val p = line.split("&")
        if (p.size < 5) null else try { SmbAcct(d(p[0]), d(p[1]), d(p[2]), d(p[3]), d(p[4])) } catch (_: Exception) { null }
    }

    private fun save(c: Context, l: List<SmbAcct>) =
        Store.setList(c, "smb", l.map { listOf(e(it.id), e(it.name), e(it.server), e(it.user), e(it.pass)).joinToString("&") })

    fun add(c: Context, a: SmbAcct) = save(c, all(c) + a)
    fun remove(c: Context, id: String) = save(c, all(c).filter { it.id != id })

    fun addDialog(act: android.app.Activity, done: (SmbAcct) -> Unit) {
        val p = Ui.pal(act)
        fun field(hint: String, pw: Boolean = false): EditText {
            val et = EditText(act); et.hint = T.t(hint); et.setSingleLine()
            et.inputType = if (pw) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_CLASS_TEXT
            return et
        }
        val name = field("Name (for example Office PC)")
        val server = field("Server and share (192.168.1.5/Documents)")
        val user = field("User name (empty = guest)")
        val pass = field("Password", true)
        val msg = Ui.text(act, "", 12f, 0xFFE53935.toInt())
        val box = LinearLayout(act); box.orientation = LinearLayout.VERTICAL; box.setPaddingRelative(act.dp(20), act.dp(8), act.dp(20), 0)
        for (v in listOf(name, server, user, pass, msg)) box.addView(v)
        val sv = ScrollView(act); sv.addView(box)
        val dlg = AlertDialog.Builder(act).setTitle(T.t("Add a Windows share (SMB)")).setView(sv)
            .setPositiveButton(T.t("Add"), null).setNegativeButton(T.t("Cancel"), null).create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { b ->
            val s = server.text.toString().trim().removePrefix("smb://").replace('\\', '/').trim('/')
            if (s.isEmpty()) { msg.text = T.t("Type the server address"); return@setOnClickListener }
            val a = SmbAcct("smb:" + System.currentTimeMillis(), name.text.toString().trim().ifEmpty { s.substringBefore('/') }, s, user.text.toString().trim(), pass.text.toString())
            b.isEnabled = false; msg.setTextColor(p.sub); msg.text = T.t("Connecting…")
            Thread {
                val err = try { SmbFs(a).list(""); "" } catch (ex: Exception) { ex.message ?: "Could not connect" }
                act.runOnUiThread {
                    if (err.isEmpty()) { add(act, a); dlg.dismiss(); done(a) }
                    else { b.isEnabled = true; msg.setTextColor(0xFFE53935.toInt()); msg.text = T.t(err) }
                }
            }.start()
        }
    }
}
