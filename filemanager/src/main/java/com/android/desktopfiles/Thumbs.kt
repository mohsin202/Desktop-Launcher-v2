package com.android.desktopfiles

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/** File icons: folder, file type tiles, and thumbnails for pictures, videos and APKs. A shortcut gets the little arrow. */
object Thumbs {
    private val cache = LruCache<String, Bitmap>(120)
    private val ex = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private fun extColor(e: String): Int = when (e) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> 0xFF43A047
        "mp4", "mkv", "avi", "mov", "webm", "3gp" -> 0xFFE53935
        "mp3", "wav", "flac", "ogg", "m4a", "aac" -> 0xFF8E24AA
        "pdf" -> 0xFFD32F2F
        "doc", "docx" -> 0xFF1E5FBF
        "xls", "xlsx", "csv" -> 0xFF1E8E4E
        "ppt", "pptx" -> 0xFFD35400
        "apk", "apks", "apkm", "apk+", "xapk" -> 0xFF2E7D32
        "zip", "rar", "7z", "tar", "gz" -> 0xFFF57C00
        else -> 0xFF1E88E5
    }.toInt()

    /** Plain icon without thumbnail. */
    fun icon(f: File): Drawable {
        val base: Drawable = if (f.isDirectory) Glyph(Glyph.Kind.FOLDER) else Glyph(Glyph.Kind.FILE, ext = f.extension.ifEmpty { "file" }, extColor = extColor(f.extension.lowercase()))
        return if (f.extension == "desktoplink") {
            val t = Fs.target(f)
            ShortcutBadged(if (t != null) iconOf(t) else base)
        } else base
    }

    private fun iconOf(t: File): Drawable =
        if (t.isDirectory) Glyph(Glyph.Kind.FOLDER) else Glyph(Glyph.Kind.FILE, ext = t.extension.ifEmpty { "file" }, extColor = extColor(t.extension.lowercase()))

    fun bind(iv: ImageView, f: File, px: Int) {
        iv.setImageDrawable(icon(f))
        val real = Fs.target(f) ?: f
        if (real.isDirectory) { iv.tag = null; return }
        val e = real.extension.lowercase()
        val isApk = e == "apk" || e in Apks.ZIPS
        if (!Fs.isImage(real) && !Fs.isVideo(real) && !isApk) { iv.tag = null; return }
        val key = real.absolutePath + "@" + px + "@" + real.lastModified()
        iv.tag = key
        val badge = f.extension == "desktoplink"
        cache.get(key)?.let { iv.setImageDrawable(wrap(iv.context, it, badge)); return }
        ex.execute {
            val bmp: Bitmap? = try {
                when {
                    isApk -> if (e == "apk") apkIcon(iv.context, real, px) else Apks.bundleIcon(iv.context, real, px)
                    Fs.isImage(real) -> ThumbnailUtils.createImageThumbnail(real, Size(px, px), null)
                    else -> ThumbnailUtils.createVideoThumbnail(real, Size(px, px), null)
                }
            } catch (_: Throwable) { null }
            if (bmp != null) {
                cache.put(key, bmp)
                main.post { if (iv.tag == key) iv.setImageDrawable(wrap(iv.context, bmp, badge)) }
            }
        }
    }

    private fun wrap(c: Context, b: Bitmap, badge: Boolean): Drawable {
        val d = BitmapDrawable(c.resources, b)
        return if (badge) ShortcutBadged(d) else d
    }

    private fun apkIcon(c: Context, f: File, px: Int): Bitmap? {
        val pm = c.packageManager
        val info = pm.getPackageArchiveInfo(f.absolutePath, 0) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = f.absolutePath; ai.publicSourceDir = f.absolutePath
        val d = ai.loadIcon(pm)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, px, px); d.draw(cv)
        return bmp
    }
}
