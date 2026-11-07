package com.android.desktopfiles

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Thin line icons in the style of Windows 11 for the quick row of the context menu. Drawn on a 24 x 24 grid. */
class MenuIcon(private val kind: String, private val color: Int, private val sizePx: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 1.6f; color = this@MenuIcon.color
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    private fun rrect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float) = c.drawRoundRect(l, t, r, b, rad, rad, paint)

    override fun draw(c: Canvas) {
        val b = bounds
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)
        when (kind) {
            "cut" -> {          // scissors
                c.drawLine(7f, 3f, 16.5f, 15.5f, paint)
                c.drawLine(17f, 3f, 7.5f, 15.5f, paint)
                c.drawCircle(6.5f, 18.8f, 2.6f, paint)
                c.drawCircle(17.5f, 18.8f, 2.6f, paint)
            }
            "copy" -> {         // two sheets
                rrect(c, 3.5f, 8.5f, 15.5f, 21f, 2.2f)
                val p = Path()
                p.moveTo(8.5f, 6.5f); p.lineTo(8.5f, 5.5f); p.quadTo(8.5f, 3.5f, 10.5f, 3.5f); p.lineTo(18.5f, 3.5f)
                p.quadTo(20.5f, 3.5f, 20.5f, 5.5f); p.lineTo(20.5f, 13.5f); p.quadTo(20.5f, 15.5f, 18.5f, 15.5f); p.lineTo(18f, 15.5f)
                c.drawPath(p, paint)
            }
            "rename" -> {       // a text box with a cursor
                rrect(c, 2.5f, 6.5f, 21.5f, 17.5f, 2.4f)
                c.drawLine(12f, 9.5f, 12f, 14.5f, paint)
                c.drawLine(10.2f, 9.5f, 13.8f, 9.5f, paint)
                c.drawLine(10.2f, 14.5f, 13.8f, 14.5f, paint)
            }
            "share" -> {        // a box with an arrow leaving it
                val box = Path()
                box.moveTo(9.5f, 6.5f); box.lineTo(7f, 6.5f); box.quadTo(4.5f, 6.5f, 4.5f, 9f); box.lineTo(4.5f, 18f)
                box.quadTo(4.5f, 20.5f, 7f, 20.5f); box.lineTo(17f, 20.5f); box.quadTo(19.5f, 20.5f, 19.5f, 18f); box.lineTo(19.5f, 15.5f)
                c.drawPath(box, paint)
                val arrow = Path()
                arrow.moveTo(9.5f, 15f); arrow.cubicTo(9.5f, 11f, 12.5f, 9.5f, 19.5f, 9.5f)
                c.drawPath(arrow, paint)
                val head = Path()
                head.moveTo(16.5f, 6.3f); head.lineTo(20f, 9.5f); head.lineTo(16.5f, 12.7f)
                c.drawPath(head, paint)
            }
            "delete" -> {       // a bin
                c.drawLine(4f, 6.5f, 20f, 6.5f, paint)
                val handle = Path()
                handle.moveTo(9f, 6.5f); handle.lineTo(9f, 4.8f); handle.quadTo(9f, 4f, 9.8f, 4f); handle.lineTo(14.2f, 4f)
                handle.quadTo(15f, 4f, 15f, 4.8f); handle.lineTo(15f, 6.5f)
                c.drawPath(handle, paint)
                val bin = Path()
                bin.moveTo(6f, 6.5f); bin.lineTo(7f, 19f); bin.quadTo(7.1f, 20.5f, 8.6f, 20.5f); bin.lineTo(15.4f, 20.5f)
                bin.quadTo(16.9f, 20.5f, 17f, 19f); bin.lineTo(18f, 6.5f)
                c.drawPath(bin, paint)
                c.drawLine(10f, 10f, 10f, 17f, paint)
                c.drawLine(14f, 10f, 14f, 17f, paint)
            }
        }
        c.restore()
    }

    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { paint.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
