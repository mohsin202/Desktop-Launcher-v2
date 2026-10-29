package com.android.desktoplauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Trash for files and folders. Same convention as Android: a trashed item stays in its own folder and is renamed
 * to ".trashed-<expiry>-<name>" (expiry = Unix time 30 days ahead). Nothing is moved anywhere else.
 * Expired items are deleted when the Trash is opened.
 */
object TrashStore {
    private val PAT = Regex("^\\.trashed-(\\d+)-(.+)$")
    const val DAYS = 30

    class Item(val file: File, val name: String, val expiry: Long) {
        val daysLeft: Int get() = maxOf(0, Math.ceil((expiry - System.currentTimeMillis() / 1000) / 86400.0).toInt())
        val size: Long get() = try { if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length() } catch (_: Exception) { 0L }
    }

    private fun base() = Environment.getExternalStorageDirectory()

    private fun scan(c: Context, vararg paths: String) {
        try { MediaScannerConnection.scanFile(c.applicationContext, paths, null, null) } catch (_: Exception) {}
    }

    /** Runs on a worker thread. */
    fun list(c: Context): List<Item> {
        val out = LinkedHashMap<String, Item>()
        fun add(f: File) {
            val m = PAT.matchEntire(f.name) ?: return
            if (!f.exists() || out.containsKey(f.absolutePath)) return
            out[f.absolutePath] = Item(f, m.groupValues[2], m.groupValues[1].toLongOrNull() ?: 0L)
        }
        // 1) what Android itself marked as trashed
        try {
            val b = Bundle()
            b.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            c.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.Files.FileColumns.DATA), b, null)?.use {
                while (it.moveToNext()) it.getString(0)?.let { p -> add(File(p)) }
            }
        } catch (_: Exception) {}
        // 2) anything named .trashed-* that the media index does not know about (also folders)
        if (Environment.isExternalStorageManager()) {
            fun walk(d: File, depth: Int) {
                if (depth > 6) return
                val kids = try { d.listFiles() } catch (_: Exception) { null } ?: return
                for (k in kids) {
                    if (k.name.startsWith(".trashed-")) { add(k); continue }
                    if (k.isDirectory && !(k.name == "Android" && depth == 0)) walk(k, depth + 1)
                }
            }
            walk(base(), 0)
        }
        // 3) automatic clean-up of items older than 30 days
        val now = System.currentTimeMillis() / 1000
        val keep = ArrayList<Item>()
        for (it in out.values) {
            if (it.expiry in 1 until now) deleteForever(c, it) else keep += it
        }
        return keep.sortedByDescending { it.expiry }
    }

    fun trash(c: Context, f: File): Boolean {
        val exp = System.currentTimeMillis() / 1000 + DAYS * 86400L
        return try {
            val t = File(f.parentFile, ".trashed-$exp-${f.name}")
            val ok = f.renameTo(t)
            if (ok && t.isFile) scan(c, f.absolutePath, t.absolutePath)
            ok
        } catch (_: Exception) { false }
    }

    fun restore(c: Context, it: Item): Boolean = try {
        val t = Files.unique(it.file.parentFile ?: Files.root(c), it.name)
        val ok = it.file.renameTo(t)
        if (ok && t.isFile) scan(c, it.file.absolutePath, t.absolutePath)
        ok
    } catch (_: Exception) { false }

    fun deleteForever(c: Context, it: Item): Boolean = try {
        val wasFile = it.file.isFile
        val ok = it.file.deleteRecursively()
        if (wasFile) scan(c, it.file.absolutePath)
        ok
    } catch (_: Exception) { false }
}

