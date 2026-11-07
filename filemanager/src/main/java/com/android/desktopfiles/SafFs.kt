package com.android.desktopfiles

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder

/** A folder offered by another app through Android's file picker (Google Drive, OneDrive, Dropbox, a USB drive…). No account setup here: the other app does the sign-in. */
class SafAcct(val id: String, val name: String, val tree: String)

class SafFs(private val c: Context, private val a: SafAcct) : RemoteFs {
    override val name get() = a.name
    private val tree: Uri = Uri.parse(a.tree)
    private val res get() = c.contentResolver

    private class Doc(val id: String, val name: String, val dir: Boolean, val size: Long, val mtime: Long)

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun children(id: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val out = ArrayList<Doc>()
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val cur = try { res.query(uri, cols, null, null, null) } catch (e: Exception) { throw Dav.DavException(e.message ?: "Could not read the folder") }
        cur?.use {
            while (it.moveToNext()) out.add(Doc(it.getString(0), it.getString(1) ?: "", it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                if (it.isNull(3)) 0L else it.getLong(3), if (it.isNull(4)) 0L else it.getLong(4)))
        } ?: throw Dav.DavException("The app did not answer")
        return out
    }

    /** The document id of [path] ("" or "/" is the chosen folder itself), or null. */
    private fun find(path: String): String? {
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (seg in path.split("/").filter { it.isNotEmpty() }) {
            id = children(id).firstOrNull { it.name == seg }?.id ?: return null
        }
        return id
    }

    private fun need(path: String) = find(path) ?: throw Dav.DavException("Not found (404)")
    private fun parentOf(path: String) = path.trimEnd('/').substringBeforeLast('/', "")
    private fun leaf(path: String) = path.trimEnd('/').substringAfterLast('/')

    override fun list(path: String): List<Dav.Entry> {
        val base = path.trimEnd('/')
        return children(need(path)).map { Dav.Entry(it.name, "$base/${it.name}", it.dir, it.size, it.mtime) }
    }

    override fun get(path: String, out: OutputStream, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        val i = res.openInputStream(docUri(need(path))) ?: throw Dav.DavException("Could not read the file")
        i.use { s ->
            val buf = ByteArray(128 * 1024)
            while (true) { if (cancelled()) break; val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); onBytes(n.toLong()) }
        }
    }

    override fun put(path: String, f: File, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        val parent = need(parentOf(path)); val n = leaf(path)
        val existing = children(parent).firstOrNull { it.name == n }
        val uri = if (existing != null) docUri(existing.id) else {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
            DocumentsContract.createDocument(res, docUri(parent), mime, n) ?: throw Dav.DavException("The app did not accept the file")
        }
        val o = res.openOutputStream(uri, "wt") ?: throw Dav.DavException("The app did not accept the file")
        o.use { w ->
            f.inputStream().use { i ->
                val buf = ByteArray(128 * 1024)
                while (true) { if (cancelled()) throw Dav.DavException("Cancelled"); val k = i.read(buf); if (k < 0) break; w.write(buf, 0, k); onBytes(k.toLong()) }
            }
        }
    }

    override fun mkcol(path: String) {
        DocumentsContract.createDocument(res, docUri(need(parentOf(path))), DocumentsContract.Document.MIME_TYPE_DIR, leaf(path))
            ?: throw Dav.DavException("The app did not accept the folder")
    }

    override fun delete(path: String) {
        if (!DocumentsContract.deleteDocument(res, docUri(need(path)))) throw Dav.DavException("Could not delete")
    }

    override fun move(from: String, to: String) {
        val src = docUri(need(from))
        if (parentOf(from) == parentOf(to)) { DocumentsContract.renameDocument(res, src, leaf(to)) ?: throw Dav.DavException("Could not rename"); return }
        DocumentsContract.moveDocument(res, src, docUri(need(parentOf(from))), docUri(need(parentOf(to)))) ?: throw Dav.DavException("Could not move")
    }
}

object SafAccts {
    private fun e(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun d(s: String) = URLDecoder.decode(s, "UTF-8")

    fun all(c: Context): List<SafAcct> = Store.list(c, "saf").mapNotNull { line ->
        val p = line.split("&")
        if (p.size < 3) null else try { SafAcct(d(p[0]), d(p[1]), d(p[2])) } catch (_: Exception) { null }
    }

    private fun save(c: Context, l: List<SafAcct>) = Store.setList(c, "saf", l.map { listOf(e(it.id), e(it.name), e(it.tree)).joinToString("&") })
    fun add(c: Context, a: SafAcct) = save(c, all(c) + a)
    fun remove(c: Context, id: String) {
        all(c).firstOrNull { it.id == id }?.let { try { c.contentResolver.releasePersistableUriPermission(Uri.parse(it.tree), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {} }
        save(c, all(c).filter { it.id != id })
    }

    /** Opens the system picker; the chosen folder is saved and handed to [done]. */
    fun addFromPicker(act: android.app.Activity, done: (SafAcct) -> Unit) {
        val m = act as? MainActivity
        if (m == null) { act.toast("Could not open the picker"); return }
        m.pickTree { uri ->
            var title = "Folder"
            try {
                val id = DocumentsContract.getTreeDocumentId(uri)
                act.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(uri, id), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) title = it.getString(0) ?: title }
            } catch (_: Exception) {}
            Dialogs.ask(act, "Name", title, "Add") { n ->
                val a = SafAcct("saf:" + System.currentTimeMillis(), n, uri.toString())
                add(act, a); done(a)
            }
        }
    }
}
