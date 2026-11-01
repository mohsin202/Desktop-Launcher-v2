package com.android.desktoplauncher

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Home screen categories ("screens"): Discover, All, Widgets, Apps, Photos & Videos, Shortcuts, Folders, Files and
 * any number of screens the user makes. Every category has one or more pages; a category (except Discover, All and
 * the user's own) only exists while there is something to show in it.
 */
object Cats {
    const val DISCOVER = "discover"
    const val ALL = "all"
    const val WIDGETS = "widgets"
    const val APPS = "apps"
    const val MEDIA = "media"
    const val SHORTCUTS = "shortcuts"
    const val FOLDERS = "folders"
    const val FILES = "files"
    const val K_POS2 = "pos2"          // icon places per category: "tag@cat@page@col@row@key"
    private const val K_CUSTOM = "ccats"   // user made screens: "id:name"
    private const val K_ASSIGN = "cassign" // items put on a user screen: "id=key"
    private const val K_HOME = "homecat"

    private val titles = mapOf(
        DISCOVER to "Discover", ALL to "All", WIDGETS to "Widgets", APPS to "Apps", MEDIA to "Photos & Videos",
        SHORTCUTS to "Shortcuts", FOLDERS to "Folders", FILES to "Files"
    )
    private val mediaExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v")

    class Custom(val id: String, var name: String)

    fun isCustom(id: String) = id.startsWith("u_")

    fun customs(c: Context): List<Custom> = Prefs.list(c, K_CUSTOM).mapNotNull { s ->
        val i = s.indexOf(':')
        if (i <= 0) null else Custom(s.substring(0, i), s.substring(i + 1))
    }

    private fun saveCustoms(c: Context, l: List<Custom>) = Prefs.setList(c, K_CUSTOM, l.map { it.id + ":" + it.name }, fire = false)

    fun add(c: Context): String {
        val id = "u_" + System.currentTimeMillis().toString(36)
        saveCustoms(c, customs(c) + Custom(id, if (Prefs.language(c) == 1) "نئی" else "New"))
        return id
    }

    fun rename(c: Context, id: String, name: String) {
        val l = customs(c)
        l.firstOrNull { it.id == id }?.name = name.replace("|", " ")
        saveCustoms(c, l)
    }

    fun remove(c: Context, id: String) {
        saveCustoms(c, customs(c).filter { it.id != id })
        Prefs.setList(c, K_ASSIGN, Prefs.list(c, K_ASSIGN).filter { it.substringBefore('=') != id }, fire = false)
        if (home(c) == id) setHome(c, ALL)
    }

    fun name(c: Context, id: String): String =
        if (isCustom(id)) customs(c).firstOrNull { it.id == id }?.name ?: "" else UiText.text(c, titles[id] ?: id)

    fun home(c: Context): String = Prefs.getStr(c, K_HOME) ?: ALL
    fun setHome(c: Context, id: String) { Prefs.setStr(c, K_HOME, id, fire = false) }

    /** item key -> user screen id */
    fun assignments(c: Context): HashMap<String, String> {
        val m = HashMap<String, String>()
        for (s in Prefs.list(c, K_ASSIGN)) {
            val i = s.indexOf('=')
            if (i > 0) m[s.substring(i + 1)] = s.substring(0, i)
        }
        return m
    }

    fun assign(c: Context, key: String, cat: String?) {
        if (key.contains('|')) return
        val l = Prefs.list(c, K_ASSIGN).filter { it.substringAfter('=') != key }.toMutableList()
        if (cat != null) l.add("$cat=$key")
        Prefs.setList(c, K_ASSIGN, l, fire = false)
    }

    /** Does this item belong on the screen? Items put on a user screen are only shown there. */
    fun matches(cat: String, e: Entry, assigned: String?, valid: Set<String>): Boolean {
        val a = assigned?.takeIf { it in valid }
        if (isCustom(cat)) return a == cat
        if (a != null) return false
        return when (cat) {
            ALL -> true
            APPS -> e is Entry.AppE
            MEDIA -> e is Entry.FileE && !e.file.isDirectory && e.file.extension.lowercase() in mediaExt
            SHORTCUTS -> e is Entry.FileE && e.file.extension == "desktoplink"
            FOLDERS -> e is Entry.FolderE || (e is Entry.FileE && e.file.isDirectory)
            FILES -> e is Entry.FileE && !e.file.isDirectory && e.file.extension.lowercase() !in mediaExt && e.file.extension != "desktoplink"
            else -> false
        }
    }
}

/** What the category button shows: the name, or (while paging inside a category) one dot per page. */
object Screens {
    var name = ""
    var pages = 1
    var page = 0
    var dots = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val h = Handler(Looper.getMainLooper())
    private val revert = Runnable { dots = false; fire() }

    fun add(l: () -> Unit) { listeners.add(l) }
    fun remove(l: () -> Unit) { listeners.remove(l) }
    private fun fire() { for (l in listeners) l() }

    fun show(n: String, p: Int, i: Int) { name = n; pages = p; page = i; fire() }

    /** Crossing into another category shows its name; moving inside a category shows the dots for a moment. */
    fun moved(crossed: Boolean) {
        h.removeCallbacks(revert)
        if (crossed) dots = false else { dots = true; h.postDelayed(revert, 1600) }
        fire()
    }
}

/** Rounded button: category name, or dots (current page dark). */
class CatPill(ctx: Context, private val wDp: Int, private val hDp: Int, private val bgColor: () -> Int) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val l: () -> Unit = { invalidate() }

    init { isClickable = true }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Screens.add(l); invalidate() }
    override fun onDetachedFromWindow() { Screens.remove(l); super.onDetachedFromWindow() }
    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(context.dp(wDp), context.dp(hDp)) }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        bg.color = bgColor()
        canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, bg)
        val fg = Colors.iconFg(context)
        if (Screens.dots && Screens.pages > 0) {
            val n = Screens.pages
            val r = h * 0.14f
            val step = minOf(h * 0.42f, (w - h) / maxOf(1, n - 1).toFloat())
            var x = (w - step * (n - 1)) / 2f
            for (i in 0 until n) {
                dotPaint.color = if (i == Screens.page) fg else (fg and 0x00FFFFFF) or 0x55000000
                canvas.drawCircle(x, h / 2f, r, dotPaint)
                x += step
            }
        } else {
            tp.color = Colors.text(context)
            tp.textSize = h * 0.5f
            val s = TextUtils.ellipsize(Screens.name, android.text.TextPaint(tp), w - h * 0.6f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(s, w / 2f, h / 2f - (tp.ascent() + tp.descent()) / 2f, tp)
        }
    }
}

