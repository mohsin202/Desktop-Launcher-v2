package com.android.desktoplauncher

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

class MenuItem(
    val label: String,
    val enabled: Boolean = true,
    val bold: Boolean = false,
    val checked: Boolean = false,
    val children: List<MenuItem>? = null,
    val action: (() -> Unit)? = null
)

/** Context menu with sub menus, drawn as popup windows (works inside overlay windows too). */
class PopupMenuX(private val ctx: Context, private val anchor: View) {
    private val stack = ArrayList<PopupWindow>()

    fun show(x: Int, y: Int, items: List<MenuItem>, above: Boolean = false) =
        level(x, y, items, 0, above, null)

    fun dismiss() = closeFrom(0)

    private fun closeFrom(n: Int) {
        while (stack.size > n) stack.removeAt(stack.size - 1).dismiss()
    }

    private fun level(x: Int, y: Int, items: List<MenuItem>, lvl: Int, above: Boolean, flipX: Int?) {
        closeFrom(lvl)
        val col = LinearLayout(ctx)
        UiText.applyDirection(col, ctx)
        col.orientation = LinearLayout.VERTICAL
        col.background = Colors.rounded(Colors.surface(ctx), ctx.dp(12).toFloat())
        col.setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(4), ctx.dp(6))
        col.minimumWidth = ctx.dp(190)

        for (item in items) {
            val row = LinearLayout(ctx)
            UiText.applyDirection(row, ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
            val tv = TextView(ctx)
            tv.text = (if (item.checked) "✓  " else "") + UiText.text(ctx, item.label)
            tv.textSize = 15f
            tv.setTextColor(Colors.text(ctx))
            if (item.bold && item.enabled) tv.setTypeface(tv.typeface, Typeface.BOLD)
            row.alpha = if (item.enabled) 1f else 0.38f
            row.addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (item.children != null) {
                val arrow = TextView(ctx)
                arrow.text = "›"
                arrow.textSize = 18f
                arrow.setTextColor(Colors.text(ctx))
                row.addView(arrow)
            }
            row.setOnHoverListener { v, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
                    v.setBackgroundColor(0x22888888)
                    if (item.enabled && item.children != null) openSub(v, item, lvl) else closeFrom(lvl + 1)
                } else if (ev.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
                    v.setBackgroundColor(0)
                }
                false
            }
            row.setOnClickListener { v ->
                if (item.enabled) {
                    if (item.children != null) openSub(v, item, lvl)
                    else { dismiss(); item.action?.invoke() }
                }
            }
            col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        col.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = col.measuredWidth
        val h = col.measuredHeight
        val dm = ctx.resources.displayMetrics
        var px = x
        if (px + w > dm.widthPixels) px = if (flipX != null) flipX - w else dm.widthPixels - w
        if (px < 0) px = 0
        var py = if (above) y - h else y
        if (py + h > dm.heightPixels) py = dm.heightPixels - h
        if (py < 0) py = 0

        val pw = PopupWindow(col, WRAP, WRAP, lvl == 0)
        pw.setBackgroundDrawable(ColorDrawable(0))
        pw.elevation = ctx.dp(8).toFloat()
        pw.isClippingEnabled = false
        pw.setOnDismissListener {
            if (stack.isNotEmpty() && stack[0] === pw) { stack.removeAt(0); closeFrom(0) }
        }
        stack.add(pw)
        try {
            // Sub windows are positioned relative to the parent window, so convert from screen coordinates.
            val a = IntArray(2); val b = IntArray(2)
            anchor.getLocationOnScreen(a); anchor.getLocationInWindow(b)
            pw.showAtLocation(anchor, Gravity.NO_GRAVITY, px - (a[0] - b[0]), py - (a[1] - b[1]))
            Blur.popup(ctx, pw)
        } catch (e: Exception) {
            stack.remove(pw)
        }
    }

    private fun openSub(v: View, item: MenuItem, lvl: Int) {
        val kids = item.children ?: return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        level(loc[0] + v.width, loc[1] - ctx.dp(6), kids, lvl + 1, false, loc[0])
    }
}
