package com.android.desktopfiles

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Colours: Material You (system) colours, light or dark as chosen in the settings. */
class Pal(val dark: Boolean, val bg: Int, val bar: Int, val barEnd: Int, val card: Int, val line: Int, val text: Int, val sub: Int,
          val accent: Int, val accentSoft: Int, val hover: Int, val sel: Int, val warn: Int)

object Ui {
    fun isDark(c: Context): Boolean = when (Store.str(c, "theme", "system")) {
        "dark" -> true
        "light" -> false
        else -> (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun pal(c: Context): Pal {
        val dark = isDark(c)
        fun col(id: Int) = try { c.getColor(id) } catch (_: Exception) { 0xFF888888.toInt() }
        return if (dark) Pal(true,
            bg = col(android.R.color.system_neutral1_900), bar = col(android.R.color.system_accent1_900), barEnd = col(android.R.color.system_accent2_900),
            card = col(android.R.color.system_neutral1_800), line = 0x33FFFFFF, text = col(android.R.color.system_neutral1_50),
            sub = col(android.R.color.system_neutral2_300), accent = col(android.R.color.system_accent1_200),
            accentSoft = col(android.R.color.system_accent1_700), hover = 0x22FFFFFF, sel = 0x44A0C4FF, warn = 0xFFFFB4AB.toInt())
        else Pal(false,
            bg = col(android.R.color.system_neutral1_50), bar = col(android.R.color.system_accent1_100), barEnd = col(android.R.color.system_accent2_100),
            card = col(android.R.color.system_neutral1_10), line = 0x1F000000, text = col(android.R.color.system_neutral1_900),
            sub = col(android.R.color.system_neutral2_600), accent = col(android.R.color.system_accent1_600),
            accentSoft = col(android.R.color.system_accent1_100), hover = 0x14000000, sel = 0x33478CFF, warn = 0xFFBA1A1A.toInt())
    }

    fun rounded(color: Int, r: Float, stroke: Int = 0, sw: Int = 0): GradientDrawable = GradientDrawable().also {
        it.shape = GradientDrawable.RECTANGLE; it.cornerRadius = r; it.setColor(color); if (sw > 0) it.setStroke(sw, stroke)
    }

    fun ripple(c: Context, p: Pal, base: Drawable?, r: Float): Drawable =
        RippleDrawable(ColorStateList.valueOf(p.hover), base, rounded(Color.WHITE, r))

    fun text(c: Context, s: String, size: Float, color: Int, bold: Boolean = false, tr: Boolean = true): TextView = TextView(c).also {
        it.text = if (tr) T.t(s) else s; it.textSize = size; it.setTextColor(color); if (bold) it.typeface = Typeface.DEFAULT_BOLD
        it.includeFontPadding = false
        // real names (files, apps, drives) keep to the screen's start edge: right in right-to-left, left otherwise
        if (!tr) it.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    /** A flat round / rounded button made of a text glyph. */
    fun glyphButton(c: Context, p: Pal, glyph: String, size: Float = 18f, w: Int = 40, h: Int = 40, click: (View) -> Unit): TextView {
        val t = text(c, glyph, size, p.text)
        t.gravity = Gravity.CENTER
        t.background = ripple(c, p, null, c.dpf(8f))
        t.isClickable = true
        t.layoutParams = LinearLayout.LayoutParams(c.dp(w), c.dp(h))
        t.setOnClickListener(click)
        return t
    }
}

/**
 * One menu line. [row] makes a line of icon buttons (Cut, Copy, Rename… like Windows 11); each of those items gives its [icon].
 * [hint] is the dim shortcut text at the far end of the line.
 */
class MenuItem(val label: String, val enabled: Boolean = true, val checked: Boolean? = null, val danger: Boolean = false,
               val sub: List<MenuItem>? = null, val row: List<MenuItem>? = null, val icon: String? = null, val hint: String? = null,
               val action: () -> Unit = {})

/** Rounded popup menu (also used as the right-click / long-press menu). A null entry is a thin separator. */
class Pop(private val c: Context) {
    private var win: PopupWindow? = null
    private var origin: View? = null    // the screen the first menu was opened on: sub-menus hang from it, not from the menu above them

    fun dismiss() { try { win?.dismiss() } catch (_: Exception) {}; win = null }

    private fun build(items: List<MenuItem?>, p: Pal, minW: Int): View {
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        box.background = Ui.rounded(p.card, c.dpf(10f), p.line, 1)
        box.setPaddingRelative(c.dp(4), c.dp(6), c.dp(4), c.dp(6))
        for (m in items) {
            if (m == null) {
                val sep = View(c); sep.setBackgroundColor(p.line)
                box.addView(sep, LinearLayout.LayoutParams(MATCH, 1).also { it.setMargins(c.dp(8), c.dp(4), c.dp(8), c.dp(4)) })
                continue
            }
            if (m.row != null) {
                val line = LinearLayout(c); line.orientation = LinearLayout.HORIZONTAL
                for (q in m.row) {
                    val cell = LinearLayout(c); cell.orientation = LinearLayout.VERTICAL; cell.gravity = Gravity.CENTER
                    val ic = android.widget.ImageView(c); ic.setImageDrawable(MenuIcon(q.icon ?: "", p.text, c.dp(22)))
                    val lb = Ui.text(c, q.label, 11f, p.sub); lb.gravity = Gravity.CENTER; lb.maxLines = 1
                    lb.ellipsize = android.text.TextUtils.TruncateAt.END
                    cell.addView(ic, LinearLayout.LayoutParams(c.dp(22), c.dp(22)).also { it.bottomMargin = c.dp(3) }); cell.addView(lb, LinearLayout.LayoutParams(MATCH, WRAP))
                    cell.alpha = if (q.enabled) 1f else 0.4f
                    cell.background = Ui.ripple(c, p, null, c.dpf(6f)); cell.isClickable = q.enabled
                    cell.setOnClickListener { if (q.enabled) { dismiss(); q.action() } }
                    line.addView(cell, LinearLayout.LayoutParams(0, c.dp(56), 1f).also { it.marginStart = c.dp(1); it.marginEnd = c.dp(1) })
                }
                box.addView(line, LinearLayout.LayoutParams(MATCH, WRAP))
                continue
            }
            val row = LinearLayout(c)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPaddingRelative(c.dp(12), 0, c.dp(12), 0)
            val chk = Ui.text(c, if (m.checked == true) "✓" else "", 14f, p.accent, true)
            chk.gravity = Gravity.CENTER
            if (m.checked != null) row.addView(chk, LinearLayout.LayoutParams(c.dp(22), WRAP))
            val t = Ui.text(c, m.label, 14f, if (m.danger) p.warn else p.text)
            row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (m.hint != null) row.addView(Ui.text(c, m.hint, 11f, p.sub).also { it.setPaddingRelative(c.dp(16), 0, 0, 0) }, LinearLayout.LayoutParams(WRAP, WRAP))
            if (m.sub != null) row.addView(Ui.text(c, if (T.rtl) "‹" else "›", 16f, p.sub), LinearLayout.LayoutParams(WRAP, WRAP))
            row.alpha = if (m.enabled) 1f else 0.4f
            row.background = Ui.ripple(c, p, null, c.dpf(6f))
            row.isClickable = m.enabled
            row.setOnClickListener {
                if (!m.enabled) return@setOnClickListener
                if (m.sub != null) {
                    val loc = IntArray(2); row.getLocationOnScreen(loc)
                    val old = win; win = null
                    // the sub-menu opens beside this one: to its right, or to its left in right-to-left
                    showAt(row.rootView, if (T.rtl) loc[0] else loc[0] + row.width, loc[1], m.sub, alignEnd = T.rtl)
                    try { old?.dismiss() } catch (_: Exception) {}
                } else { dismiss(); m.action() }
            }
            box.addView(row, LinearLayout.LayoutParams(MATCH, c.dp(36)))
        }
        val sv = ScrollView(c)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(box)
        val fl = FrameLayout(c)
        fl.layoutDirection = Dir.value()
        fl.setPaddingRelative(c.dp(6), c.dp(6), c.dp(6), c.dp(6))
        fl.addView(sv, FrameLayout.LayoutParams(maxOf(minW, c.dp(190)), WRAP))
        return fl
    }

    /**
     * Shows the menu with its top-left corner at the screen point (x, y). It stays inside the visible area (below the status bar and above
     * the taskbar) and scrolls when it is taller than that. [alignEnd] puts its right edge at x instead (sub-menus in right-to-left).
     */
    fun showAt(anchor: View, x: Int, y: Int, items: List<MenuItem?>, alignEnd: Boolean = false) {
        dismiss()
        try {
            val top = origin ?: anchor.rootView.also { origin = it }
            val p = Ui.pal(c)
            val v = build(items, p, c.dp(230))
            v.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val vis = android.graphics.Rect(); top.getWindowVisibleDisplayFrame(vis)
            if (vis.isEmpty) { val dm = c.resources.displayMetrics; vis.set(0, 0, dm.widthPixels, dm.heightPixels) }
            val m = c.dp(8)
            val maxH = maxOf(c.dp(120), vis.height() - 2 * m)
            val w = PopupWindow(v, v.measuredWidth, minOf(v.measuredHeight, maxH), true)
            w.isOutsideTouchable = true
            w.elevation = c.dpf(12f)
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            val wantX = if (alignEnd) x - v.measuredWidth else x
            val px = wantX.coerceIn(vis.left + m, maxOf(vis.left + m, vis.right - m - v.measuredWidth))
            val py = y.coerceIn(vis.top + m, maxOf(vis.top + m, vis.bottom - m - w.height))
            win = w
            w.showAtLocation(top, Gravity.NO_GRAVITY, px, py)
        } catch (e: Exception) {
            // never take the whole app down for a menu: say what went wrong instead
            win = null
            android.widget.Toast.makeText(c.applicationContext, "Menu: " + e.javaClass.simpleName + " " + (e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun showBelow(anchor: View, items: List<MenuItem?>) {
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        showAt(anchor, loc[0], loc[1] + anchor.height, items)
    }
}

/**
 * Text that sits at the start edge of its box: the right in right-to-left, the left otherwise. Without this a name written in English
 * counts as a left-to-right paragraph and lands on the left of an Urdu screen, away from its icon (no direction marks are added to the text).
 */
fun <V : android.widget.TextView> V.atStart(): V { textAlignment = View.TEXT_ALIGNMENT_VIEW_START; return this }

/** The layout direction of this app's own windows (dialogs, menus): right-to-left when Urdu is on. */
object Dir {
    fun value(): Int = if (T.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    fun apply(d: android.app.Dialog) { d.window?.decorView?.layoutDirection = value() }
}

/** Like show(), and the dialog follows the app's direction. */
fun android.app.AlertDialog.Builder.showDir(): android.app.AlertDialog { val d = create(); d.show(); Dir.apply(d); return d }

/** Wraps its children onto several lines. */
class Flow(c: Context, private val gap: Int) : ViewGroup(c) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec)
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            measureChild(ch, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += lineH + gap; lineH = 0 }
            x += ch.measuredWidth + gap
            lineH = maxOf(lineH, ch.measuredHeight)
        }
        setMeasuredDimension(maxW, y + lineH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += lineH + gap; lineH = 0 }
            // right-to-left: the first card sits at the right edge and the line fills towards the left
            if (layoutDirection == LAYOUT_DIRECTION_RTL) ch.layout(maxW - x - ch.measuredWidth, y, maxW - x, y + ch.measuredHeight)
            else ch.layout(x, y, x + ch.measuredWidth, y + ch.measuredHeight)
            x += ch.measuredWidth + gap
            lineH = maxOf(lineH, ch.measuredHeight)
        }
    }
}

/** Thin rounded usage bar (drive cards). */
class UsageBar(c: Context, private val pal: Pal, private val fraction: Float) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat(); val r = h / 2
        p.color = pal.line; canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), r, r, p)
        p.color = if (fraction > 0.9f) pal.warn else pal.accent
        val fw = maxOf(h, width * fraction.coerceIn(0f, 1f))
        // fills from the start edge: the right side in right-to-left
        if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.drawRoundRect(RectF(width - fw, 0f, width.toFloat(), h), r, r, p)
        else canvas.drawRoundRect(RectF(0f, 0f, fw, h), r, r, p)
    }
}