/** The launcher's own Trash app: lists trashed files from the whole phone; restore or delete them. */
class TrashActivity : Activity() {
    private lateinit var listBox: LinearLayout
    private lateinit var restoreBtn: TextView
    private lateinit var deleteBtn: TextView
    private var items: List<TrashStore.Item> = emptyList()
    private val selected = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(2)
    private var loading = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setDecorFitsSystemWindows(false)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Colors.surfaceSolid(this))
        UiText.applyDirection(root, this)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(dp(14), bars.top + dp(8), dp(14), bars.bottom + dp(10))
            ins
        }
        val title = TextView(this)
        title.text = UiText.text(this, "Trash")
        title.textSize = 34f
        title.setTextColor(Colors.accent(this))
        title.setPadding(dp(14), dp(40), dp(14), dp(20))
        root.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))

        val card = ScrollView(this)
        card.background = Colors.rounded(cardColor(), dp(20).toFloat())
        card.isVerticalScrollBarEnabled = false
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        card.addView(listBox, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(card, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(0, dp(12), 0, 0)
        deleteBtn = pill("Delete", true) { confirmDelete() }
        restoreBtn = pill("Restore", false) { restoreSelected() }
        bar.addView(deleteBtn, LinearLayout.LayoutParams(0, dp(52), 1f).also { it.marginEnd = dp(8) })
        bar.addView(restoreBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
        updateButtons()
    }

    override fun onResume() { super.onResume(); load() }
    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    private fun cardColor(): Int {
        val base = Colors.surfaceSolid(this)
        val mix = if (Colors.night(this)) 0.07f else 0.6f
        fun ch(a: Int) = (a + (255 - a) * mix).toInt()
        return android.graphics.Color.rgb(ch(android.graphics.Color.red(base)), ch(android.graphics.Color.green(base)), ch(android.graphics.Color.blue(base)))
    }

    private fun pill(label: String, filled: Boolean, click: () -> Unit): TextView {
        val t = TextView(this)
        t.text = UiText.text(this, label)
        t.textSize = 16f
        t.gravity = Gravity.CENTER
        t.setTextColor(if (filled) Colors.iconFg(this) else Colors.accent(this))
        t.background = if (filled) Colors.rounded(Colors.iconBg(this), dp(26).toFloat())
        else android.graphics.drawable.GradientDrawable().apply { setStroke(dp(1), Colors.accent(this@TrashActivity)); cornerRadius = dp(26).toFloat() }
        t.setOnClickListener { if (selected.isNotEmpty()) click() }
        return t
    }

    private fun updateButtons() {
        val on = selected.isNotEmpty()
        deleteBtn.alpha = if (on) 1f else 0.4f
        restoreBtn.alpha = if (on) 1f else 0.4f
    }

    private fun load() {
        loading = true
        render()
        Thread {
            val l = try { TrashStore.list(this) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                items = l
                selected.retainAll(l.map { it.file.absolutePath }.toSet())
                loading = false
                render()
            }
        }.start()
    }

    private fun render() {
        listBox.removeAllViews()
        if (loading) { listBox.addView(info(UiText.text(this, "Loading…"))); return }
        if (!android.os.Environment.isExternalStorageManager())
            listBox.addView(info(UiText.text(this, "Allow all files access in the launcher Settings to see trash from the whole phone.")))
        if (items.isEmpty()) { listBox.addView(info(UiText.text(this, "Trash is empty"))); updateButtons(); return }
        listBox.addView(row(null, UiText.text(this, "All items"), "", selected.size == items.size) {
            if (selected.size == items.size) selected.clear() else items.forEach { selected += it.file.absolutePath }
            render()
        })
        listBox.addView(info(UiText.text(this, "Items are deleted after 30 days")))
        for (it in items) {
            val sub = "${it.daysLeft} " + UiText.text(this, "days left") + " • " + Formatter.formatShortFileSize(this, it.size)
            listBox.addView(row(it, it.name, sub, it.file.absolutePath in selected) {
                val p = it.file.absolutePath
                if (!selected.add(p)) selected.remove(p)
                render()
            })
        }
        updateButtons()
    }

    private fun info(t: String): View {
        val v = TextView(this)
        v.text = t
        v.textSize = 14f
        v.alpha = 0.75f
        v.setTextColor(Colors.text(this))
        v.setPadding(dp(18), dp(14), dp(18), dp(14))
        return v
    }

    private fun row(item: TrashStore.Item?, name: String, sub: String, checked: Boolean, click: () -> Unit): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(14), dp(10), dp(14), dp(10))
        if (item != null) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageDrawable(Icons.file(File(item.name)))
            val ext = item.name.substringAfterLast('.', "").lowercase()
            if (ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
                val f = item.file
                pool.execute {
                    try {
                        val o = BitmapFactory.Options(); o.inSampleSize = 1; o.inJustDecodeBounds = true
                        BitmapFactory.decodeFile(f.absolutePath, o)
                        var s = 1; while (o.outWidth / (s * 2) >= 160 && o.outHeight / (s * 2) >= 160) s *= 2
                        val o2 = BitmapFactory.Options(); o2.inSampleSize = s
                        val bmp = BitmapFactory.decodeFile(f.absolutePath, o2)
                        if (bmp != null) runOnUiThread { iv.setImageDrawable(BitmapDrawable(resources, bmp)) }
                    } catch (_: Throwable) {}
                }
            }
            iv.clipToOutline = true
            iv.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat()) }
            }
            r.addView(iv, LinearLayout.LayoutParams(dp(56), dp(56)).also { it.marginEnd = dp(14) })
        }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val n = TextView(this)
        n.text = name
        n.textSize = 16f
        n.maxLines = 1
        n.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        n.setTextColor(Colors.text(this))
        col.addView(n)
        if (sub.isNotEmpty()) {
            val s = TextView(this)
            s.text = sub
            s.textSize = 13f
            s.alpha = 0.7f
            s.setTextColor(Colors.text(this))
            col.addView(s)
        }
        r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(Tick(this, checked), LinearLayout.LayoutParams(dp(28), dp(28)))
        r.isClickable = true
        r.setOnClickListener { click() }
        return r
    }

    private fun chosen() = items.filter { it.file.absolutePath in selected }

    private fun restoreSelected() {
        val l = chosen()
        Thread {
            val n = l.count { TrashStore.restore(this, it) }
            runOnUiThread { toast(if (n > 0) UiText.text(this, "Restored") else UiText.text(this, "Could not restore")); Bus.fire(); selected.clear(); load() }
        }.start()
    }

    private fun confirmDelete() {
        val l = chosen()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(24), dp(24), dp(20))
        box.background = Colors.rounded(Colors.surfaceSolid(this), dp(28).toFloat())
        UiText.applyDirection(box, this)
        val t = TextView(this)
        t.text = if (l.size == 1) l[0].name else "${l.size} " + UiText.text(this, "items")
        t.textSize = 20f
        t.gravity = Gravity.CENTER
        t.setTextColor(Colors.text(this))
        box.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        val m = TextView(this)
        m.text = UiText.text(this, "This cannot be undone")
        m.gravity = Gravity.CENTER
        m.alpha = 0.7f
        m.setTextColor(Colors.text(this))
        box.addView(m, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(8) })
        val ok = pill("Delete", true) {}
        ok.alpha = 1f
        box.addView(ok, LinearLayout.LayoutParams(MATCH, dp(52)).also { it.topMargin = dp(18) })
        val dlg = AlertDialog.Builder(this).setView(box).create()
        dlg.window?.setBackgroundDrawable(ColorDrawable(0))
        ok.setOnClickListener {
            dlg.dismiss()
            Thread {
                l.forEach { TrashStore.deleteForever(this, it) }
                runOnUiThread { selected.clear(); load() }
            }.start()
        }
        dlg.show()
    }
}

/** Round tick box: ring when off, filled with a tick when on. */
class Tick(c: Context, private val on: Boolean) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - context.dp(3)
        p.strokeWidth = context.dp(2).toFloat()
        if (on) {
            p.style = Paint.Style.FILL; p.color = Colors.accent(context)
            canvas.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE; p.color = 0xFFFFFFFF.toInt(); p.strokeCap = Paint.Cap.ROUND
            val path = Path(); path.moveTo(cx - r * .45f, cy); path.lineTo(cx - r * .1f, cy + r * .35f); path.lineTo(cx + r * .5f, cy - r * .35f)
            canvas.drawPath(path, p)
        } else {
            p.style = Paint.Style.STROKE; p.color = Colors.text(context)
            canvas.drawCircle(cx, cy, r, p)
        }
    }
}
