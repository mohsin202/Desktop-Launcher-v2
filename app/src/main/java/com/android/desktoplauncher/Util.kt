package com.android.desktoplauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.Toast
import java.io.File
import java.util.concurrent.ConcurrentHashMap

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()

/** Right mouse button (or secondary click) on a view. Consumes the whole gesture. */
fun View.setSecondaryClick(cb: (Int, Int) -> Unit) {
    var active = false
    setOnTouchListener { _, ev ->
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            active = (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
            if (active) cb(ev.rawX.toInt(), ev.rawY.toInt())
        }
        val r = active
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) active = false
        r
    }
}

/** Smallest width >= 600dp = tablet/desktop layout (taskbar), below = phone layout (dock). Can be forced in settings. */
object Modes {
    fun tablet(c: Context): Boolean = when (Prefs.layoutMode(c)) {
        1 -> false
        2 -> true
        else -> c.resources.configuration.smallestScreenWidthDp >= 600
    }
}

object Colors {
    fun night(c: Context) =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun sys(c: Context, id: Int, fb: Int): Int =
        if (Build.VERSION.SDK_INT >= 31) c.getColor(id) else fb

    fun glass(c: Context, col: Int): Int =
        if (Blur.on(c)) (col and 0x00FFFFFF) or 0xB8000000.toInt() else col

    fun surfaceSolid(c: Context) =
        if (night(c)) sys(c, android.R.color.system_neutral1_800, 0xFF2B2D31.toInt())
        else sys(c, android.R.color.system_neutral1_50, 0xFFF1F3F8.toInt())

    fun surface(c: Context) = glass(c, surfaceSolid(c))

    fun surfaceHigh(c: Context) =
        if (night(c)) sys(c, android.R.color.system_neutral1_700, 0xFF3A3D42.toInt())
        else sys(c, android.R.color.system_neutral1_200, 0xFFDDE1E8.toInt())

    fun text(c: Context) =
        if (night(c)) sys(c, android.R.color.system_neutral1_100, 0xFFE3E3E8.toInt())
        else sys(c, android.R.color.system_neutral1_900, 0xFF1B1B1F.toInt())

    fun taskbar(c: Context) = glass(c,
        if (night(c)) sys(c, android.R.color.system_accent2_800, 0xFF2E3A47.toInt())
        else sys(c, android.R.color.system_accent1_100, 0xFFD3E3FD.toInt()))

    fun accent(c: Context) =
        if (night(c)) sys(c, android.R.color.system_accent1_200, 0xFF9FC2FF.toInt())
        else sys(c, android.R.color.system_accent1_600, 0xFF3F6FD4.toInt())

    fun iconBg(c: Context) =
        if (night(c)) sys(c, android.R.color.system_accent1_800, 0xFF1F3A66.toInt())
        else sys(c, android.R.color.system_accent1_100, 0xFFD3E3FD.toInt())

    fun iconFg(c: Context) =
        if (night(c)) sys(c, android.R.color.system_accent1_100, 0xFFD3E3FD.toInt())
        else sys(c, android.R.color.system_accent1_800, 0xFF1F3A66.toInt())

    fun glassOn(c: Context) = Prefs.bool(c, Prefs.K_GLASS, false)

    /** A panel background: the plain colour, or (glass blur on) frosted glass like the special clock's Glass style. */
    fun panel(c: Context, base: Int, r: Float, radii: FloatArray? = null): Drawable {
        fun shape(f: GradientDrawable.() -> Unit) = GradientDrawable().apply {
            if (radii != null) cornerRadii = radii else cornerRadius = r
            f()
        }
        if (!glassOn(c)) return shape { setColor(base) }
        val night = night(c)
        val tint = shape { setColor((base and 0x00FFFFFF) or (if (night) 0x70000000 else 0x58000000)) }
        val sheen = shape {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(if (night) 0x4DFFFFFF else 0x99FFFFFF.toInt(), if (night) 0x12FFFFFF else 0x2EFFFFFF)
            setStroke(c.dp(1).coerceAtLeast(1), 0xB3FFFFFF.toInt())
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(tint, sheen))
    }

    fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }
}

object Blur {
    fun on(c: Context) = Build.VERSION.SDK_INT >= 31 && Prefs.bool(c, Prefs.K_BLUR, false)

    /** Keep overlays translucent without BLUR_BEHIND, which can blur the entire screen. */
    fun apply(lp: android.view.WindowManager.LayoutParams, c: Context, radiusDp: Int = 28) {
        if (Build.VERSION.SDK_INT < 31) return
        lp.flags = lp.flags and android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        lp.blurBehindRadius = 0
    }