/** Little code-drawn icons (Windows Files look): folders, drives, sidebar places. */
class Glyph(private val kind: Kind, private val tint: Int = 0, private val ext: String = "", private val extColor: Int = 0xFF1E88E5.toInt()) : Drawable() {
    enum class Kind { FOLDER, FILE, HOME, DESKTOP, DOWNLOAD, DOCS, PICS, MUSIC, VIDEOS, BIN, APPS, DRIVE, USB, ROOT, PHONE, ANDROID }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = color; c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)
    }
    private fun grad(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, c1: Int, c2: Int) {
        p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, t, 0f, b, c1, c2, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, p); p.shader = null
    }

    override fun draw(c: Canvas) {
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat(); val l = b.left.toFloat(); val t = b.top.toFloat()
        fun x(f: Float) = l + w * f
        fun y(f: Float) = t + h * f
        when (kind) {
            Kind.FOLDER -> {
                grad(c, x(.06f), y(.14f), x(.46f), y(.40f), w * .07f, 0xFFE8A000.toInt(), 0xFFCB8A00.toInt())
                grad(c, x(.06f), y(.24f), x(.94f), y(.84f), w * .08f, 0xFFFFD866.toInt(), 0xFFFFC433.toInt())
            }
            Kind.FILE -> {
                rr(c, x(.18f), y(.08f), x(.82f), y(.92f), w * .08f, 0xFFF4F4F4.toInt())
                rr(c, x(.18f), y(.52f), x(.82f), y(.76f), w * .04f, extColor)
                tp.color = Color.WHITE; tp.textSize = h * .16f
                c.drawText(ext.take(4).uppercase(), x(.5f), y(.70f), tp)
            }
            Kind.HOME -> {
                val col = if (tint != 0) tint else 0xFF2D7DD2.toInt()
                p.style = Paint.Style.FILL; p.color = col
                val path = Path(); path.moveTo(x(.5f), y(.12f)); path.lineTo(x(.92f), y(.50f)); path.lineTo(x(.80f), y(.50f)); path.lineTo(x(.80f), y(.86f))
                path.lineTo(x(.20f), y(.86f)); path.lineTo(x(.20f), y(.50f)); path.lineTo(x(.08f), y(.50f)); path.close(); c.drawPath(path, p)
                rr(c, x(.42f), y(.58f), x(.58f), y(.86f), w * .03f, 0xFFFFFFFF.toInt())
            }
            Kind.DESKTOP -> {
                grad(c, x(.08f), y(.16f), x(.92f), y(.72f), w * .06f, 0xFF3AB0F0.toInt(), 0xFF0A74C9.toInt())
                rr(c, x(.40f), y(.72f), x(.60f), y(.82f), 0f, 0xFF7A8B99.toInt())
                rr(c, x(.28f), y(.82f), x(.72f), y(.88f), w * .02f, 0xFF7A8B99.toInt())
            }
            Kind.DOWNLOAD -> {
                p.style = Paint.Style.STROKE; p.color = 0xFF1E9E8A.toInt(); p.strokeWidth = w * .09f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                c.drawLine(x(.5f), y(.14f), x(.5f), y(.62f), p)
                val a = Path(); a.moveTo(x(.28f), y(.42f)); a.lineTo(x(.5f), y(.64f)); a.lineTo(x(.72f), y(.42f)); c.drawPath(a, p)
                c.drawLine(x(.18f), y(.84f), x(.82f), y(.84f), p)
            }
            Kind.DOCS -> {
                grad(c, x(.2f), y(.08f), x(.8f), y(.92f), w * .07f, 0xFFB7C3D6.toInt(), 0xFF7E90AC.toInt())
                for (i in 0 until 4) rr(c, x(.3f), y(.26f + i * .15f), x(.7f), y(.31f + i * .15f), w * .02f, 0xFFFFFFFF.toInt())
            }
            Kind.PICS -> {
                grad(c, x(.08f), y(.16f), x(.92f), y(.84f), w * .07f, 0xFF35B2F2.toInt(), 0xFF1A6FD1.toInt())
                p.style = Paint.Style.FILL; p.color = 0xFFFFE27A.toInt(); c.drawCircle(x(.70f), y(.38f), w * .07f, p)
                p.color = 0xFFFFFFFF.toInt()
                val m = Path(); m.moveTo(x(.08f), y(.84f)); m.lineTo(x(.36f), y(.46f)); m.lineTo(x(.58f), y(.72f)); m.lineTo(x(.70f), y(.60f)); m.lineTo(x(.92f), y(.84f)); m.close(); c.drawPath(m, p)
            }
            Kind.MUSIC -> {
                p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, t, 0f, t + h, 0xFFFF6E8B.toInt(), 0xFFF2602B.toInt(), Shader.TileMode.CLAMP)
                c.drawCircle(x(.5f), y(.5f), w * .42f, p); p.shader = null
                p.color = 0xFFFFFFFF.toInt(); c.drawCircle(x(.42f), y(.68f), w * .08f, p); c.drawCircle(x(.64f), y(.62f), w * .08f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = w * .05f; p.strokeCap = Paint.Cap.ROUND
                c.drawLine(x(.49f), y(.66f), x(.49f), y(.34f), p); c.drawLine(x(.71f), y(.60f), x(.71f), y(.30f), p); c.drawLine(x(.49f), y(.34f), x(.71f), y(.30f), p)
            }
            Kind.VIDEOS -> {
                grad(c, x(.08f), y(.18f), x(.92f), y(.82f), w * .08f, 0xFFA66BFF.toInt(), 0xFF6A3BD1.toInt())
                p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt()
                val m = Path(); m.moveTo(x(.42f), y(.34f)); m.lineTo(x(.42f), y(.66f)); m.lineTo(x(.68f), y(.50f)); m.close(); c.drawPath(m, p)
            }
            Kind.BIN -> {
                val col = if (tint != 0) tint else 0xFF5F7A99.toInt()
                p.style = Paint.Style.FILL; p.color = col
                rr(c, x(.26f), y(.30f), x(.74f), y(.88f), w * .06f, col)
                rr(c, x(.18f), y(.20f), x(.82f), y(.27f), w * .02f, col)
                rr(c, x(.40f), y(.12f), x(.60f), y(.20f), w * .02f, col)
                p.color = 0xFFFFFFFF.toInt(); p.alpha = 160
                rr(c, x(.36f), y(.40f), x(.40f), y(.76f), 0f, 0xA0FFFFFF.toInt()); rr(c, x(.48f), y(.40f), x(.52f), y(.76f), 0f, 0xA0FFFFFF.toInt()); rr(c, x(.60f), y(.40f), x(.64f), y(.76f), 0f, 0xA0FFFFFF.toInt())
            }
            Kind.APPS -> {
                val cols = intArrayOf(0xFF4C9BFF.toInt(), 0xFF35C08C.toInt(), 0xFFFFB02E.toInt(), 0xFFFF6B6B.toInt())
                for (i in 0 until 4) { val cx = .14f + (i % 2) * .38f; val cy = .14f + (i / 2) * .38f; rr(c, x(cx), y(cy), x(cx + .32f), y(cy + .32f), w * .08f, cols[i]) }
            }
            Kind.DRIVE -> {
                grad(c, x(.08f), y(.36f), x(.92f), y(.72f), w * .06f, 0xFFB9C4D0.toInt(), 0xFF8696A7.toInt())
                rr(c, x(.12f), y(.60f), x(.88f), y(.68f), 0f, 0xFF5B6B7B.toInt())
                p.style = Paint.Style.FILL; p.color = 0xFF3DDC84.toInt(); c.drawCircle(x(.80f), y(.54f), w * .03f, p)
            }
            Kind.USB -> {
                grad(c, x(.30f), y(.30f), x(.78f), y(.70f), w * .05f, 0xFFB9C4D0.toInt(), 0xFF8696A7.toInt())
                rr(c, x(.12f), y(.40f), x(.32f), y(.60f), w * .03f, 0xFF5B6B7B.toInt())
                rr(c, x(.16f), y(.44f), x(.22f), y(.48f), 0f, 0xFFFFFFFF.toInt()); rr(c, x(.16f), y(.52f), x(.22f), y(.56f), 0f, 0xFFFFFFFF.toInt())
            }
            Kind.ROOT -> {
                grad(c, x(.10f), y(.16f), x(.90f), y(.84f), w * .08f, 0xFF455A64.toInt(), 0xFF263238.toInt())
                tp.color = 0xFF7CFC9B.toInt(); tp.textSize = h * .42f; c.drawText("#", x(.5f), y(.62f), tp)
            }
            Kind.PHONE -> {
                grad(c, x(.28f), y(.08f), x(.72f), y(.92f), w * .08f, 0xFF607D8B.toInt(), 0xFF37474F.toInt())
                rr(c, x(.33f), y(.16f), x(.67f), y(.80f), w * .03f, 0xFFB3E5FC.toInt())
            }
            Kind.ANDROID -> {
                p.style = Paint.Style.FILL; p.color = 0xFF3DDC84.toInt()
                c.drawArc(RectF(x(.2f), y(.22f), x(.8f), y(.82f)), 180f, 180f, true, p)
                rr(c, x(.2f), y(.52f), x(.8f), y(.82f), w * .06f, 0xFF3DDC84.toInt())
            }
        }
    }

    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Draws [inner] with the "shortcut" arrow badge in the bottom-left corner. */
class ShortcutBadged(private val inner: Drawable) : Drawable() {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    override fun onBoundsChange(b: android.graphics.Rect) { inner.bounds = b }
    override fun getIntrinsicWidth() = inner.intrinsicWidth
    override fun getIntrinsicHeight() = inner.intrinsicHeight
    override fun draw(c: Canvas) {
        inner.draw(c)
        val b = bounds; val s = minOf(b.width(), b.height()).toFloat(); val r = s * 0.17f
        val cx = b.left + r + s * 0.05f; val cy = b.bottom - r - s * 0.05f
        bg.style = Paint.Style.FILL; bg.color = 0x40000000; c.drawCircle(cx, cy + s * 0.012f, r + s * 0.012f, bg)
        bg.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r, bg)
        fg.color = 0xFF263238.toInt(); fg.strokeWidth = s * 0.05f
        val a = r * 0.5f
        path.reset(); path.moveTo(cx - a, cy + a); path.lineTo(cx + a, cy - a)
        path.moveTo(cx - a * 0.05f, cy - a); path.lineTo(cx + a, cy - a); path.lineTo(cx + a, cy + a * 0.05f)
        c.drawPath(path, fg)
    }
    override fun setAlpha(alpha: Int) { inner.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { inner.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
