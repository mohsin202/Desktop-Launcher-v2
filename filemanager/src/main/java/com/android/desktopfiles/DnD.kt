package com.android.desktopfiles

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.view.DragEvent
import android.view.View
import java.io.File

/**
 * Drag and drop of files. The payload uses the launcher's own format (label "desk-drag", first item "file|<path>"; more files follow as
 * plain text items), so the launcher's desktop understands a drop too. Inside Files a drop opens a small menu: Copy here / Move here / Create shortcut here.
 */
object DnD {
    const val LABEL = "desk-drag"

    fun start(v: View, files: List<File>): Boolean {
        if (files.isEmpty()) return false
        val c = v.context; val px = c.dp(56)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val ic = Thumbs.icon(files[0]); ic.setBounds(0, 0, px, px); ic.draw(Canvas(bmp))
        val shadow = object : View.DragShadowBuilder(v) {
            override fun onProvideShadowMetrics(size: Point, touch: Point) { size.set(px, px); touch.set(px / 2, px / 2) }
            override fun onDrawShadow(canvas: Canvas) {
                canvas.drawBitmap(bmp, 0f, 0f, null)
                if (files.size > 1) {   // how many files travel together
                    val r = c.dpf(10f); val cx = px - r; val cy = r
                    val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    pt.color = Ui.pal(c).accent; canvas.drawCircle(cx, cy, r, pt)
                    pt.color = 0xFFFFFFFF.toInt(); pt.textSize = c.dpf(12f); pt.textAlign = android.graphics.Paint.Align.CENTER; pt.isFakeBoldText = true
                    canvas.drawText(files.size.toString(), cx, cy + c.dpf(4f), pt)
                }
            }
        }
        val cd = ClipData.newPlainText(LABEL, "file|" + files[0].absolutePath)
        for (f in files.drop(1)) cd.addItem(ClipData.Item(f.absolutePath))
        return try { v.startDragAndDrop(cd, shadow, null, View.DRAG_FLAG_GLOBAL) } catch (_: Exception) { false }
    }

    fun paths(cd: ClipData?): List<File> {
        if (cd == null) return emptyList()
        val out = ArrayList<File>()
        for (i in 0 until cd.itemCount) {
            var s = cd.getItemAt(i).text?.toString() ?: continue
            if (i == 0) { if (!s.startsWith("file|")) return emptyList(); s = s.substring(5) }
            out.add(File(s))
        }
        return out.filter { it.exists() }
    }

    private fun hover(v: View, on: Boolean) {
        v.foreground = if (on) Ui.rounded(0x1F000000 and 0, v.context.dpf(8f), Ui.pal(v.context).accent, v.context.dp(2)) else null
    }

    /** Makes [v] a drop target that puts the files into [dest] (null = nowhere to drop right now). */
    fun attach(host: Host, v: View, dest: () -> File?) {
        v.setOnDragListener { view, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> e.clipDescription?.label?.toString() == LABEL
                DragEvent.ACTION_DRAG_ENTERED -> { if (dest() != null) hover(view, true); true }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { hover(view, false); true }
                DragEvent.ACTION_DROP -> {
                    hover(view, false)
                    val d = dest(); val files = paths(e.clipData)
                    if (d != null && files.isNotEmpty()) {
                        val xy = IntArray(2); view.getLocationOnScreen(xy)
                        menu(host, view, xy[0] + e.x.toInt(), xy[1] + e.y.toInt(), files, d)
                    }
                    true
                }
                else -> true
            }
        }
    }

    /** Dropping on the Trash: the files go to the Trash. */
    fun attachTrash(host: Host, v: View) {
        v.setOnDragListener { view, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> e.clipDescription?.label?.toString() == LABEL
                DragEvent.ACTION_DRAG_ENTERED -> { hover(view, true); true }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { hover(view, false); true }
                DragEvent.ACTION_DROP -> {
                    hover(view, false)
                    val files = paths(e.clipData); val c = view.context
                    if (files.isNotEmpty()) Jobs.start(Job("Moving " + files.size + (if (files.size == 1) " item" else " items") + " to Trash"), { j ->
                        j.total = files.size.toLong()
                        for (f in files) { if (j.cancelled) break; j.detail = f.name; if (!TrashStore.trash(c, f)) j.failed++; j.done++ }
                    }) { host.refreshAll() }
                    true
                }
                else -> true
            }
        }
    }

    private fun menu(host: Host, anchor: View, x: Int, y: Int, files: List<File>, dest: File) {
        val c = anchor.context
        // a folder cannot go into itself or into one of its own subfolders
        if (files.any { it.isDirectory && (dest.absolutePath == it.absolutePath || dest.absolutePath.startsWith(it.absolutePath + "/")) }) {
            c.toast("A folder cannot be dropped into itself"); return
        }
        val same = files.all { it.parentFile?.absolutePath == dest.absolutePath }
        val n = files.size
        Pop(c).showAt(anchor, x, y, listOf(
            MenuItem("Copy here") {
                Jobs.start(Job("Copying " + n + (if (n == 1) " item" else " items") + " to " + dest.name), { Ops.transfer(files, dest, false, it) }) { host.refreshAll() }
                host.showStatus()
            },
            MenuItem("Move here", !same) {
                Jobs.start(Job("Moving " + n + (if (n == 1) " item" else " items") + " to " + dest.name), { Ops.transfer(files, dest, true, it) }) { host.refreshAll() }
                host.showStatus()
            },
            MenuItem("Create shortcut here") {
                var ok = 0
                for (f in files) if (Fs.makeShortcut(f, dest) != null) ok++
                c.toast(if (ok > 0) "Shortcut created" else "Could not create the shortcut"); host.refreshAll()
            }))
    }
}