    /** Real blur of what is behind an overlay: the launcher's own views are blurred while a drawer / menu is open. */
    fun behind(c: Context, v: View?, on: Boolean) {
        if (v == null || Build.VERSION.SDK_INT < 31) return
        try {
            if (on && (Prefs.bool(c, Prefs.K_BLUR, false) || Prefs.bool(c, Prefs.K_GLASS, false))) {
                val r = c.dp(14).toFloat()
                v.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP))
            } else v.setRenderEffect(null)
        } catch (_: Throwable) { }
    }

    /** Keep popups touch-safe; bounded glass blur needs a Window-backed surface. */
    fun popup(c: Context, pw: android.widget.PopupWindow) {
        if (Build.VERSION.SDK_INT < 31 || !on(c)) return
        pw.contentView.post {
            try {
                val root = pw.contentView.rootView
                val lp = root.layoutParams as? android.view.WindowManager.LayoutParams ?: return@post
                lp.flags = lp.flags and android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                lp.blurBehindRadius = 0
                c.getSystemService(android.view.WindowManager::class.java).updateViewLayout(root, lp)
            } catch (_: Exception) { }
        }
    }
}

object Icons {
    private val cache = ConcurrentHashMap<String, Drawable.ConstantState>()

    /** Home screen and drawer both use the two settings: "themed icons" and "themed icon on unsupported icons". */
    fun app(c: Context, comp: ComponentName, @Suppress("UNUSED_PARAMETER") themed: Boolean = false): Drawable {
        val on = Prefs.bool(c, Prefs.K_THEMED, false)
        val all = on && Prefs.bool(c, Prefs.K_THEMED_ALL, false)
        val key = comp.flattenToString() + (if (on) (if (all) "#ta" else "#t") else "") + (if (Colors.night(c)) "n" else "d")
        cache[key]?.let { return it.newDrawable().mutate() }
        val d: Drawable = try {
            val base = c.packageManager.getActivityIcon(comp)
            if (!on) base else {
                var mono: Drawable? = null
                if (Build.VERSION.SDK_INT >= 33 && base is AdaptiveIconDrawable) mono = base.monochrome
                if (mono != null) {
                    val fg = mono.constantState?.newDrawable()?.mutate() ?: mono
                    fg.setTint(Colors.iconFg(c))
                    AdaptiveIconDrawable(ColorDrawable(Colors.iconBg(c)), fg)
                } else if (all) makeMono(c, base) else base
            }
        } catch (e: Exception) {
            c.packageManager.defaultActivityIcon
        }
        d.constantState?.let { cache[key] = it }
        return d
    }

    /** Builds a one-colour icon for an app that has no monochrome layer. */
    private fun makeMono(c: Context, base: Drawable): Drawable {
        val n = 216
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val src: Drawable = if (base is AdaptiveIconDrawable) (base.foreground ?: base) else base
        if (base is AdaptiveIconDrawable) src.setBounds(0, 0, n, n)
        else { val m = (n * 0.19f).toInt(); src.setBounds(m, m, n - m, n - m) }
        src.draw(cv)
        val px = IntArray(n * n)
        bmp.getPixels(px, 0, n, 0, 0, n, n)
        var cover = 0L
        for (p in px) if ((p ushr 24) > 200) cover++
        val opaque = cover > n.toLong() * n * 0.55          // a full square picture: use its dark parts as the shape
        val col = Colors.iconFg(c) and 0x00FFFFFF
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            val lum = (0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)) / 255f
            val out = if (opaque) (a * (1f - lum).coerceIn(0f, 1f) * 1.3f).toInt() else a
            px[i] = (out.coerceIn(0, 255) shl 24) or col
        }
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        val fg = android.graphics.drawable.BitmapDrawable(c.resources, bmp)
        return AdaptiveIconDrawable(ColorDrawable(Colors.iconBg(c)), fg)
    }

    fun file(f: File): Drawable =
        if (f.isDirectory) FolderIcon() else {
            val ext = f.extension.lowercase()
            val col = when (ext) {
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> 0xFF43A047
                "mp4", "mkv", "avi", "mov", "webm", "3gp" -> 0xFFE53935
                "mp3", "wav", "flac", "ogg", "m4a", "aac" -> 0xFF8E24AA
                "pdf" -> 0xFFD32F2F
                "apk" -> 0xFF2E7D32
                "zip", "rar", "7z", "tar", "gz" -> 0xFFF57C00
                else -> 0xFF1E88E5
            }.toInt()
            FileIcon(ext, col)
        }
}

