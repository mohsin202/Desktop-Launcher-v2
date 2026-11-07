package com.android.desktopfiles

import java.io.File
import java.io.OutputStream

/** A server the file manager can browse: WebDAV (Nextcloud, ownCloud, NAS) or a Windows share (SMB). Paths start with "/" and are relative to the account. */
interface RemoteFs {
    val name: String
    fun list(path: String): List<Dav.Entry>
    fun get(path: String, out: OutputStream, onBytes: (Long) -> Unit, cancelled: () -> Boolean)
    fun put(path: String, f: File, onBytes: (Long) -> Unit, cancelled: () -> Boolean)
    fun mkcol(path: String)
    fun delete(path: String)
    fun move(from: String, to: String)
}

/** A saved account as the screens see it. [kind] is "WebDAV" or "SMB". */
class Remote(val id: String, val name: String, val kind: String, val fs: RemoteFs)

class DavFs(private val a: Dav.Acct) : RemoteFs {
    override val name get() = a.name
    override fun list(path: String) = Dav.list(a, path)
    override fun get(path: String, out: OutputStream, onBytes: (Long) -> Unit, cancelled: () -> Boolean) = Dav.get(a, path, out, onBytes, cancelled)
    override fun put(path: String, f: File, onBytes: (Long) -> Unit, cancelled: () -> Boolean) = Dav.put(a, path, f, onBytes, cancelled)
    override fun mkcol(path: String) = Dav.mkcol(a, path)
    override fun delete(path: String) = Dav.delete(a, path)
    override fun move(from: String, to: String) = Dav.move(a, from, to)
}
