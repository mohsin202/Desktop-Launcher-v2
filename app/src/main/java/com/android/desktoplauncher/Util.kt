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
        val shape = Prefs.getInt(c, Prefs.K_ICONSHAPE, 0)
        val key = comp.flattenToString() + (if (on) (if (all) "#ta" else "#t") else "") + (if (Colors.night(c)) "n" else "d") + (if (shape != 0) "#s$shape" else "")
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
        val out = if (shape != 0) IconShapes.wrap(d, shape) else d
        out.constantState?.let { cache[key] = it }
        return out
    }

    /**
     * Themed version of an icon that has no monochrome layer: the real picture stays recognisable, only its colours
     * are replaced by the Material You pair; the plate always follows the theme (light plate in light mode, dark plate in dark mode).
     */
    private fun makeMono(c: Context, base: Drawable): Drawable {
        val n = 216
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val adaptive = base is AdaptiveIconDrawable
        if (adaptive) base.setBounds(0, 0, n, n)
        else { val m = (n * 0.19f).toInt(); base.setBounds(m, m, n - m, n - m) }
        base.draw(cv)
        val px = IntArray(n * n)
        bmp.getPixels(px, 0, n, 0, 0, n, n)
        val lum = FloatArray(n * n)
        for (i in px.indices) {
            val p = px[i]
            lum[i] = (0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255))
        }
        val plate = Colors.iconBg(c); val ink = Colors.iconFg(c)
        fun mix(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt()
        fun median(vals: List<Float>): Float = if (vals.isEmpty()) 128f else vals.sorted()[vals.size / 2]
        // The plate always follows the theme (light plate in light mode, dark plate in dark mode), whatever the original
        // background was. What differs from the icon's own background becomes the ink, so the picture stays recognisable.
        var minX = n; var minY = n; var maxX = -1; var maxY = -1
        for (y in 0 until n) for (x in 0 until n) if ((px[y * n + x] ushr 24) > 128) {
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val border = ArrayList<Float>()
        var ringTotal = 0
        if (maxX > minX && maxY > minY) {
            val bw = ((maxX - minX) * 0.1f).toInt().coerceAtLeast(2); val bh = ((maxY - minY) * 0.1f).toInt().coerceAtLeast(2)
            for (y in minY..maxY) for (x in minX..maxX) {
                if (x - minX < bw || maxX - x < bw || y - minY < bh || maxY - y < bh) {
                    ringTotal++
                    if ((px[y * n + x] ushr 24) > 200) border.add(lum[y * n + x])
                }
            }
        }
        val solidBg = ringTotal > 0 && border.size > ringTotal * 0.6f
        val bg = median(border)
        val tt = FloatArray(n * n)
        if (solidBg) {
            val d = ArrayList<Float>()
            for (i in px.indices) if ((px[i] ushr 24) > 128) d.add(Math.abs(lum[i] - bg))
            d.sort()
            val dmax = maxOf(30f, if (d.isEmpty()) 30f else d[(d.size * 0.97f).toInt().coerceIn(0, d.size - 1)])
            for (i in px.indices) tt[i] = (Math.abs(lum[i] - bg) / dmax).coerceIn(0f, 1f)
        } else {
            // a shape without its own background (transparent around it): solid ink with a little shading
            val inside = ArrayList<Float>()
            for (i in px.indices) if ((px[i] ushr 24) > 128) inside.add(lum[i])
            val m = median(inside)
            for (i in px.indices) tt[i] = (0.8f + 0.2f * (Math.abs(lum[i] - m) / 128f)).coerceIn(0f, 1f)
        }
        for (i in px.indices) {
            val a = px[i] ushr 24
            if (a == 0) { px[i] = 0; continue }
            val t = tt[i]
            val r = mix((plate shr 16) and 255, (ink shr 16) and 255, t)
            val g = mix((plate shr 8) and 255, (ink shr 8) and 255, t)
            val bl = mix(plate and 255, ink and 255, t)
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
        }
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        val out = android.graphics.drawable.BitmapDrawable(c.resources, bmp)
        return if (adaptive) out else AdaptiveIconDrawable(ColorDrawable(plate), out)
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
        try { c.startActivity(i, Launch.options(c)) } catch (e: Exception) { c.toast("No app can open this file") }
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
    /**
     * Launch options for "Open apps in freeform windows": asks for the freeform windowing mode (hidden API, may be refused by
     * the ROM) and a window rectangle, like taskbar apps do. Returns null when the setting is off and no bounds are given.
     */
    const val ADB_GRANT = "adb shell pm grant com.android.desktoplauncher android.permission.WRITE_SECURE_SETTINGS"

    fun hasSecure(c: Context) = c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Switches on the system's freeform window support (Settings.Global enable_freeform_support / force_resizable_activities /
     * freeform_window_management), like the Taskbar app. Needs the WRITE_SECURE_SETTINGS permission given once with adb.
     */
    fun ensureFreeform(c: Context): Boolean {
        if (!hasSecure(c)) return false
        return try {
            val r = c.contentResolver
            Settings.Global.putInt(r, "enable_freeform_support", 1)
            Settings.Global.putInt(r, "force_resizable_activities", 1)
            Settings.Global.putInt(r, "freeform_window_management", 1)
            true
        } catch (_: Throwable) { false }
    }

    private var hiddenOpen = false
    private var hiddenErr: String? = null
    /** Android hides some framework calls (here: asking for the freeform windowing mode). Lifts that restriction for this app. */
    private fun unlockHidden() {
        if (hiddenOpen) return
        hiddenOpen = true
        try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclared = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, java.lang.reflect.Array.newInstance(Class::class.java, 0).javaClass)
            val vm = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclared.invoke(vm, "getRuntime", arrayOfNulls<Class<*>>(0)) as java.lang.reflect.Method
            val setEx = getDeclared.invoke(vm, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)) as java.lang.reflect.Method
            setEx.invoke(getRuntime.invoke(null), arrayOf("L"))
        } catch (e: Throwable) { hiddenErr = e.toString() }
    }

    /** Plain-text report of everything the freeform launch depends on (shown in Settings > Windows). */
    fun diagnose(c: Context): String {
        val sb = StringBuilder()
        fun line(k: String, v: Any?) { sb.append(k).append(": ").append(v).append('\n') }
        line("Android SDK", Build.VERSION.SDK_INT)
        line("Device", Build.MANUFACTURER + " " + Build.MODEL)
        val pm = c.packageManager
        line("Feature freeform windows", pm.hasSystemFeature("android.software.freeform_window_management"))
        line("Feature picture-in-picture", pm.hasSystemFeature("android.software.picture_in_picture"))
        for (k in listOf("enable_freeform_support", "force_resizable_activities", "freeform_window_management", "enable_non_resizable_multi_window", "force_desktop_mode_on_external_displays"))
            line("Global $k", try { Settings.Global.getString(c.contentResolver, k) } catch (_: Throwable) { "?" })
        line("WRITE_SECURE_SETTINGS", hasSecure(c))
        line("Setting: open apps in freeform", Prefs.bool(c, Prefs.K_FREEFORM, false))
        line("Setting: helper window", Prefs.bool(c, Prefs.K_FFHACK, false))
        line("Helper window running", FreeformHackActivity.running())
        hiddenOpen = false; hiddenErr = null
        unlockHidden()
        line("Hidden API unlock", if (hiddenErr == null) "no error" else hiddenErr)
        try {
            val o = android.app.ActivityOptions.makeBasic()
            val m = o.javaClass.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
            m.invoke(o, 5)
            line("setLaunchWindowingMode(5)", "worked")
        } catch (e: Throwable) { line("setLaunchWindowingMode(5)", e.toString()) }
        try {
            val o = android.app.ActivityOptions.makeBasic()
            o.setLaunchBounds(sizeRect(c, 2))
            line("setLaunchBounds", "worked, " + sizeRect(c, 2))
        } catch (e: Throwable) { line("setLaunchBounds", e.toString()) }
        return sb.toString()
    }

    /** Opens the invisible freeform helper window once (Taskbar's "freeform hack"), if the setting is on. */
    private fun startHack(c: Context) {
        if (!Prefs.bool(c, Prefs.K_FFHACK, false) || FreeformHackActivity.running()) return
        try {
            c.startActivity(Intent(c, FreeformHackActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                options(c, android.graphics.Rect(0, 0, 1, 1)))
        } catch (_: Throwable) { }
    }

    /** Window sizes of the "Window size" choice: 0 phone, 1 tablet, 2 desktop (in dp, clamped to the screen). */
    fun sizeRect(c: Context, size: Int): android.graphics.Rect {
        val dm = c.resources.displayMetrics
        val (wd, hd) = when (size) { 0 -> 400 to 720; 1 -> 820 to 620; else -> 1180 to 760 }
        val w = minOf((wd * dm.density).toInt(), (dm.widthPixels * 0.94f).toInt())
        val h = minOf((hd * dm.density).toInt(), (dm.heightPixels * 0.9f).toInt())
        val l = (dm.widthPixels - w) / 2; val t = (dm.heightPixels - h) / 2
        return android.graphics.Rect(l, t, l + w, t + h)
    }

    /**
     * Launch options for freeform windows: asks for the freeform windowing mode (hidden API, may be refused by the ROM)
     * and a window rectangle, like taskbar apps do. [size] (0 phone, 1 tablet, 2 desktop) forces a window of that size even
     * when the setting is off; otherwise the saved window size is used. Returns null when nothing is asked for.
     */
    fun options(c: Context, bounds: android.graphics.Rect? = null, size: Int? = null): android.os.Bundle? {
        val on = Prefs.bool(c, Prefs.K_FREEFORM, false) || size != null
        if (!on && bounds == null) return null
        return try {
            val o = android.app.ActivityOptions.makeBasic()
            if (on) unlockHidden()
            if (on) try { o.javaClass.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(o, 5) } catch (_: Throwable) {}
            o.setLaunchBounds(bounds ?: sizeRect(c, size ?: Prefs.getInt(c, Prefs.K_WINSIZE, 2)))
            o.toBundle()
        } catch (_: Throwable) { null }
    }

    fun start(c: Context, i: Intent, size: Int? = null) {
        if (Prefs.bool(c, Prefs.K_FREEFORM, false) || size != null) { ensureFreeform(c); startHack(c) }
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options(c, null, size)) } catch (e: Exception) { c.toast("Cannot open") }
    }

    fun app(c: Context, comp: ComponentName, size: Int? = null) {
        start(c, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(comp)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), size)
    }

    /** "Window size >" sub menu (Phone / Tablet / Desktop) for the menu of an app icon. */
    fun sizeMenu(c: Context, comp: ComponentName): MenuItem = MenuItem("Window size", children = listOf(
        MenuItem("Phone") { app(c, comp, 0) },
        MenuItem("Tablet") { app(c, comp, 1) },
        MenuItem("Desktop") { app(c, comp, 2) }
    ))

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


