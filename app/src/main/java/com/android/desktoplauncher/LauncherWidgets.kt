package com.android.desktoplauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.Calendar

/** Widgets that are built into the launcher (not Android app widgets). Their ids are negative. */
object InternalWidgets {
    const val CLOCK_DIGITAL = "clock_digital"
    const val CLOCK_ANALOG = "clock_analog"
    const val SEARCH = "search"
    const val CLOCK_MD_FILL = "clock_md_fill"
    const val CLOCK_MD_OUTLINE = "clock_md_outline"
    const val CLOCK_SPECIAL = "clock_special"

    class Def(val type: String, val title: String, val minWdp: Int, val minHdp: Int)

    val all = listOf(
        Def(CLOCK_DIGITAL, "Clock (digital)", 180, 100),
        Def(CLOCK_ANALOG, "Clock (analog)", 160, 160),
        Def(CLOCK_MD_FILL, "Clock (digital, filled)", 200, 110),
        Def(CLOCK_MD_OUTLINE, "Clock (digital, outline)", 200, 110),
        Def(CLOCK_SPECIAL, "Clock (special, free size)", 260, 110),
        Def(SEARCH, "Search bar", 280, 56)
    )

    fun def(type: String) = all.firstOrNull { it.type == type }

    fun create(ctx: Context, type: String, onSearch: () -> Unit): View = when (type) {
        CLOCK_DIGITAL -> DigitalClockWidget(ctx)
        CLOCK_ANALOG -> AnalogClockWidget(ctx)
        CLOCK_MD_FILL -> BigDigitalClock(ctx, false)
        CLOCK_MD_OUTLINE -> BigDigitalClock(ctx, true)
        CLOCK_SPECIAL -> SpecialClockView(ctx, ClockCfg())
        else -> SearchBarWidget(ctx, onSearch)
    }

    /** Small picture of the widget for the widget picker. */
    fun preview(ctx: Context, type: String): Drawable? {
        return try {
            val d = def(type) ?: return null
            val v = create(ctx, type) {}
            val w = ctx.dp(d.minWdp)
            val h = ctx.dp(d.minHdp)
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // the big clocks are light text meant for the wallpaper: give the picker picture a dark tile
            if (type == CLOCK_SPECIAL || type == CLOCK_MD_FILL || type == CLOCK_MD_OUTLINE) bmp.eraseColor(0xFF2F3A44.toInt())
            v.draw(Canvas(bmp))
            BitmapDrawable(ctx.resources, bmp)
        } catch (_: Exception) { null }
    }

    fun nextId(existing: Collection<Int>): Int = (existing.filter { it < 0 }.minOrNull() ?: 0) - 1
}

/** Material You style digital clock: big tonal card with time and date. */
class DigitalClockWidget(ctx: Context) : LinearLayout(ctx) {
    private val time = TextClock(ctx)
    private val date = TextClock(ctx)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = Colors.rounded(Colors.iconBg(ctx), ctx.dp(28).toFloat())
        time.format12Hour = "h:mm"
        time.format24Hour = "HH:mm"
        time.setTextColor(Colors.iconFg(ctx))
        time.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        time.includeFontPadding = false
        time.gravity = Gravity.CENTER
        date.format12Hour = "EEE, MMM d"
        date.format24Hour = "EEE, MMM d"
        date.setTextColor(Colors.iconFg(ctx))
        date.alpha = 0.8f
        date.gravity = Gravity.CENTER
        date.maxLines = 1
        addView(time, LayoutParams(WRAP, WRAP))
        addView(date, LayoutParams(WRAP, WRAP))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val t = minOf(h * 0.52f, w * 0.30f)
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, t)
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, maxOf(t * 0.28f, context.dp(11).toFloat()))
    }
}

/** Material You style analog clock. */
class AnalogClockWidget(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            if (isAttachedToWindow) handler.postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.post(tick) }
    override fun onDetachedFromWindow() { handler.removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - context.dp(2)
        if (r <= 0) return
        p.style = Paint.Style.FILL
        p.color = Colors.iconBg(context)
        canvas.drawCircle(cx, cy, r, p)

        // hour marks
        p.color = Colors.iconFg(context)
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90)
            val big = i % 3 == 0
            val rr = r * (if (big) 0.84f else 0.86f)
            p.alpha = if (big) 255 else 150
            canvas.drawCircle(cx + (Math.cos(a) * rr).toFloat(), cy + (Math.sin(a) * rr).toFloat(), r * (if (big) 0.045f else 0.028f), p)
        }
        p.alpha = 255

        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f
        val m = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
        val s = cal.get(Calendar.SECOND)

        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = Colors.iconFg(context)
        p.strokeWidth = r * 0.10f
        hand(canvas, cx, cy, h * 30f, r * 0.45f)
        p.color = Colors.accent(context)
        p.strokeWidth = r * 0.07f
        hand(canvas, cx, cy, m * 6f, r * 0.68f)
        p.color = Colors.text(context)
        p.strokeWidth = r * 0.025f
        hand(canvas, cx, cy, s * 6f, r * 0.74f)
        p.style = Paint.Style.FILL
        p.color = Colors.accent(context)
        canvas.drawCircle(cx, cy, r * 0.065f, p)
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float) {
        val a = Math.toRadians(deg - 90.0)
        c.drawLine(cx, cy, cx + (Math.cos(a) * len).toFloat(), cy + (Math.sin(a) * len).toFloat(), p)
    }
}

/** Pill shaped search bar; tapping it opens the app drawer with the search field focused. */
class SearchBarWidget(ctx: Context, private val onSearch: () -> Unit) : LinearLayout(ctx) {
    private val icon = object : View(ctx) {
        private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(canvas: Canvas) {
            sp.color = Colors.text(context)
            sp.strokeWidth = context.dp(2).toFloat()
            val r = minOf(width, height) * 0.28f
            val cx = width / 2f - r * 0.25f
            val cy = height / 2f - r * 0.25f
            canvas.drawCircle(cx, cy, r, sp)
            canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f, cx + r * 1.6f, cy + r * 1.6f, sp)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Colors.rounded(Colors.surfaceSolid(ctx), ctx.dp(40).toFloat())
        elevation = ctx.dp(2).toFloat()
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        addView(icon, LayoutParams(ctx.dp(28), ctx.dp(28)))
        val t = TextView(ctx)
        t.text = UiText.text(ctx, "Search")
        t.textSize = 16f
        t.alpha = 0.7f
        t.setTextColor(Colors.text(ctx))
        t.maxLines = 1
        addView(t, LayoutParams(0, WRAP, 1f).also { it.marginStart = ctx.dp(10) })
        isClickable = true
        setOnClickListener { onSearch() }
    }
}
