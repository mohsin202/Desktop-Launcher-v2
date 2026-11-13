package com.android.desktopfiles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View.MeasureSpec
import android.view.MotionEvent
import android.view.View
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ListView

/**
 * A scroll bar for lists and grids that works with the mouse: an arrow at each end (click or hold to scroll), a thumb that can be
 * dragged, and a click on the track to move a page. (The system's bar only drags a ScrollView, not a ListView / GridView.)
 */
class DeskScroll(c: Context, private val p: Pal, private val targets: List<AbsListView>, private val sv: android.widget.ScrollView? = null) : View(c) {
    private val h = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = c.dp(16)
    private var thumbTop = 0f; private var thumbLen = 0f
    private var mode = 0            // 0 none, 1 up arrow, 2 down arrow, 3 dragging, 4 track
    private var grab = 0f
    private var trackY = 0f
    private var hover = false

    private fun cur(): AbsListView? = targets.firstOrNull { it.visibility == VISIBLE }

    /** offset, total, extent of what is scrolled, in pixels; null when there is nothing to scroll. */
    private fun metrics(): FloatArray? {
        if (sv != null) {
            val ch = sv.getChildAt(0) ?: return null
            return floatArrayOf(sv.scrollY.toFloat(), ch.height.toFloat(), (sv.height - sv.paddingTop - sv.paddingBottom).toFloat())
        }
        val t = cur() ?: return null
        val n = t.adapter?.count ?: 0
        if (n == 0 || t.childCount == 0) return null
        val first = t.getChildAt(0)
        val cols = if (t is GridView) maxOf(1, t.numColumns) else 1
        val gap = if (t is GridView) t.verticalSpacing else if (t is ListView) t.dividerHeight else 0
        val rowH = (first.height + gap).coerceAtLeast(1)
        val rows = (n + cols - 1) / cols
        val total = rows.toFloat() * rowH
        val extent = (t.height - t.paddingTop - t.paddingBottom).toFloat()
        val offset = (t.firstVisiblePosition / cols).toFloat() * rowH - (first.top - t.paddingTop)
        return floatArrayOf(offset, total, extent)
    }

    private var lastKey = ""
    private var needed = false

    /**
     * This view's layout height is MATCH_PARENT wherever it sits beside a list/ScrollView (see [attach], [attachScroll], [wrap]).
     * When it is placed inside a container that itself wraps its content (e.g. [wrap]'s FrameLayout, used in dialogs and menus),
     * a plain View asked to measure MATCH_PARENT against a non-EXACTLY (AT_MOST/UNSPECIFIED) spec would otherwise claim the whole
     * available space (the Android default for View.onMeasure), which made the wrapping FrameLayout - and so the whole dialog -
     * balloon to the size of the screen. Reporting 0 in that case costs nothing: the same child is re-measured with an EXACTLY
     * spec in the container's second pass once its real (wrap_content) size is known, and this view only ever draws using its
     * actual laid-out height anyway.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hh = if (hMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(w, hh)
    }

    /** Called on every frame (cheap): redraws only when something about the scrolling changed. Never hides the view itself, so it cannot get stuck hidden. */
    fun update() {
        val m = metrics()
        val need = m != null && m[1] > m[2] + 1
        val key = if (m == null) "-" else "${m[0].toInt()}/${m[1].toInt()}/${m[2].toInt()}/$need"
        if (key != lastKey || need != needed) { lastKey = key; needed = need; invalidate() }
    }

