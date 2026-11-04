package com.android.desktoplauncher

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.view.View
import java.io.File

/**
 * Drag from the app drawer / search results to the desktop.
 * The payload travels as plain text ("app|<component>" or "file|<path>") with a private label, so it also works
 * when the drawer lives in the taskbar's overlay window (a different window than the desktop).
 */
object DeskDrag {
    const val LABEL = "desk-drag"
    const val APP = "app"
    const val FILE = "file"

    class Payload(val kind: String, val value: String)

    fun ours(d: ClipDescription?): Boolean = d?.label?.toString() == LABEL

    fun parse(cd: ClipData?): Payload? {
        if (cd == null || cd.itemCount == 0) return null
        val s = cd.getItemAt(0).text?.toString() ?: return null
        val i = s.indexOf('|')
        if (i <= 0) return null
        return Payload(s.substring(0, i), s.substring(i + 1))
    }

    private fun snapshot(c: Context, icon: Drawable?, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        if (icon != null) {
            val cv = Canvas(bmp)
            val old = icon.copyBounds()
            icon.setBounds(0, 0, px, px)
            icon.draw(cv)
            icon.bounds = old
        }
        return bmp
    }

    /** Starts the drag from [v]. Only the icon follows the finger (the finger holds its centre). */
    fun start(v: View, p: Payload, icon: Drawable?): Boolean {
        val c = v.context
        val px = c.dp(60)
        val bmp = snapshot(c, icon, px)
        val shadow = object : View.DragShadowBuilder(v) {
            override fun onProvideShadowMetrics(size: Point, touch: Point) { size.set(px, px); touch.set(px / 2, px / 2) }
            override fun onDrawShadow(canvas: Canvas) { canvas.drawBitmap(bmp, 0f, 0f, null) }
        }
        return try {
            v.startDragAndDrop(ClipData.newPlainText(LABEL, p.kind + "|" + p.value), shadow, null, View.DRAG_FLAG_GLOBAL)
        } catch (_: Exception) { false }
    }

    /** Writes the shortcut file (same format as "Create shortcut on Desktop") on the desktop folder. */
    fun makeShortcut(c: Context, target: File): File? = try {
        val dest = Files.unique(Files.root(c), target.name + ".desktoplink")
        dest.writeText("DESKTOP_SHORTCUT\n" + target.absolutePath)
        dest
    } catch (_: Exception) { null }
}
