package com.android.desktoplauncher

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

/** Read-only provider used to hand desktop files to other apps (open / share / clipboard). */
class DesktopProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(uri: Uri): File? {
        val f = File(uri.path ?: return null).canonicalFile
        val ext = Environment.getExternalStorageDirectory().canonicalPath
        return if (f.path.startsWith(ext)) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileOf(uri) ?: throw FileNotFoundException()
        if (!f.isFile) throw FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = fileOf(uri)?.let { Files.mime(it) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
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
