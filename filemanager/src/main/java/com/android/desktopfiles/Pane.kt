package com.android.desktopfiles

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** The Details pane on the right: preview (picture, video frame, app icon, start of a text file) and the facts about the selected item. */
class DetailsPane(private val c: Context) {
    private val p = Ui.pal(c)
    private val sv = ScrollView(c)
    private val col = LinearLayout(c)
    private var gen = 0
    val view: View get() = sv

    init {
        sv.setBackgroundColor(p.card)
        col.orientation = LinearLayout.VERTICAL
        col.setPaddingRelative(c.dp(16), c.dp(16), c.dp(16), c.dp(16))
        sv.addView(col)
    }

    private fun row(label: String, value: String): TextView {
        col.addView(Ui.text(c, label, 11f, p.sub).also { it.setPaddingRelative(0, c.dp(12), 0, c.dp(2)) })
        val v = Ui.text(c, value, 13f, p.text); v.setTextIsSelectable(true)
        col.addView(v); return v
    }

    /** Shows another view (the transfers of a device, the history of received files) instead of file details. */
    fun showView(v: View) { gen++; col.removeAllViews(); col.addView(v) }

    fun show(files: List<File>) {
        val g = ++gen
        col.removeAllViews()
        if (files.isEmpty()) { col.addView(Ui.text(c, "Select a file to see its details", 13f, p.sub)); return }
        if (files.size > 1) {
            col.addView(Ui.text(c, files.size.toString() + " items selected", 16f, p.text, true))
            val v = row("Total size", "calculating…")
            Thread {
                val t = files.sumOf { Ops.sizeOf(it) }
                c.mainExecutor.execute { if (g == gen) v.text = Fs.size(c, t) }
            }.start()
            return
        }
        val f = files[0]; val real = Fs.target(f) ?: f
        val iv = ImageView(c); iv.scaleType = ImageView.ScaleType.FIT_CENTER
        Thumbs.bind(iv, f, 480)
        col.addView(iv, LinearLayout.LayoutParams(MATCH, c.dp(190)))
        val nm = Ui.text(c, Fs.shown(f), 16f, p.text, true, false); nm.setPaddingRelative(0, c.dp(10), 0, 0); nm.setTextIsSelectable(true)
        col.addView(nm)
        row("Type", Fs.typeName(f))
        row("Location", f.parent ?: "/")
        val tg = Tags.of(f.absolutePath)
        if (tg.isNotEmpty()) row("Tags", tg.joinToString(", ") { it.name })
        val sz = row("Size", if (real.isDirectory) "calculating…" else Fs.size(c, real.length()))
        row("Modified", Fs.date(real.lastModified()))
        if (real.isDirectory) Thread {
            val t = Ops.sizeOf(real)
            c.mainExecutor.execute { if (g == gen) sz.text = Fs.size(c, t) }
        }.start()
        val e = real.extension.lowercase()
        if (real.isFile && e in setOf("txt", "md", "log", "csv", "json", "xml", "html", "kt", "java", "py", "js", "css", "ini", "conf", "yml", "yaml", "srt") && real.length() < 400_000) {
            val tv = Ui.text(c, "", 11f, p.text); tv.typeface = Typeface.MONOSPACE
            tv.background = Ui.rounded(p.bg, c.dpf(8f)); tv.setPaddingRelative(c.dp(10), c.dp(8), c.dp(10), c.dp(8))
            col.addView(Ui.text(c, "Preview", 11f, p.sub).also { it.setPaddingRelative(0, c.dp(14), 0, c.dp(4)) })
            col.addView(tv)
            Thread {
                val s = try { real.bufferedReader().use { r -> val cb = CharArray(2000); val n = r.read(cb); if (n > 0) String(cb, 0, n) else "" } } catch (_: Exception) { "" }
                c.mainExecutor.execute { if (g == gen) tv.text = s }
            }.start()
        }
    }
}