/** Icon shapes the user can pick: the adaptive icon's two layers are drawn through the chosen outline. */
object IconShapes {
    const val SYSTEM = 0
    val names = listOf("System", "Circle", "Squircle", "Rounded square", "Square", "Teardrop", "Diamond", "Hexagon", "Flower", "Arch")

    private fun roundedPolygon(pts: List<android.graphics.PointF>, radius: Float, out: android.graphics.Path) {
        val n = pts.size
        for (i in 0 until n) {
            val p0 = pts[(i + n - 1) % n]; val p1 = pts[i]; val p2 = pts[(i + 1) % n]
            fun toward(a: android.graphics.PointF, b: android.graphics.PointF): android.graphics.PointF {
                val dx = b.x - a.x; val dy = b.y - a.y
                val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val t = minOf(radius, len / 2f) / len
                return android.graphics.PointF(a.x + dx * t, a.y + dy * t)
            }
            val s = toward(p1, p0); val e = toward(p1, p2)
            if (i == 0) out.moveTo(s.x, s.y) else out.lineTo(s.x, s.y)
            out.quadTo(p1.x, p1.y, e.x, e.y)
        }
        out.close()
    }

    /** Draws shape [id] filling [r] into [out]. 0 (system) is drawn as a squircle where a preview is needed. */
    fun build(id: Int, r: android.graphics.RectF, out: android.graphics.Path) {
        out.reset()
        val w = r.width(); val h = r.height(); val cx = r.centerX(); val cy = r.centerY()
        when (id) {
            1 -> out.addOval(r, android.graphics.Path.Direction.CW)
            4 -> out.addRoundRect(r, w * 0.08f, h * 0.08f, android.graphics.Path.Direction.CW)
            3 -> out.addRoundRect(r, w * 0.27f, h * 0.27f, android.graphics.Path.Direction.CW)
            5 -> { val q = w / 2f; out.addRoundRect(r, floatArrayOf(q, q, 0f, 0f, q, q, q, q), android.graphics.Path.Direction.CW) }
            6 -> roundedPolygon(listOf(android.graphics.PointF(cx, r.top), android.graphics.PointF(r.right, cy),
                android.graphics.PointF(cx, r.bottom), android.graphics.PointF(r.left, cy)), w * 0.22f, out)
            7 -> roundedPolygon((0 until 6).map { i ->
                val a = Math.toRadians(60.0 * i - 90.0)
                android.graphics.PointF(cx + (w / 2f) * Math.cos(a).toFloat(), cy + (h / 2f) * Math.sin(a).toFloat())
            }, w * 0.12f, out)
            8 -> {
                for (i in 0..180) {
                    val a = Math.PI * 2 * i / 180
                    val k = 0.88f + 0.12f * Math.cos(8 * a).toFloat()
                    val x = cx + (w / 2f) * k * Math.cos(a).toFloat(); val y = cy + (h / 2f) * k * Math.sin(a).toFloat()
                    if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
                }
                out.close()
            }
            9 -> { val q = w / 2f; val s = w * 0.14f; out.addRoundRect(r, floatArrayOf(q, q, q, q, s, s, s, s), android.graphics.Path.Direction.CW) }
            else -> {   // squircle (superellipse)
                for (i in 0..120) {
                    val a = Math.PI * 2 * i / 120
                    val cs = Math.cos(a); val sn = Math.sin(a)
                    val x = cx + (w / 2f) * Math.signum(cs).toFloat() * Math.pow(Math.abs(cs), 0.5).toFloat()
                    val y = cy + (h / 2f) * Math.signum(sn).toFloat() * Math.pow(Math.abs(sn), 0.5).toFloat()
                    if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
                }
                out.close()
            }
        }
    }