/** Small round arrow button; its colours are read again whenever the theme (light / dark) changes. */
class CatArrow(private val act: LauncherActivity, txt: String, private val onTap: () -> Unit) : TextView(act) {
    private var sig = 0
    init {
        text = txt
        textSize = 14f
        gravity = Gravity.CENTER
        setOnClickListener { onTap() }
        applyColors()
    }
    private fun key() = Colors.taskbar(act) * 31 + Colors.text(act)
    private fun applyColors() {
        sig = key()
        setTextColor(Colors.text(act))
        background = Colors.rounded((Colors.taskbar(act) and 0x00FFFFFF) or 0xE6000000.toInt(), act.dp(12).toFloat())
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); applyColors() }
    override fun onDraw(canvas: Canvas) {
        if (key() != sig) { post { applyColors() } }
        super.onDraw(canvas)
    }
}

/** "<  name  >" bar above the dock (phone mode). */
class CatBar(private val act: LauncherActivity, private val desk: DesktopView) : LinearLayout(act) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        fun arrow(txt: String, left: Boolean) = CatArrow(act, txt) {
            val rtl = desk.layoutDirection == View.LAYOUT_DIRECTION_RTL
            desk.step(if (left) (if (rtl) 1 else -1) else (if (rtl) -1 else 1))
        }
        addView(arrow("<", true), LayoutParams(act.dp(24), act.dp(24)))
        val pill = CatPill(act, 76, 24) { (Colors.taskbar(act) and 0x00FFFFFF) or 0xE6000000.toInt() }
        pill.setOnClickListener { act.openCats() }
        pill.setOnLongClickListener { desk.nextCategory(); true }
        addView(pill, LayoutParams(act.dp(76), act.dp(24)).also { it.leftMargin = act.dp(8); it.rightMargin = act.dp(8) })
        addView(arrow(">", false), LayoutParams(act.dp(24), act.dp(24)))
    }
}

