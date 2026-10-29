package com.android.desktoplauncher

import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One widget in the picker. [add] receives the screen position to place it at (-1,-1 = first free spot). */
class WidgetItem(
    val label: String, val appKey: String, val appName: String, val appIcon: Drawable?,
    val minWdp: Int, val minHdp: Int, val preview: () -> Drawable?, val add: (Int, Int) -> Unit
)

/** Payload of a widget being dragged from the picker onto the desktop. */
class WidgetDrag(val item: WidgetItem)

/**
 * Widget picker as a bottom sheet (Lawnchair / Launcher3 style).
 * Portrait: list of apps; tap an app to show / hide its widgets. Wide screens: apps on one side, widgets of the
 * selected app on the other. Tap a widget for "Add to home screen", or long-press and drag it onto the desktop.
 */
class WidgetSheet(
    private val c: Context,
    private val items: List<WidgetItem>,
    private val topInset: Int,
    private val bottomInset: Int,
    private val spansFor: (Int, Int) -> IntArray,
    private val onClose: () -> Unit
) : FrameLayout(c) {

    private val sheet = LinearLayout(c)
    private val content = FrameLayout(c)
    private val search = EditText(c)
    private val twoPane = c.resources.displayMetrics.widthPixels >= c.dp(720)
    private val groups: LinkedHashMap<String, List<WidgetItem>>
    private val expanded = HashSet<String>()
    private var selectedApp: String? = null
    private var selectedItem: WidgetItem? = null
    private var query = ""
    private val scrolls = ArrayList<ScrollView>()
    private var downY = 0f
    private var dragging = false
    private var startAtTop = false
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(c).scaledTouchSlop

    init {
        val own = c.packageName
        groups = LinkedHashMap()
        val byApp = items.groupBy { it.appKey }
        byApp.keys.sortedWith(compareBy({ it != own }, { byApp[it]!!.first().appName.lowercase() })).forEach { groups[it] = byApp[it]!! }
        UiText.applyDirection(this, c)
        setBackgroundColor(0x66000000)
        isClickable = true
        setOnClickListener { onClose() }

        sheet.orientation = LinearLayout.VERTICAL
        sheet.isClickable = true
        val r = c.dp(28).toFloat()
        sheet.background = GradientDrawable().apply {
            setColor(Colors.surfaceSolid(c)); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        sheet.setPadding(c.dp(12), c.dp(8), c.dp(12), 0)
        val sw = c.resources.displayMetrics.widthPixels
        val width = if (twoPane) minOf(c.dp(960), (sw * 0.94f).toInt()) else MATCH
        addView(sheet, LayoutParams(width, MATCH, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also {
            it.topMargin = topInset + c.dp(if (twoPane) 24 else 12)
            it.bottomMargin = bottomInset
        })

        val handle = View(c)
        handle.background = Colors.rounded(Colors.text(c) and 0x55FFFFFF, c.dp(3).toFloat())
        sheet.addView(handle, LinearLayout.LayoutParams(c.dp(36), c.dp(4)).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = c.dp(6) })

        val title = TextView(c)
        title.text = UiText.text(c, "Widgets")
        title.textSize = 22f
        title.gravity = Gravity.CENTER
        title.setTextColor(Colors.text(c))
        sheet.addView(title, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = c.dp(14); it.bottomMargin = c.dp(12) })

        val pill = LinearLayout(c)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = Colors.rounded(card(), c.dp(30).toFloat())
        pill.setPadding(c.dp(18), 0, c.dp(14), 0)
        search.hint = UiText.text(c, "Search")
        search.setSingleLine()
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.textSize = 16f
        search.background = null
        search.setTextColor(Colors.text(c))
        search.setHintTextColor((Colors.text(c) and 0x00FFFFFF) or 0x88000000.toInt())
        pill.addView(search, LinearLayout.LayoutParams(0, MATCH, 1f))
        pill.addView(SearchButton(c), LinearLayout.LayoutParams(c.dp(32), c.dp(32)))
        sheet.addView(pill, LinearLayout.LayoutParams(MATCH, c.dp(54)).also { it.bottomMargin = c.dp(10) })

        sheet.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; render() }
        })
        render()
        // slide in
        sheet.translationY = c.resources.displayMetrics.heightPixels.toFloat()
        post { sheet.animate().translationY(0f).setDuration(220).start() }
    }

    private fun card(): Int {
        val base = Colors.surfaceSolid(c)
        val mix = if (Colors.night(c)) 0.07f else 0.6f
        fun ch(a: Int, b: Int) = (a + (b - a) * mix).toInt()
        return Color.rgb(ch(Color.red(base), 255), ch(Color.green(base), 255), ch(Color.blue(base), 255))
    }

    private fun matches(g: List<WidgetItem>): List<WidgetItem> =
        if (query.isEmpty()) g else {
            val appHit = g.first().appName.contains(query, true)
            if (appHit) g else g.filter { it.label.contains(query, true) }
        }

    private fun render() {
        content.removeAllViews()
        scrolls.clear()
        val visible = groups.entries.map { it.key to matches(it.value) }.filter { it.second.isNotEmpty() }
        if (visible.isEmpty()) {
            val t = TextView(c)
            t.text = UiText.text(c, "No widgets found")
            t.setTextColor(Colors.text(c))
            t.gravity = Gravity.CENTER
            content.addView(t, LayoutParams(MATCH, c.dp(120)))
            return
        }
        if (twoPane) renderTwoPane(visible) else renderSingle(visible)
    }

    // ---- portrait: apps with expandable widget lists
    private fun renderSingle(visible: List<Pair<String, List<WidgetItem>>>) {
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, 0, 0, c.dp(24))
        for ((key, list) in visible) {
            val open = query.isNotEmpty() || key in expanded
            val card = LinearLayout(c)
            card.orientation = LinearLayout.VERTICAL
            card.background = Colors.rounded(card(), c.dp(24).toFloat())
            val chev = Chevron(c, open)
            card.addView(appRow(list.first(), list.size, chev, false) {
                if (key in expanded) expanded.remove(key) else expanded.add(key)
                render()
            })
            if (open) {
                val body = LinearLayout(c)
                body.orientation = LinearLayout.VERTICAL
                body.setPadding(c.dp(8), 0, c.dp(8), c.dp(10))
                for (w in list) body.addView(widgetView(w))
                card.addView(body)
            }
            col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = c.dp(8) })
        }
        val sv = ScrollView(c)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(col, LayoutParams(MATCH, WRAP))
        scrolls += sv
        content.addView(sv, LayoutParams(MATCH, MATCH))
    }

    // ---- wide: apps on one side, widgets of the selected app on the other
    private fun renderTwoPane(visible: List<Pair<String, List<WidgetItem>>>) {
        if (selectedApp == null || visible.none { it.first == selectedApp }) selectedApp = null
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        val left = LinearLayout(c)
        left.orientation = LinearLayout.VERTICAL
        left.setPadding(0, 0, 0, c.dp(24))
        for ((key, list) in visible) {
            val sel = key == selectedApp
            val r = appRow(list.first(), list.size, null, sel) { selectedApp = key; selectedItem = null; render() }
            val wrap = FrameLayout(c)
            wrap.background = Colors.rounded(if (sel) Colors.surfaceHigh(c) else card(), c.dp(20).toFloat())
            wrap.addView(r)
            left.addView(wrap, LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = c.dp(6) })
        }
        val lsv = ScrollView(c)
        lsv.isVerticalScrollBarEnabled = false
        lsv.addView(left, LayoutParams(MATCH, WRAP))
        scrolls += lsv
        row.addView(lsv, LinearLayout.LayoutParams(0, MATCH, 0.42f).also { it.marginEnd = c.dp(10) })

        val right = LinearLayout(c)
        right.orientation = LinearLayout.VERTICAL
        right.setPadding(c.dp(6), 0, c.dp(6), c.dp(24))
        val sel = visible.firstOrNull { it.first == selectedApp }
        if (sel != null) for (w in sel.second) right.addView(widgetView(w))
        val rsv = ScrollView(c)
        rsv.isVerticalScrollBarEnabled = false
        rsv.addView(right, LayoutParams(MATCH, WRAP))
        scrolls += rsv
        val rightBox = FrameLayout(c)
        rightBox.background = Colors.rounded(card(), c.dp(24).toFloat())
        rightBox.addView(rsv, LayoutParams(MATCH, MATCH))
        row.addView(rightBox, LinearLayout.LayoutParams(0, MATCH, 0.58f))
        content.addView(row, LayoutParams(MATCH, MATCH))
    }

    private fun appRow(first: WidgetItem, count: Int, chev: Chevron?, selected: Boolean, click: () -> Unit): View {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
        val icon = ImageView(c)
        icon.setImageDrawable(first.appIcon)
        row.addView(icon, LinearLayout.LayoutParams(c.dp(44), c.dp(44)).also { it.marginEnd = c.dp(14) })
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        val n = TextView(c)
        n.text = first.appName
        n.textSize = 17f
        n.maxLines = 1
        n.ellipsize = android.text.TextUtils.TruncateAt.END
        n.setTextColor(Colors.text(c))
        col.addView(n)
        val s = TextView(c)
        s.text = "$count " + UiText.text(c, if (count == 1) "widget" else "widgets")
        s.textSize = 13f
        s.alpha = 0.7f
        s.setTextColor(Colors.text(c))
        col.addView(s)
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (chev != null) row.addView(chev, LinearLayout.LayoutParams(c.dp(28), c.dp(28)))
        row.isClickable = true
        row.setOnClickListener { click() }
        return row
    }

    private fun widgetView(w: WidgetItem): View {
        val isSel = selectedItem === w
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(c.dp(10), c.dp(12), c.dp(10), c.dp(12))
        box.background = if (isSel) Colors.rounded(Colors.surfaceHigh(c), c.dp(20).toFloat()) else null

        val img = ImageView(c)
        img.adjustViewBounds = true
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.maxHeight = c.dp(150)
        img.maxWidth = c.dp(300)
        img.setImageDrawable(w.preview() ?: w.appIcon)
        box.addView(img, LinearLayout.LayoutParams(WRAP, WRAP))

        val name = TextView(c)
        name.text = w.label
        name.textSize = 15f
        name.gravity = Gravity.CENTER
        name.setTextColor(Colors.text(c))
        box.addView(name, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = c.dp(8) })
        val sp = spansFor(w.minWdp, w.minHdp)
        val size = TextView(c)
        size.text = "${sp[0]} × ${sp[1]}"
        size.textSize = 13f
        size.alpha = 0.7f
        size.gravity = Gravity.CENTER
        size.setTextColor(Colors.text(c))
        box.addView(size, LinearLayout.LayoutParams(MATCH, WRAP))

        if (isSel) {
            val add = TextView(c)
            add.text = UiText.text(c, "Add to home screen")
            add.textSize = 15f
            add.gravity = Gravity.CENTER
            add.setTextColor(Colors.iconFg(c))
            add.background = Colors.rounded(Colors.iconBg(c), c.dp(24).toFloat())
            add.setPadding(c.dp(22), c.dp(10), c.dp(22), c.dp(10))
            add.setOnClickListener { onClose(); w.add(-1, -1) }
            box.addView(add, LinearLayout.LayoutParams(WRAP, WRAP).also { it.topMargin = c.dp(10) })
        }

        box.isClickable = true
        box.setOnClickListener { selectedItem = if (isSel) null else w; render() }
        box.setOnLongClickListener {
            val clip = ClipData.newPlainText("widget", w.label)
            val ok = box.startDragAndDrop(clip, View.DragShadowBuilder(img), WidgetDrag(w), 0)
            // Hide the sheet so the desktop below receives the drop.
            if (ok) visibility = INVISIBLE
            ok
        }
        return box
    }

    fun restoreAfterDrag() { visibility = VISIBLE }

    /** True when the list under the finger is already scrolled to its first item (or the touch is on the header). */
    private fun atTop(ev: MotionEvent): Boolean {
        val loc = IntArray(2)
        for (sv in scrolls) {
            sv.getLocationOnScreen(loc)
            if (ev.rawX >= loc[0] && ev.rawX <= loc[0] + sv.width && ev.rawY >= loc[1] && ev.rawY <= loc[1] + sv.height) {
                return !sv.canScrollVertically(-1)
            }
        }
        return true
    }

    // Swipe down while the list is already at its top: the sheet follows the finger and closes.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY; dragging = false; startAtTop = atTop(ev)
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (startAtTop && !dragging && ev.rawY - downY > slop * 2) { dragging = true; return true }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> sheet.translationY = maxOf(0f, ev.rawY - downY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                dragging = false
                if (ev.actionMasked == MotionEvent.ACTION_UP && (sheet.translationY > c.dp(110) || vy > 1200f)) onClose()
                else sheet.animate().translationY(0f).setDuration(180).start()
            }
        }
        return true
    }
}

/** Small chevron: points down when collapsed, up when expanded. */
class Chevron(c: Context, private val up: Boolean) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(canvas: Canvas) {
        p.color = Colors.text(context)
        p.strokeWidth = context.dp(2).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = width * 0.22f
        val path = Path()
        if (up) { path.moveTo(cx - r, cy + r / 2); path.lineTo(cx, cy - r / 2); path.lineTo(cx + r, cy + r / 2) }
        else { path.moveTo(cx - r, cy - r / 2); path.lineTo(cx, cy + r / 2); path.lineTo(cx + r, cy - r / 2) }
        canvas.drawPath(path, p)
    }
}