    fun wrap(d: android.graphics.drawable.Drawable, id: Int): android.graphics.drawable.Drawable {
        if (d is android.graphics.drawable.AdaptiveIconDrawable) return ShapedIcon(d.background, d.foreground, id)
        if (d is android.graphics.drawable.BitmapDrawable && d.intrinsicWidth == 216) return d   // a ready-made themed picture
        // an old-style icon: a white plate in the chosen shape with the picture on it
        val inset = android.graphics.drawable.InsetDrawable(d, 0.29f)
        return ShapedIcon(android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt()), inset, id)
    }
}

class ShapedIcon(private val bg: android.graphics.drawable.Drawable, private val fg: android.graphics.drawable.Drawable, private val id: Int) :
    android.graphics.drawable.Drawable() {
    private val path = android.graphics.Path()

    override fun onBoundsChange(b: android.graphics.Rect) {
        // adaptive layers are 1.5 times the visible area
        val dx = (b.width() * 0.25f).toInt(); val dy = (b.height() * 0.25f).toInt()
        val r = android.graphics.Rect(b.left - dx, b.top - dy, b.right + dx, b.bottom + dy)
        bg.bounds = r; fg.bounds = r
        IconShapes.build(id, android.graphics.RectF(b), path)
    }

    override fun draw(c: android.graphics.Canvas) {
        val s = c.save()
        c.clipPath(path)
        bg.draw(c); fg.draw(c)
        c.restoreToCount(s)
    }

    override fun setAlpha(a: Int) { bg.alpha = a; fg.alpha = a }
    override fun setColorFilter(f: android.graphics.ColorFilter?) { bg.colorFilter = f; fg.colorFilter = f }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = (108 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    override fun getIntrinsicHeight() = intrinsicWidth

    override fun getConstantState(): ConstantState? {
        val b = bg.constantState ?: return null
        val f = fg.constantState ?: return null
        val shape = id
        return object : ConstantState() {
            override fun newDrawable(): android.graphics.drawable.Drawable = ShapedIcon(b.newDrawable(), f.newDrawable(), shape)
            override fun getChangingConfigurations() = 0
        }
    }
}

/** Name to show for a file: a shortcut (.desktoplink) is shown without its ending. */
fun File.shown(): String = if (extension == "desktoplink") name.removeSuffix(".desktoplink") else name