/** Small house sign; dark and filled for the category that is the home screen. */
class HomeIcon(ctx: Context, private val filled: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { isClickable = true }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val path = Path()
        path.moveTo(w * 0.5f, h * 0.06f)
        path.lineTo(w * 0.94f, h * 0.48f)
        path.lineTo(w * 0.80f, h * 0.48f)
        path.lineTo(w * 0.80f, h * 0.94f)
        path.lineTo(w * 0.58f, h * 0.94f)
        path.lineTo(w * 0.58f, h * 0.64f)
        path.lineTo(w * 0.42f, h * 0.64f)
        path.lineTo(w * 0.42f, h * 0.94f)
        path.lineTo(w * 0.20f, h * 0.94f)
        path.lineTo(w * 0.20f, h * 0.48f)
        path.lineTo(w * 0.06f, h * 0.48f)
        path.close()
        val fg = Colors.iconFg(context)
        if (filled) { p.style = Paint.Style.FILL; p.color = fg }
        else {
            p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(2).toFloat(); p.strokeJoin = Paint.Join.ROUND
            p.color = (fg and 0x00FFFFFF) or 0x99000000.toInt()
        }
        canvas.drawPath(path, p)
    }
}

/**
 * The category page: a card above the button with a picture of every category (current one outlined), a home sign
 * on top, the name below and "+" at the end. Long press on a picture shows all pages of that category.
 */
class CatMenu(private val act: LauncherActivity, private val desk: DesktopView, bottomGap: Int, private val closer: () -> Unit) : FrameLayout(act) {
    private val card = LinearLayout(act)
    private var pagesOf: String? = null
    private val wall: Drawable? = try { WallpaperManager.getInstance(act).drawable } catch (_: Throwable) { null }

    init {
        UiText.applyDirection(this, act)
        setBackgroundColor(0x4D000000)
        isClickable = true
        setOnClickListener { closer() }
        card.orientation = LinearLayout.VERTICAL
        card.background = Colors.panel(act, Colors.surfaceSolid(act), act.dp(30).toFloat())
        card.setPadding(act.dp(10), act.dp(12), act.dp(10), act.dp(14))
        card.isClickable = true
        val ins = Insets.safe(act)
        addView(card, LayoutParams(MATCH, WRAP, Gravity.BOTTOM).also {
            it.bottomMargin = bottomGap; it.leftMargin = ins.left + act.dp(10); it.rightMargin = ins.right + act.dp(10)
        })
        show()
    }

    /** Back button: from the pages of a category to the list of categories. False when there is nothing more to go back to. */
    fun back(): Boolean {
        if (pagesOf != null) { pagesOf = null; show(); return true }
        return false
    }

    private fun show() {
        card.removeAllViews()
        val cat = pagesOf
        if (cat == null) buildCats() else buildPages(cat)
    }

    private fun thumbSize(): IntArray {
        val dm = act.resources.displayMetrics
        val aspect = desk.width.coerceAtLeast(1).toFloat() / desk.height.coerceAtLeast(1)
        val th = minOf(act.dp(220), (dm.heightPixels * 0.30f).toInt())
        return intArrayOf((th * aspect).toInt().coerceAtLeast(act.dp(60)), th)
    }

    private fun pill(text: String): TextView {
        val t = TextView(act)
        t.text = text
        t.textSize = 15f
        t.gravity = Gravity.CENTER
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        t.setTextColor(Colors.iconBg(act))
        t.background = Colors.rounded(Colors.iconFg(act), act.dp(20).toFloat())
        t.setPadding(act.dp(16), act.dp(7), act.dp(16), act.dp(7))
        return t
    }

