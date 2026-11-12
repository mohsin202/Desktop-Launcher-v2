package com.android.desktopfiles

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Thin line icons in the style of Windows 11 for the quick row of the context menu. Drawn on a 24 x 24 grid. */
class MenuIcon(private val kind: String, private val color: Int, private val sizePx: Int, stroke: Float = 1.6f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = stroke; color = this@MenuIcon.color
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    private fun rrect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float) = c.drawRoundRect(l, t, r, b, rad, rad, paint)
    private fun fill(c: Canvas, p: Path) { val st = paint.style; paint.style = Paint.Style.FILL; c.drawPath(p, paint); paint.style = st }

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
            "paste" -> {        // a clipboard with a sheet on it
                val board = Path()
                board.moveTo(9f, 5.5f); board.lineTo(7f, 5.5f); board.quadTo(4.5f, 5.5f, 4.5f, 8f); board.lineTo(4.5f, 19f)
                board.quadTo(4.5f, 21.5f, 7f, 21.5f); board.lineTo(11f, 21.5f)
                c.drawPath(board, paint)
                val top = Path()
                top.moveTo(15f, 5.5f); top.lineTo(17f, 5.5f); top.quadTo(19.5f, 5.5f, 19.5f, 8f); top.lineTo(19.5f, 9f)
                c.drawPath(top, paint)
                rrect(c, 8.5f, 3f, 15.5f, 7.5f, 1.8f)
                rrect(c, 10.5f, 10.5f, 20f, 21.5f, 2f)
            }
            "back", "fwd" -> {  // a thin arrow, like the Windows 11 navigation buttons
                val sg = if (kind == "back") 1f else -1f
                val x0 = if (kind == "back") 4.5f else 19.5f
                c.drawLine(x0, 12f, 24f - x0, 12f, paint)
                val head = Path(); head.moveTo(x0 + sg * 6f, 5.8f); head.lineTo(x0, 12f); head.lineTo(x0 + sg * 6f, 18.2f)
                c.drawPath(head, paint)
            }
            "up" -> {
                c.drawLine(12f, 19.5f, 12f, 4.5f, paint)
                val head = Path(); head.moveTo(5.8f, 10.5f); head.lineTo(12f, 4.5f); head.lineTo(18.2f, 10.5f)
                c.drawPath(head, paint)
            }
            "refresh" -> {      // an open circle with an arrow head
                val start = -35f
                c.drawArc(android.graphics.RectF(4.5f, 4.5f, 19.5f, 19.5f), start, 300f, false, paint)
                val a = Math.toRadians(start.toDouble())
                val tx = (12 + 7.5 * Math.cos(a)).toFloat(); val ty = (12 + 7.5 * Math.sin(a)).toFloat()
                val dir = Math.atan2(-Math.cos(a), Math.sin(a))     // counter-clockwise along the circle
                val p = Path()
                for ((i, off) in doubleArrayOf(0.7, -0.7).withIndex()) {
                    val bx = (tx - 4.5 * Math.cos(dir + off)).toFloat(); val by = (ty - 4.5 * Math.sin(dir + off)).toFloat()
                    if (i == 0) p.moveTo(bx, by) else { p.lineTo(tx, ty); p.lineTo(bx, by) }
                }
                c.drawPath(p, paint)
            }
            "send" -> {         // a paper plane
                val pl = Path(); pl.moveTo(3f, 11f); pl.lineTo(21f, 3f); pl.lineTo(14f, 21f); pl.lineTo(11.5f, 13.5f); pl.close()
                c.drawPath(pl, paint); c.drawLine(11.5f, 13.5f, 21f, 3f, paint)
            }
            "history" -> {      // a clock with a counter-clockwise arrow
                c.drawCircle(12.5f, 12f, 8f, paint)
                c.drawLine(12.5f, 7.5f, 12.5f, 12f, paint); c.drawLine(12.5f, 12f, 15.7f, 14f, paint)
                c.drawLine(1.8f, 9.5f, 4.3f, 12.3f, paint); c.drawLine(4.3f, 12.3f, 7f, 9.8f, paint)
            }
            "menu" -> {
                c.drawLine(4f, 7f, 20f, 7f, paint); c.drawLine(4f, 12f, 20f, 12f, paint); c.drawLine(4f, 17f, 20f, 17f, paint)
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
            "play" -> { val p2 = Path(); p2.moveTo(7f, 4.5f); p2.lineTo(7f, 19.5f); p2.lineTo(19f, 12f); p2.close(); fill(c, p2) }
            "pause" -> { c.drawRoundRect(6.5f, 4.5f, 10.5f, 19.5f, 1.4f, 1.4f, paint); c.drawRoundRect(13.5f, 4.5f, 17.5f, 19.5f, 1.4f, 1.4f, paint) }
            "prev", "next" -> {
                val sg = if (kind == "prev") -1f else 1f
                val p2 = Path(); p2.moveTo(12f - sg * 7.5f, 4.5f); p2.lineTo(12f - sg * 7.5f, 19.5f); p2.lineTo(12f + sg * 3.5f, 12f); p2.close(); fill(c, p2)
                c.drawLine(12f + sg * 3.3f, 4.5f, 12f + sg * 3.3f, 19.5f, paint)
            }
            "lock" -> {
                rrect(c, 5.5f, 11f, 18.5f, 20.5f, 2.2f)
                val sh = Path(); sh.moveTo(8.3f, 11f); sh.lineTo(8.3f, 7.8f); sh.quadTo(8.3f, 3.5f, 12f, 3.5f); sh.quadTo(15.7f, 3.5f, 15.7f, 7.8f); sh.lineTo(15.7f, 11f)
                c.drawPath(sh, paint)
                c.drawCircle(12f, 15f, 1.6f, paint)
            }
            "unlock" -> {
                rrect(c, 5.5f, 11f, 18.5f, 20.5f, 2.2f)
                val sh = Path(); sh.moveTo(8.3f, 11f); sh.lineTo(8.3f, 7.8f); sh.quadTo(8.3f, 3.5f, 12f, 3.5f); sh.quadTo(15.7f, 3.5f, 15.7f, 6.5f)
                c.drawPath(sh, paint)
                c.drawCircle(12f, 15f, 1.6f, paint)
            }
            "rotate" -> {
                c.drawArc(android.graphics.RectF(4.5f, 4.5f, 19.5f, 19.5f), -35f, 300f, false, paint)
                val a = Math.toRadians(-35.0); val tx = (12 + 7.5 * Math.cos(a)).toFloat(); val ty = (12 + 7.5 * Math.sin(a)).toFloat()
                c.drawLine(tx, ty, tx - 4.2f, ty - 1.5f, paint); c.drawLine(tx, ty, tx - 1.2f, ty + 4.2f, paint)
            }
            "pip" -> { rrect(c, 3.5f, 5.5f, 20.5f, 18.5f, 2f); c.drawRoundRect(12f, 11f, 19f, 17f, 1.5f, 1.5f, paint.also { it.style = Paint.Style.FILL }); paint.style = Paint.Style.STROKE }
            "fit" -> {
                rrect(c, 3.5f, 5.5f, 20.5f, 18.5f, 2f)
                val a1 = Path(); a1.moveTo(8f, 9f); a1.lineTo(8f, 6.5f); a1.lineTo(10.5f, 6.5f); c.drawPath(a1, paint)
                val a2 = Path(); a2.moveTo(16f, 9f); a2.lineTo(16f, 6.5f); a2.lineTo(13.5f, 6.5f); c.drawPath(a2, paint)
                val a3 = Path(); a3.moveTo(8f, 15f); a3.lineTo(8f, 17.5f); a3.lineTo(10.5f, 17.5f); c.drawPath(a3, paint)
                val a4 = Path(); a4.moveTo(16f, 15f); a4.lineTo(16f, 17.5f); a4.lineTo(13.5f, 17.5f); c.drawPath(a4, paint)
            }
            "loop" -> {
                c.drawArc(android.graphics.RectF(4.5f, 4.5f, 19.5f, 19.5f), 20f, 230f, false, paint)
                c.drawArc(android.graphics.RectF(4.5f, 4.5f, 19.5f, 19.5f), 200f, 230f, false, paint)
                val h1 = Path(); h1.moveTo(16.2f, 4.1f); h1.lineTo(19.3f, 5.6f); h1.lineTo(17f, 8.4f); c.drawPath(h1, paint)
                val h2 = Path(); h2.moveTo(7.8f, 19.9f); h2.lineTo(4.7f, 18.4f); h2.lineTo(7f, 15.6f); c.drawPath(h2, paint)
            }
            "headset" -> {
                c.drawArc(android.graphics.RectF(5f, 3.5f, 19f, 19f), 180f, 180f, false, paint)
                rrect(c, 3.5f, 12.5f, 7.5f, 19f, 1.8f); rrect(c, 16.5f, 12.5f, 20.5f, 19f, 1.8f)
            }
            "subtitle" -> {
                rrect(c, 3f, 6f, 21f, 18f, 2.2f)
                c.drawLine(6f, 10.2f, 11f, 10.2f, paint); c.drawLine(13f, 10.2f, 18f, 10.2f, paint)
                c.drawLine(6f, 14f, 14.5f, 14f, paint)
            }
            "close" -> { c.drawLine(5.5f, 5.5f, 18.5f, 18.5f, paint); c.drawLine(18.5f, 5.5f, 5.5f, 18.5f, paint) }
            "speed" -> {
                c.drawCircle(12f, 12.5f, 8f, paint)
                c.drawLine(12f, 12.5f, 16f, 8.5f, paint); c.drawLine(12f, 12.5f, 12f, 7f, paint)
                c.drawLine(9.5f, 2.5f, 14.5f, 2.5f, paint)
            }
            "more" -> { c.drawCircle(12f, 6f, 1.5f, paint.also { it.style = Paint.Style.FILL }); c.drawCircle(12f, 12f, 1.5f, paint); c.drawCircle(12f, 18f, 1.5f, paint); paint.style = Paint.Style.STROKE }
        }
        c.restore()
    }

    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { paint.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
