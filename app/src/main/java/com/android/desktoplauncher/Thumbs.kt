package com.android.desktoplauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/** Picture/video thumbnails for file icons (search, downloads card and desktop shortcuts). */
object Thumbs {
    private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val VID = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v")
    private val cache = LruCache<String, Bitmap>(120)
    private val ex = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** The real file behind a .desktoplink shortcut, or the file itself. */
    private fun target(f: File): File {
        if (f.extension != "desktoplink") return f
        return try { f.readLines().getOrNull(1)?.let { File(it) } ?: f } catch (_: Exception) { f }
    }

    /** Shows the normal file icon right away, then swaps in a thumbnail when the file is a picture or video. */
    fun bind(iv: ImageView, f: File, px: Int, radiusPx: Float = px * 0.18f) {
        iv.setImageDrawable(Icons.file(f))
        if (f.isDirectory) { iv.tag = null; return }
        val key = f.absolutePath + "@" + px
        iv.tag = key
        cache.get(key)?.let { iv.setImageDrawable(BitmapDrawable(iv.resources, it)); return }
        val ext = f.extension.lowercase()
        if (ext != "desktoplink" && ext !in IMG && ext !in VID) return
        ex.execute {
            val t = target(f)
            val te = t.extension.lowercase()
            val bmp = try {
                if (!t.exists()) null
                else if (te in IMG) ThumbnailUtils.createImageThumbnail(t, Size(px, px), null)
                else if (te in VID) ThumbnailUtils.createVideoThumbnail(t, Size(px, px), null)
                else null
            } catch (_: Throwable) { null }
            if (bmp != null) {
                val out = rounded(bmp, px, radiusPx)
                cache.put(key, out)
                main.post { if (iv.tag == key) iv.setImageDrawable(BitmapDrawable(iv.resources, out)) }
            } else if (t != f) {
                // shortcut to a non-media file: show the target's file icon
                main.post { if (iv.tag == key) iv.setImageDrawable(Icons.file(t)) }
            }
        }
    }

    private fun rounded(src: Bitmap, px: Int, r: Float): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val scale = maxOf(px / src.width.toFloat(), px / src.height.toFloat())
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate((px - src.width * scale) / 2f, (px - src.height * scale) / 2f)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(m)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = shader
        Canvas(out).drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), r, r, p)
        return out
    }
}
