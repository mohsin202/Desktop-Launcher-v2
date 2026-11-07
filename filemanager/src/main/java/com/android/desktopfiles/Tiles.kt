package com.android.desktopfiles

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.AbsListView
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout

/** The grid (tile) look shared by the phone and cloud pages. */
object Tiles {
    fun isGrid(c: Context) = Store.str(c, "view", "details") == "grid"

    fun gridView(c: Context): GridView {
        val g = GridView(c)
        g.numColumns = GridView.AUTO_FIT
        g.columnWidth = c.dp(104)
        g.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        g.verticalSpacing = c.dp(4)
        g.horizontalSpacing = c.dp(4)
        g.setPaddingRelative(c.dp(8), c.dp(4), c.dp(8), c.dp(8)); g.clipToPadding = false
        g.selector = android.graphics.drawable.ColorDrawable(0)
        g.visibility = View.GONE
        return g
    }

    fun tile(c: Context, p: Pal, icon: Drawable?, name: String): LinearLayout {
        val t = LinearLayout(c)
        t.orientation = LinearLayout.VERTICAL; t.gravity = Gravity.CENTER_HORIZONTAL
        t.layoutParams = AbsListView.LayoutParams(MATCH, c.dp(110))
        t.setPaddingRelative(c.dp(4), c.dp(8), c.dp(4), c.dp(4))
        t.background = Ui.ripple(c, p, null, c.dpf(8f)); t.isClickable = true
        val iv = ImageView(c); iv.setImageDrawable(icon)
        t.addView(iv, LinearLayout.LayoutParams(c.dp(52), c.dp(52)))
        val n = Ui.text(c, name, 12f, p.text, false, false)
        n.textAlignment = View.TEXT_ALIGNMENT_GRAVITY; n.gravity = Gravity.CENTER_HORIZONTAL; n.maxLines = 2; n.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        t.addView(n, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = c.dp(4) })
        return t
    }

    /** The View ▾ menu: details list or grid (the same choice as in the folder pages). */
    fun viewItems(c: Context, changed: () -> Unit) = listOf(
        MenuItem("Details", checked = !isGrid(c)) { Store.setStr(c, "view", "details"); changed() },
        MenuItem("Grid", checked = isGrid(c)) { Store.setStr(c, "view", "grid"); changed() })
}
