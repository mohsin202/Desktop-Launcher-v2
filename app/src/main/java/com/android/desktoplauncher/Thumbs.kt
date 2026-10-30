package com.android.desktoplauncher

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/**
 * Picture/video thumbnails for file icons (search, downloads card, desktop shortcuts).
 * They are adaptive icons, so the phone's icon shape (circle, squircle, square...) is applied automatically.
 */
object Thumbs {
    private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val VID = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v")
    private val cache = LruCache<String, Bitmap>(80)
    private val ex = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private fun target(f: File): File {
        if (f.extension != "desktoplink") return f
        return try { f.readLines().getOrNull(1)?.let { File(it) } ?: f } catch (_: Exception) { f }
    }

    private fun adaptive(res: Resources, bmp: Bitmap, video: Boolean, bg: Int): Drawable =
        if (video) AdaptiveIconDrawable(ColorDrawable(bg), BitmapDrawable(res, bmp))
        else AdaptiveIconDrawable(BitmapDrawable(res, bmp), ColorDrawable(Color.TRANSPARENT))

    /** Shows the normal file icon right away, then swaps in a thumbnail when the file is a picture or video. */
    fun bind(iv: ImageView, f: File, px: Int) {
        iv.setImageDrawable(Icons.file(f))
        if (f.isDirectory) { iv.tag = null; return }
        val ctx = iv.context
        val themed = Prefs.bool(ctx, Prefs.K_THEMED, false)
        val bgC = if (themed) Colors.iconBg(ctx) else 0xFF263238.toInt()
        val fgC = if (themed) Colors.iconFg(ctx) else 0xFFECEFF1.toInt()
        val key = f.absolutePath + "@" + px + "#" + bgC
        iv.tag = key
        val ext = f.extension.lowercase()
        if (ext != "desktoplink" && ext !in IMG && ext !in VID) return
        val res = iv.resources
        cache.get(key)?.let { iv.setImageDrawable(adaptive(res, it, ext in VID, bgC)); return }
        val layer = (px * 1.5f).toInt().coerceAtLeast(2)
        ex.execute {
            val t = target(f)
            val te = t.extension.lowercase()
            val bmp = try {
                if (!t.exists()) null
                else if (te in IMG) ThumbnailUtils.createImageThumbnail(t, Size(layer, layer), null)
                else if (te in VID) ThumbnailUtils.createVideoThumbnail(t, Size(layer, layer), null)
                else null
            } catch (_: Throwable) { null }
            if (bmp != null) {
                val vid = te in VID
                val out = if (vid) filmFrame(bmp, layer, px, bgC, fgC) else cropSquare(bmp, layer)
                cache.put(key, out)
                main.post { if (iv.tag == key) iv.setImageDrawable(adaptive(res, out, vid, bgC)) }
            } else if (t != f) {
                main.post { if (iv.tag == key) iv.setImageDrawable(Icons.file(t)) }
            }
        }
    }

    private fun cropSquare(src: Bitmap, side: Int): Bitmap {
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val scale = maxOf(side / src.width.toFloat(), side / src.height.toFloat())
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate((side - src.width * scale) / 2f, (side - src.height * scale) / 2f)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Foreground layer of the video icon (layer = adaptive 108dp layer). The film strip sits inside the safe zone,
     * so it is never cut by a round mask; the background layer supplies the (Material You) colour.
     */
    private fun filmFrame(src: Bitmap, layer: Int, px: Int, bg: Int, fg: Int): Bitmap {
        val out = Bitmap.createBitmap(layer, layer, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val s = px * 0.74f
        cv.translate((layer - s) / 2f, (layer - s) / 2f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = fg
        val n = 5
        val hw = s * 0.08f
        val gap = (s - n * hw) / (n + 1)
        // Portrait videos get the perforations on the left and right, landscape/square ones on top and bottom.
        val portrait = src.height > src.width * 1.05f
        for (i in 0 until n) {
            val x = gap + i * (hw + gap)
            if (portrait) {
                cv.drawRoundRect(RectF(s * 0.02f, x, s * 0.02f + hw, x + hw), hw * 0.3f, hw * 0.3f, p)
                cv.drawRoundRect(RectF(s * 0.98f - hw, x, s * 0.98f, x + hw), hw * 0.3f, hw * 0.3f, p)
            } else {
                cv.drawRoundRect(RectF(x, s * 0.02f, x + hw, s * 0.02f + hw), hw * 0.3f, hw * 0.3f, p)
                cv.drawRoundRect(RectF(x, s * 0.98f - hw, x + hw, s * 0.98f), hw * 0.3f, hw * 0.3f, p)
            }
        }
        val r = if (portrait) RectF(s * 0.17f, 0f, s * 0.83f, s) else RectF(0f, s * 0.17f, s, s * 0.83f)
        val scale = maxOf(r.width() / src.width, r.height() / src.height)
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(r.left + (r.width() - src.width * scale) / 2f, r.top + (r.height() - src.height * scale) / 2f)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(m)
        val pp = Paint(Paint.ANTI_ALIAS_FLAG)
        pp.shader = shader
        cv.drawRoundRect(r, s * 0.05f, s * 0.05f, pp)
        return out
    }
}
