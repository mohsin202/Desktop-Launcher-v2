package com.android.desktopfiles

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Read-only provider: hands files to other apps (open with, share). */
class FileProv : ContentProvider() {
    companion object { const val AUTH = "com.android.desktopfiles.files" }

    override fun onCreate() = true

    /** Folders other apps may read through this provider: internal storage plus every mounted drive (USB disk, SD card). */
    private fun roots(): List<String> {
        val l = ArrayList<String>()
        l += Environment.getExternalStorageDirectory().absolutePath
        try {
            val sm = context?.getSystemService(android.os.storage.StorageManager::class.java)
            sm?.storageVolumes?.forEach { v -> v.directory?.let { d -> l += d.absolutePath } }
        } catch (_: Exception) {}
        l += "/storage"
        return l
    }

    private fun fileOf(uri: Uri): File? {
        // checked on the written-out path (no ".." tricks) and on the real path behind any link; a drive is often reached through a link
        val plain = File(uri.path ?: return null).toPath().normalize().toString()
        val real = try { File(plain).canonicalPath } catch (_: Exception) { plain }
        val ok = roots().any { r -> plain == r || plain.startsWith("$r/") || real == r || real.startsWith("$r/") } ||
            real.startsWith("/mnt/media_rw/") || real.startsWith("/mnt/pass_through/")
        return if (ok) File(plain) else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileOf(uri) ?: throw FileNotFoundException()
        if (!f.isFile) throw FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = fileOf(uri)?.let { Fs.mime(it) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileOf(uri) ?: return null
        val cols = projection?.map { it } ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols.toTypedArray())
        c.addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else f.name })
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
