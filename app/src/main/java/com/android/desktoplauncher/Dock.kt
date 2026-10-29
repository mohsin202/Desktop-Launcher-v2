package com.android.desktoplauncher

import android.content.ComponentName
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Phone-mode dock (bottom row of pinned apps). In desktop mode the same apps live in the taskbar. */
class DockView(private val ctx: Context) : LinearLayout(ctx) {
    private var menu: PopupMenuX? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        background = Colors.rounded((Colors.taskbar(ctx) and 0x00FFFFFF) or 0xE6000000.toInt(), ctx.dp(30).toFloat())
        setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
        reload()
    }

    fun reload() {
        removeAllViews()
        val dots = Prefs.bool(ctx, Prefs.K_DOTS, true)
        val pins = Prefs.list(ctx, Prefs.K_PINNED).mapNotNull { ComponentName.unflattenFromString(it) }
            .filter { AppRepo.find(ctx, it) != null }.take(6)
        for (comp in pins) {
            val f = FrameLayout(ctx)
            val ic = BadgedIcon(ctx)
            ic.setImageDrawable(Icons.app(ctx, comp, false))
            ic.dot = dots && NotifState.has(comp.packageName)
            f.addView(ic, FrameLayout.LayoutParams(ctx.dp(52), ctx.dp(52), Gravity.CENTER))
            f.setOnClickListener { Launch.app(ctx, comp) }
            val show = { x: Int, y: Int ->
                menu?.dismiss()
                val m = PopupMenuX(ctx, this)
                menu = m
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                m.show(x, loc[1], listOf(
                    MenuItem("Open") { Launch.app(ctx, comp) },
                    MenuItem("App Info") { Launch.info(ctx, comp.packageName) },
                    MenuItem("Remove from dock") { Prefs.toggleInList(ctx, Prefs.K_PINNED, comp.flattenToString()) }
                ), above = true)
            }
            f.setOnLongClickListener { v: View -> val l = IntArray(2); v.getLocationOnScreen(l); show(l[0], 0); true }
            f.setSecondaryClick { x, y -> show(x, y) }
            addView(f, LayoutParams(ctx.dp(62), ctx.dp(62)))
        }
        visibility = if (pins.isEmpty()) GONE else VISIBLE
    }
}
