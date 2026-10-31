package com.android.desktoplauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * The trash bin icon drawn in code (a see-through 3D glass bin with a recycle sign).
 * Everything is in a 432 x 432 design space and scaled to the requested size.
 * The last few trashed items are tossed into the bin: the first lies upside down on the floor, the newest sticks out of the top.
 */
object TrashArt {
    // rim corners: left, back, right, front. base corners: left, back, right, front.
    private val R = arrayOf(floatArrayOf(100f, 118f), floatArrayOf(200f, 84f), floatArrayOf(332f, 128f), floatArrayOf(228f, 160f))
    private val B = arrayOf(floatArrayOf(124f, 300f), floatArrayOf(214f, 270f), floatArrayOf(304f, 308f), floatArrayOf(230f, 342f))

    const val TILE = 84

    private fun poly(vararg p: FloatArray): Path {
        val path = Path()
        path.moveTo(p[0][0], p[0][1])
        for (i in 1 until p.size) path.lineTo(p[i][0], p[i][1])
        path.close()
        return path
    }

    private fun fill(color: Int, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.alpha = alpha }
    private fun line(color: Int, alpha: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; this.alpha = alpha; style = Paint.Style.STROKE; strokeWidth = w
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    /** [tiles] are the items, oldest first (up to 5), each a TILE x TILE bitmap. Returns a size x size bitmap. */
    fun render(tiles: List<Bitmap>, size: Int = 432): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.scale(size / 432f, size / 432f)
        val items = tiles.takeLast(5)

        // 1) inside of the bin: back walls, floor, back rim
        cv.drawPath(poly(R[0], R[1], B[1], B[0]), fill(0xFFD5DBE3.toInt(), 150))
        cv.drawPath(poly(R[1], R[2], B[2], B[1]), fill(0xFFC3CBD5.toInt(), 150))
        cv.drawPath(poly(B[0], B[1], B[2], B[3]), fill(0xFFAEB6C1.toInt(), 190))
        val rimBack = Path().apply { moveTo(R[0][0], R[0][1]); lineTo(R[1][0], R[1][1]); lineTo(R[2][0], R[2][1]) }
        cv.drawPath(rimBack, line(Color.WHITE, 235, 3f))

        // 2) items
        if (items.isNotEmpty()) drawItems(cv, items)

        // 3) front glass
        val left = poly(R[0], R[3], B[3], B[0])
        val right = poly(R[3], R[2], B[2], B[3])
        cv.drawPath(left, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 120f, 0f, 340f, intArrayOf(0x55FFFFFF, 0x66F2F5F9, 0x80E9EDF2.toInt()), null, Shader.TileMode.CLAMP)
        })
        cv.drawPath(right, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 130f, 0f, 340f, intArrayOf(0x66E4E9EF, 0x80D3DAE3.toInt(), 0xA0C4CCD6.toInt()), null, Shader.TileMode.CLAMP)
        })
        // thick glass foot
        cv.drawPath(poly(B[0], B[3], floatArrayOf(B[3][0], B[3][1] + 11f), floatArrayOf(B[0][0], B[0][1] + 11f)), fill(0xFFB5BBC4.toInt()))
        cv.drawPath(poly(B[3], B[2], floatArrayOf(B[2][0], B[2][1] + 11f), floatArrayOf(B[3][0], B[3][1] + 11f)), fill(0xFF8A919B.toInt()))
        // highlights and edges
        cv.drawPath(poly(floatArrayOf(112f, 150f), floatArrayOf(132f, 156f), floatArrayOf(150f, 296f), floatArrayOf(136f, 290f)), fill(Color.WHITE, 70))
        val rimFront = Path().apply { moveTo(R[0][0], R[0][1]); lineTo(R[3][0], R[3][1]); lineTo(R[2][0], R[2][1]) }
        cv.drawPath(rimFront, line(Color.WHITE, 245, 3.5f))
        for (i in intArrayOf(0, 3, 2)) cv.drawLine(R[i][0], R[i][1], B[i][0], B[i][1], line(Color.WHITE, 170, 2.5f))
        val lowerEdge = Path().apply { moveTo(B[0][0], B[0][1]); lineTo(B[3][0], B[3][1]); lineTo(B[2][0], B[2][1]) }
        cv.drawPath(lowerEdge, line(Color.WHITE, 150, 2.5f))

        // 4) recycle sign printed on the front glass (full colour, skewed to follow the left face)
        drawRecycle(cv, 172f, 224f)
        return out
    }

    private fun drawItems(cv: Canvas, items: List<Bitmap>) {
        val spots: List<FloatArray> = when (items.size) {
            1 -> listOf(f(212, 270, -14))
            2 -> listOf(f(180, 276, 168), f(246, 258, -22))
            3 -> listOf(f(178, 276, 168), f(252, 268, -36), f(212, 238, 14))
            4 -> listOf(f(172, 278, 168), f(256, 270, -36), f(150, 236, -24), f(214, 178, 14))
            else -> listOf(f(172, 280, 168), f(258, 272, -36), f(148, 238, -24), f(246, 232, 24), f(206, 150, 10))
        }
        val inside = poly(floatArrayOf(110f, 126f), floatArrayOf(200f, 94f), floatArrayOf(323f, 133f), floatArrayOf(298f, 316f), floatArrayOf(232f, 340f), floatArrayOf(129f, 303f))
        val border = fill(Color.WHITE)
        val shadow = fill(Color.BLACK, 64)
        val half = TILE / 2f
        val b = TILE * 0.05f
        for ((i, bmp) in items.withIndex()) {
            val sp = spots[i]
            val top = items.size >= 4 && i == items.size - 1
            cv.save()
            // the newest item may stick out of the top, but never out of the sides
            if (top) cv.clipRect(RectF(112f, 0f, 322f, 345f)) else cv.clipPath(inside)
            cv.rotate(sp[2], sp[0], sp[1])
            val h = half + b
            cv.drawRoundRect(sp[0] - h + 4, sp[1] - h + 6, sp[0] + h + 4, sp[1] + h + 6, 15f, 15f, shadow)
            cv.drawRoundRect(sp[0] - h, sp[1] - h, sp[0] + h, sp[1] + h, 15f, 15f, border)
            cv.drawBitmap(Bitmap.createScaledBitmap(bmp, TILE, TILE, true), sp[0] - half, sp[1] - half, null)
            cv.restore()
        }
    }

    private fun f(x: Int, y: Int, r: Int) = floatArrayOf(x.toFloat(), y.toFloat(), r.toFloat())

    private fun drawRecycle(cv: Canvas, cx: Float, cy: Float) {
        cv.save()
        cv.translate(cx, cy)
        cv.skew(0f, 0.30f)
        cv.scale(0.92f, 1f)
        val r = 36f
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 14f; strokeCap = Paint.Cap.BUTT
            shader = LinearGradient(-r, -r, r, r, 0xFF1E96F0.toInt(), 0xFF0B66C2.toInt(), Shader.TileMode.CLAMP)
        }
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(-r, -r, r, r, 0xFF1E96F0.toInt(), 0xFF0B66C2.toInt(), Shader.TileMode.CLAMP)
        }
        for (k in 0 until 3) {
            val start = -90f + k * 120f + 8f
            val sweep = 82f
            cv.drawArc(RectF(-r, -r, r, r), start, sweep, false, arc)
            // arrow head at the end of each arc, pointing along the circle
            val a = Math.toRadians((start + sweep).toDouble())
            val px = (Math.cos(a) * r).toFloat()
            val py = (Math.sin(a) * r).toFloat()
            val tx = (-Math.sin(a)).toFloat()   // tangent (clockwise)
            val ty = Math.cos(a).toFloat()
            val nx = Math.cos(a).toFloat()      // outward normal
            val ny = Math.sin(a).toFloat()
            val p = Path()
            p.moveTo(px + tx * 22f, py + ty * 22f)
            p.lineTo(px + nx * 17f, py + ny * 17f)
            p.lineTo(px - nx * 17f, py - ny * 17f)
            p.close()
            cv.drawPath(p, head)
        }
        cv.restore()
    }
}
