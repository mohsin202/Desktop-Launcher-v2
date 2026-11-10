package com.android.desktoplauncher

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import android.view.View
import android.view.Window
import java.io.File

/** Space taken by the system bars / display cutout. (android.graphics.Insets only exists from Android 10.) */
class Ins(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object { val NONE = Ins(0, 0, 0, 0) }
}

/* Bridges that let the launcher run from Android 7 (API 24) up to the newest version: the modern API where it exists, an older equivalent otherwise. */
object Compat {
    private val SDK get() = Build.VERSION.SDK_INT

    /** All files access: the special permission from Android 11, the ordinary storage permission before that. */
    fun hasAllFiles(c: Context): Boolean =
        if (SDK >= 30) Environment.isExternalStorageManager()
        else c.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED

    // ---- adaptive icons (Android 8+). Before that the icon is drawn through the squircle outline by the launcher itself. ----
    fun adaptive(bg: Drawable, fg: Drawable): Drawable =
        if (SDK >= 26) AdaptiveIconDrawable(bg, fg) else ShapedIcon(bg, fg, 2)

    fun isAdaptive(d: Drawable): Boolean = SDK >= 26 && d is AdaptiveIconDrawable

    fun layers(d: Drawable): Pair<Drawable, Drawable>? = if (SDK >= 26 && d is AdaptiveIconDrawable) Pair(d.background, d.foreground) else null

    fun monochrome(d: Drawable): Drawable? = if (SDK >= 33 && d is AdaptiveIconDrawable) d.monochrome else null

    // ---- window edges ----
    /** Content under the status and navigation bars (the app places itself with the insets it is given). */
    fun edgeToEdge(w: Window) {
        if (SDK >= 30) w.setDecorFitsSystemWindows(false)
        else @Suppress("DEPRECATION") run {
            w.decorView.systemUiVisibility = w.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    fun lightStatusBar(w: Window, light: Boolean) {
        if (SDK >= 30) w.insetsController?.setSystemBarsAppearance(
            if (light) android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        else if (SDK >= 23) @Suppress("DEPRECATION") run {
            val f = w.decorView.systemUiVisibility
            w.decorView.systemUiVisibility = if (light) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    /** Whether the on-screen keyboard is showing (WindowInsets.isVisible exists from Android 11; before that the bottom inset is compared). */
    fun imeVisible(v: View): Boolean = try {
        val ins = v.rootWindowInsets
        if (ins == null) false
        else if (SDK >= 30) ins.isVisible(android.view.WindowInsets.Type.ime())
        else @Suppress("DEPRECATION") (ins.systemWindowInsetBottom > v.resources.displayMetrics.heightPixels * 0.15f)
    } catch (_: Exception) { false }

    fun statusBarHeight(c: Context): Int {
        val id = c.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) c.resources.getDimensionPixelSize(id) else 0
    }

    // ---- thumbnails: ThumbnailUtils.createImageThumbnail(File, Size) / createVideoThumbnail(File, Size) exist from Android 10 ----
    fun thumbImage(f: File, px: Int): Bitmap? {
        if (SDK >= 29) return android.media.ThumbnailUtils.createImageThumbnail(f, android.util.Size(px, px), null)
        val o = android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var s = 1; while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        val d = android.graphics.BitmapFactory.Options(); d.inSampleSize = s
        val b = android.graphics.BitmapFactory.decodeFile(f.path, d) ?: return null
        val deg = try { when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f } } catch (_: Exception) { 0f }
        val m = android.graphics.Matrix(); if (deg != 0f) m.postRotate(deg)
        val k = px.toFloat() / maxOf(b.width, b.height); if (k < 1f) m.postScale(k, k)
        return if (m.isIdentity) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    fun thumbVideo(f: File, px: Int): Bitmap? {
        if (SDK >= 29) return android.media.ThumbnailUtils.createVideoThumbnail(f, android.util.Size(px, px), null)
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val b = r.getFrameAtTime(-1) ?: return null
            val k = px.toFloat() / maxOf(b.width, b.height)
            if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true) else b
        } catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
    }
}