class FolderIcon : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
        val l = b.left.toFloat(); val t = b.top.toFloat()
        p.color = 0xFFE0A526.toInt()
        c.drawRoundRect(RectF(l + w * .06f, t + h * .16f, l + w * .46f, t + h * .40f), w * .06f, w * .06f, p)
        p.color = 0xFFFFC83D.toInt()
        c.drawRoundRect(RectF(l + w * .06f, t + h * .26f, l + w * .94f, t + h * .84f), w * .08f, w * .08f, p)
    }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class FileIcon(private val ext: String, private val color: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    override fun draw(c: Canvas) {
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
        val l = b.left.toFloat(); val t = b.top.toFloat()
        p.color = 0xFFF4F4F4.toInt()
        c.drawRoundRect(RectF(l + w * .18f, t + h * .08f, l + w * .82f, t + h * .92f), w * .08f, w * .08f, p)
        p.color = color
        c.drawRoundRect(RectF(l + w * .18f, t + h * .52f, l + w * .82f, t + h * .76f), w * .04f, w * .04f, p)
        tp.color = Color.WHITE
        tp.textSize = h * .16f
        c.drawText(ext.take(4).uppercase(), l + w / 2, t + h * .70f, tp)
    }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** ImageView that can draw a notification dot. */
class BadgedIcon(c: Context) : ImageView(c) {
    var dot = false
        set(v) { field = v; invalidate() }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5A4D.toInt() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dot) {
            val r = width * 0.13f
            canvas.drawCircle(width - r * 1.5f, r * 1.5f, r + 1.5f, white)
            canvas.drawCircle(width - r * 1.5f, r * 1.5f, r, red)
        }
    }
}

object Files {
    const val AUTH = "com.android.desktoplauncher.files"

    fun root(c: Context): File {
        val r = if (Environment.isExternalStorageManager()) File(Environment.getExternalStorageDirectory(), "Desktop")
        else File(c.getExternalFilesDir(null) ?: c.filesDir, "Desktop")
        r.mkdirs()
        return r
    }

    fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    fun uri(f: File): Uri = Uri.Builder().scheme("content").authority(AUTH).path(f.absolutePath).build()

    fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 2
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    fun open(c: Context, f: File) {
        if (!f.exists()) { c.toast("File not found"); return }
        if (f.isDirectory) { openFolder(c, f); return }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(f), mime(f))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { c.startActivity(i) } catch (e: Exception) { c.toast("No app can open this file") }
    }

    fun openFolder(c: Context, f: File) {
        val ext = Environment.getExternalStorageDirectory().absolutePath
        if (!f.absolutePath.startsWith(ext)) { c.toast("Folder is private to the launcher: ${f.absolutePath}"); return }
        val rel = f.absolutePath.removePrefix(ext).trimStart('/')
        val doc = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
        val tries = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(Intent.ACTION_VIEW).setDataAndType(doc, "vnd.android.document/directory"),
            Intent(Intent.ACTION_VIEW).setData(doc)
        )
        for (i in tries) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        c.toast("No file manager found")
    }

    fun copyRec(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyRec(it, File(dst, it.name)) }
        } else src.copyTo(dst, overwrite = false)
    }

    fun move(src: File, dst: File) {
        if (!src.renameTo(dst)) { copyRec(src, dst); src.deleteRecursively() }
    }
}

object Launch {
    fun start(c: Context, i: Intent) {
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { c.toast("Cannot open") }
    }

    fun app(c: Context, comp: ComponentName) {
        start(c, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(comp)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    fun info(c: Context, pkg: String) =
        start(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    fun uninstall(c: Context, pkg: String) =
        start(c, Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))

    fun shareApp(c: Context, pkg: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://play.google.com/store/apps/details?id=$pkg")
        start(c, Intent.createChooser(i, "Share"))
    }

    fun shareFile(c: Context, f: File) {
        if (f.isDirectory) { c.toast("Folders cannot be shared"); return }
        val i = Intent(Intent.ACTION_SEND).setType(Files.mime(f)).putExtra(Intent.EXTRA_STREAM, Files.uri(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(c, Intent.createChooser(i, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Opens the "Wallpaper & style" app. */
    fun wallpapers(c: Context) {
        val tries = listOf(
            Intent(Intent.ACTION_SET_WALLPAPER),
            Intent().setClassName("com.google.android.apps.wallpaper", "com.android.customization.picker.CustomizationPickerActivity"),
            Intent().setClassName("com.android.wallpaper", "com.android.customization.picker.CustomizationPickerActivity"),
            Intent().setClassName("com.android.wallpaper", "com.android.wallpaper.picker.CategoryPickerActivity"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$WallpaperSettingsActivity")
        )
        for (i in tries) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        c.toast("Wallpaper app not found")
    }
}


/** Space taken by system bars and the display cutout (punch hole), so content never goes under them. */
object Insets {
    fun of(ins: android.view.WindowInsets): android.graphics.Insets =
        ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())

    fun safe(c: Context): android.graphics.Insets = try {
        c.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.windowInsets
            .getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
    } catch (_: Exception) { android.graphics.Insets.NONE }
}