    private val pre = android.view.ViewTreeObserver.OnPreDrawListener { update(); true }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(pre); update() }

    override fun onDraw(cv: Canvas) {
        val m = metrics() ?: return
        if (m[1] <= m[2] + 1) return
        val w = width.toFloat(); val hh = height.toFloat()
        val trackTop = arrow.toFloat(); val trackLen = hh - 2 * arrow
        if (trackLen <= 0) return
        paint.style = Paint.Style.FILL
        paint.color = (p.sub and 0xFFFFFF) or 0x1E000000
        cv.drawRoundRect(w * 0.3f, trackTop, w * 0.7f, trackTop + trackLen, w * 0.2f, w * 0.2f, paint)
        thumbLen = maxOf(context.dp(32).toFloat(), trackLen * (m[2] / m[1])).coerceAtMost(trackLen)
        val frac = if (m[1] - m[2] > 0) (m[0] / (m[1] - m[2])).coerceIn(0f, 1f) else 0f
        thumbTop = trackTop + (trackLen - thumbLen) * frac
        val active = mode == 3 || hover
        paint.color = (if (active) p.accent else p.sub and 0xFFFFFF or 0xFF000000.toInt()).let { (it and 0xFFFFFF) or (if (active) 0xFF000000.toInt() else 0x99000000.toInt()) }
        cv.drawRoundRect(w * 0.2f, thumbTop, w * 0.8f, thumbTop + thumbLen, w * 0.3f, w * 0.3f, paint)
        // the two arrows
        paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dpf(1.8f); paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        paint.color = (p.text and 0xFFFFFF) or 0xCC000000.toInt()
        val cx = w / 2; val a = arrow / 4f
        val up = Path(); up.moveTo(cx - a, arrow * 0.62f); up.lineTo(cx, arrow * 0.62f - a); up.lineTo(cx + a, arrow * 0.62f); cv.drawPath(up, paint)
        val dn = Path(); dn.moveTo(cx - a, hh - arrow * 0.62f); dn.lineTo(cx, hh - arrow * 0.62f + a); dn.lineTo(cx + a, hh - arrow * 0.62f); cv.drawPath(dn, paint)
    }

    private fun scrollBy(px: Int) { if (sv != null) sv.scrollBy(0, px) else cur()?.scrollListBy(px); update() }

    private val repeat = object : Runnable {
        override fun run() {
            when (mode) {
                1 -> scrollBy(-context.dp(7))
                2 -> scrollBy(context.dp(7))
                4 -> {
                    val m = metrics()
                    if (m != null) {
                        val before = thumbTop; val after = thumbTop + thumbLen
                        if (trackY < before) scrollBy(-(m[2] * 0.9f).toInt()) else if (trackY > after) scrollBy((m[2] * 0.9f).toInt()) else { mode = 0; return }
                    }
                }
                else -> return
            }
            h.postDelayed(this, if (mode == 4) 140 else 16)
        }
    }

    private fun dragTo(y: Float) {
        val m = metrics() ?: return
        val trackLen = height - 2f * arrow
        val room = trackLen - thumbLen
        if (room <= 0) return
        val frac = ((y - grab - arrow) / room).coerceIn(0f, 1f)
        scrollBy((frac * (m[1] - m[2]) - m[0]).toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!needed && mode == 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val y = e.y
                when {
                    y < arrow -> { mode = 1; scrollBy(-context.dp(40)); h.postDelayed(repeat, 350) }
                    y > height - arrow -> { mode = 2; scrollBy(context.dp(40)); h.postDelayed(repeat, 350) }
                    y in thumbTop..(thumbTop + thumbLen) -> { mode = 3; grab = y - thumbTop }
                    else -> { mode = 4; trackY = y; repeat.run() }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (mode == 3) dragTo(e.y) else if (mode == 4) trackY = e.y
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = 0; h.removeCallbacks(repeat); invalidate() }
        }
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (!needed) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> { val o = e.y in thumbTop..(thumbTop + thumbLen); if (o != hover) { hover = o; invalidate() } }
            MotionEvent.ACTION_HOVER_EXIT -> { hover = false; invalidate() }
        }
        return true
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); h.removeCallbacks(repeat); try { viewTreeObserver.removeOnPreDrawListener(pre) } catch (_: Exception) {} }

    companion object {
        /** Puts the bar at the end edge of [frame], on top of the given lists / grids (the system's own bar is switched off). */
        fun attach(frame: FrameLayout, p: Pal, vararg lists: AbsListView): DeskScroll {
            val c = frame.context
            val bar = DeskScroll(c, p, lists.toList())
            for (l in lists) {
                l.isVerticalScrollBarEnabled = false
                l.setPaddingRelative(l.paddingStart, l.paddingTop, c.dp(16), l.paddingBottom)
                l.clipToPadding = false
                l.setOnScrollListener(object : AbsListView.OnScrollListener {
                    override fun onScrollStateChanged(v: AbsListView, s: Int) {}
                    override fun onScroll(v: AbsListView, f: Int, vc: Int, t: Int) { bar.update() }
                })
                l.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> bar.update() }
            }
            frame.addView(bar, FrameLayout.LayoutParams(c.dp(16), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
            return bar
        }

        /** A ScrollView with the bar on it, as one view (for dialogs, menus and panes). [pad] keeps the content clear of the bar; menus pass false and let the thin bar overlay the end edge. */
        fun wrap(sv: android.widget.ScrollView, p: Pal, pad: Boolean = true): FrameLayout {
            val c = sv.context
            val fr = FrameLayout(c)
            fr.addView(sv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            val bar = DeskScroll(c, p, emptyList(), sv)
            sv.isVerticalScrollBarEnabled = false
            if (pad) { sv.setPaddingRelative(sv.paddingStart, sv.paddingTop, c.dp(14), sv.paddingBottom); sv.clipToPadding = false }
            fr.addView(bar, FrameLayout.LayoutParams(c.dp(if (pad) 14 else 10), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
            return fr
        }

        /** The same bar for a ScrollView (the sidebar, the Home page). */
        fun attachScroll(frame: FrameLayout, p: Pal, sv: android.widget.ScrollView): DeskScroll {
            val c = frame.context
            val bar = DeskScroll(c, p, emptyList(), sv)
            sv.isVerticalScrollBarEnabled = false
            sv.setPaddingRelative(sv.paddingStart, sv.paddingTop, c.dp(14), sv.paddingBottom)
            sv.clipToPadding = false
            frame.addView(bar, FrameLayout.LayoutParams(c.dp(14), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
            return bar
        }
    }
}

/**
 * The sideways twin of [DeskScroll], for a HorizontalScrollView (the wide Details list): an arrow at each end, a draggable thumb,
 * and a click on the track moves a page. It hides itself (GONE) while everything fits, so it takes no room then.
 */
class DeskHScroll(c: Context, private val p: Pal, private val sv: android.widget.HorizontalScrollView) : View(c) {
    private val h = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = c.dp(16)
    private var thumbLeft = 0f; private var thumbLen = 0f
    private var mode = 0            // 0 none, 1 left arrow, 2 right arrow, 3 dragging, 4 track
    private var grab = 0f
    private var trackX = 0f
    private var hover = false
    private var lastKey = ""
    private var shown = false

    private fun range(): Int { val ch = sv.getChildAt(0) ?: return 0; return maxOf(0, ch.width - (sv.width - sv.paddingLeft - sv.paddingRight)) }

    fun update() {
        val r = range()
        val key = "${sv.scrollX}/$r/${sv.width}/$width"
        val need = r > 1
        if (need != shown) { shown = need; post { visibility = if (shown) VISIBLE else GONE } }
        if (key != lastKey) { lastKey = key; invalidate() }
    }

    private val pre = android.view.ViewTreeObserver.OnPreDrawListener { update(); true }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(pre); update() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); h.removeCallbacks(repeat); try { viewTreeObserver.removeOnPreDrawListener(pre) } catch (_: Exception) {} }

    override fun onDraw(cv: Canvas) {
        val r = range()
        if (r <= 1) return
        val w = width.toFloat(); val hh = height.toFloat()
        val trackLeft = arrow.toFloat(); val trackLen = w - 2 * arrow
        if (trackLen <= 0) return
        val total = r + sv.width.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = (p.sub and 0xFFFFFF) or 0x1E000000
        cv.drawRoundRect(trackLeft, hh * 0.3f, trackLeft + trackLen, hh * 0.7f, hh * 0.2f, hh * 0.2f, paint)
        thumbLen = maxOf(context.dp(32).toFloat(), trackLen * (sv.width / total)).coerceAtMost(trackLen)
        val frac = (sv.scrollX.toFloat() / r).coerceIn(0f, 1f)
        thumbLeft = trackLeft + (trackLen - thumbLen) * frac
        val active = mode == 3 || hover
        paint.color = (if (active) p.accent and 0xFFFFFF or 0xFF000000.toInt() else (p.sub and 0xFFFFFF) or 0x99000000.toInt())
        cv.drawRoundRect(thumbLeft, hh * 0.2f, thumbLeft + thumbLen, hh * 0.8f, hh * 0.3f, hh * 0.3f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dpf(1.8f); paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        paint.color = (p.text and 0xFFFFFF) or 0xCC000000.toInt()
        val cy = hh / 2; val a = arrow / 4f
        val l = Path(); l.moveTo(arrow * 0.62f, cy - a); l.lineTo(arrow * 0.62f - a, cy); l.lineTo(arrow * 0.62f, cy + a); cv.drawPath(l, paint)
        val rr = Path(); rr.moveTo(w - arrow * 0.62f, cy - a); rr.lineTo(w - arrow * 0.62f + a, cy); rr.lineTo(w - arrow * 0.62f, cy + a); cv.drawPath(rr, paint)
    }

    private fun scrollBy(px: Int) { sv.scrollBy(px, 0); update() }

    private val repeat = object : Runnable {
        override fun run() {
            when (mode) {
                1 -> scrollBy(-context.dp(7))
                2 -> scrollBy(context.dp(7))
                4 -> {
                    val before = thumbLeft; val after = thumbLeft + thumbLen
                    if (trackX < before) scrollBy(-(sv.width * 0.9f).toInt()) else if (trackX > after) scrollBy((sv.width * 0.9f).toInt()) else { mode = 0; return }
                }
                else -> return
            }
            h.postDelayed(this, if (mode == 4) 140 else 16)
        }
    }

    private fun dragTo(x: Float) {
        val r = range()
        val room = (width - 2f * arrow) - thumbLen
        if (room <= 0 || r <= 0) return
        val frac = ((x - grab - arrow) / room).coerceIn(0f, 1f)
        sv.scrollTo((frac * r).toInt(), 0); update()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (range() <= 1 && mode == 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val x = e.x
                when {
                    x < arrow -> { mode = 1; scrollBy(-context.dp(40)); h.postDelayed(repeat, 350) }
                    x > width - arrow -> { mode = 2; scrollBy(context.dp(40)); h.postDelayed(repeat, 350) }
                    x in thumbLeft..(thumbLeft + thumbLen) -> { mode = 3; grab = x - thumbLeft }
                    else -> { mode = 4; trackX = x; repeat.run() }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (mode == 3) dragTo(e.x) else if (mode == 4) trackX = e.x
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = 0; h.removeCallbacks(repeat); invalidate() }
        }
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (range() <= 1) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> { val o = e.x in thumbLeft..(thumbLeft + thumbLen); if (o != hover) { hover = o; invalidate() } }
            MotionEvent.ACTION_HOVER_EXIT -> { hover = false; invalidate() }
        }
        return true
    }
}

/** The two thin vertical lines at the end of a column title: take hold of them and drag to make the column wider or narrower. */
class ColGrip(c: Context, private val color: Int) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        paint.color = color; paint.strokeWidth = context.dpf(1.6f); paint.strokeCap = Paint.Cap.ROUND
        val cx = width / 2f; val gap = context.dpf(2.2f)
        val top = height * 0.18f; val bot = height * 0.82f
        cv.drawLine(cx - gap, top, cx - gap, bot, paint); cv.drawLine(cx + gap, top, cx + gap, bot, paint)
    }
    override fun onHoverEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> alpha = 1f
            MotionEvent.ACTION_HOVER_EXIT -> alpha = 0.75f
        }
        return super.onHoverEvent(e)
    }
    init { alpha = 0.75f }
}