    private fun thumbView(cat: String, page: Int, tw: Int, th: Int, active: Boolean): View {
        val frame = FrameLayout(act)
        val g = GradientDrawable()
        g.cornerRadius = act.dp(18).toFloat()
        g.setStroke(act.dp(3), if (active) Colors.iconFg(act) else 0)
        frame.background = g
        frame.setPadding(act.dp(3), act.dp(3), act.dp(3), act.dp(3))
        val iv = ImageView(act)
        iv.scaleType = ImageView.ScaleType.FIT_XY
        iv.setImageBitmap(desk.thumbnail(cat, page, tw, th, wall))
        iv.clipToOutline = true
        iv.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, act.dp(15).toFloat()) }
        }
        frame.addView(iv, LayoutParams(tw, th))
        return frame
    }

    private fun scroller(): HorizontalScrollView {
        val hs = HorizontalScrollView(act)
        hs.isHorizontalScrollBarEnabled = false
        return hs
    }

    private fun buildCats() {
        val order = desk.catOrder()
        val sz = thumbSize(); val tw = sz[0]; val th = sz[1]
        val hs = scroller()
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        var cur: View? = null
        for (cat in order) {
            val active = cat == desk.catId
            val col = LinearLayout(act)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.setPadding(act.dp(6), 0, act.dp(6), 0)
            val home = HomeIcon(act, Cats.home(act) == cat)
            home.setOnClickListener { Cats.setHome(act, cat); show() }
            col.addView(home, LinearLayout.LayoutParams(act.dp(34), act.dp(30)).also { it.bottomMargin = act.dp(6) })
            val open = { desk.goTo(cat, if (active) desk.pageIdx else 0); closer() }
            val tv = thumbView(cat, if (active) desk.pageIdx else 0, tw, th, active)
            tv.setOnClickListener { open() }
            tv.setOnLongClickListener { pagesOf = cat; show(); true }
            col.addView(tv, LinearLayout.LayoutParams(WRAP, WRAP))
            val name = pill(Cats.name(act, cat))
            name.setOnClickListener { open() }
            name.setOnLongClickListener { if (Cats.isCustom(cat)) { rename(cat); true } else false }
            col.addView(name, LinearLayout.LayoutParams(WRAP, WRAP).also { it.topMargin = act.dp(8) })
            row.addView(col, LinearLayout.LayoutParams(WRAP, WRAP))
            if (active) cur = col
        }
        val plus = TextView(act)
        plus.text = "+"
        plus.textSize = 28f
        plus.gravity = Gravity.CENTER
        plus.setTextColor(Colors.iconBg(act))
        plus.background = GradientDrawable().also { it.shape = GradientDrawable.OVAL; it.setColor(Colors.iconFg(act)) }
        plus.setOnClickListener {
            Cats.add(act)
            desk.refresh()
            show()
        }
        row.addView(plus, LinearLayout.LayoutParams(act.dp(52), act.dp(52)).also {
            it.gravity = Gravity.CENTER_VERTICAL; it.marginStart = act.dp(10); it.marginEnd = act.dp(6)
        })
        hs.addView(row)
        card.addView(hs, LinearLayout.LayoutParams(MATCH, WRAP))
        val target = cur
        if (target != null) hs.post { target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true) }
    }

    private fun buildPages(cat: String) {
        val title = TextView(act)
        title.text = Cats.name(act, cat)
        title.textSize = 20f
        title.gravity = Gravity.CENTER
        title.setTextColor(Colors.text(act))
        card.addView(title, LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = act.dp(10) })
        val sz = thumbSize(); val tw = sz[0]; val th = sz[1]
        val hs = scroller()
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        var cur: View? = null
        for (i in 0 until desk.pageCount(cat)) {
            val active = cat == desk.catId && i == desk.pageIdx
            val col = LinearLayout(act)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.setPadding(act.dp(6), 0, act.dp(6), 0)
            val tv = thumbView(cat, i, tw, th, active)
            tv.setOnClickListener { desk.goTo(cat, i); closer() }
            col.addView(tv, LinearLayout.LayoutParams(WRAP, WRAP))
            val n = pill((i + 1).toString())
            n.setOnClickListener { desk.goTo(cat, i); closer() }
            col.addView(n, LinearLayout.LayoutParams(WRAP, WRAP).also { it.topMargin = act.dp(8) })
            row.addView(col, LinearLayout.LayoutParams(WRAP, WRAP))
            if (active) cur = col
        }
        if (cat == Cats.WIDGETS) {
            val plus = TextView(act)
            plus.text = "+"
            plus.textSize = 28f
            plus.gravity = Gravity.CENTER
            plus.setTextColor(Colors.iconBg(act))
            plus.background = GradientDrawable().also { it.shape = GradientDrawable.OVAL; it.setColor(Colors.iconFg(act)) }
            plus.setOnClickListener { desk.addWidgetPage(); show() }
            row.addView(plus, LinearLayout.LayoutParams(act.dp(52), act.dp(52)).also {
                it.gravity = Gravity.CENTER_VERTICAL; it.marginStart = act.dp(10); it.marginEnd = act.dp(6)
            })
        }
        hs.addView(row)
        card.addView(hs, LinearLayout.LayoutParams(MATCH, WRAP))
        val target = cur
        if (target != null) hs.post { target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true) }
    }

    private fun rename(id: String) {
        val et = EditText(act)
        et.setText(Cats.name(act, id))
        et.selectAll()
        AlertDialog.Builder(act).setTitle(UiText.text(act, "Rename")).setView(et)
            .setPositiveButton(UiText.text(act, "Rename")) { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotBlank()) { Cats.rename(act, id, n); desk.refresh(); show() }
            }
            .setNeutralButton(UiText.text(act, "Delete screen")) { _, _ -> Cats.remove(act, id); desk.refresh(); show() }
            .setNegativeButton(UiText.text(act, "Cancel"), null).show()
    }
}
