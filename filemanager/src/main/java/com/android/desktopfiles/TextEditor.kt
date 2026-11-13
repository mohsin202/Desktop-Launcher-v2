package com.android.desktopfiles

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Both languages in one place: the editor's own texts. */
private fun L(en: String, ur: String): String = if (T.ur) ur else en

/** Which files open in the text editor when they are opened from the file manager. */
object TextKinds {
    private val exts = setOf(
        "txt", "log", "md", "markdown", "rst", "json", "xml", "yml", "yaml", "toml", "ini", "cfg", "conf", "config", "properties", "prop", "env",
        "sh", "bash", "zsh", "bat", "cmd", "ps1", "rc", "css", "scss", "js", "mjs", "jsx", "tsx", "kt", "kts", "java", "py", "c", "h", "cpp", "hpp", "cc",
        "cs", "go", "rs", "php", "rb", "pl", "lua", "sql", "gradle", "tex", "srt", "vtt", "ass", "lrc", "gitignore", "gitattributes", "desktop",
        "service", "mk", "cmake", "smali", "reg", "inf", "ino", "swift", "dart", "vue", "diff", "patch", "lock", "plist", "manifest")
    private val names = setOf("makefile", "dockerfile", "readme", "license", "changelog", "build.prop", "local.prop", "default.prop", "fstab", "hosts", "init.rc")
    fun isText(f: File): Boolean = f.isFile && (f.extension.lowercase() in exts || f.name.lowercase() in names)
}

/** One open file (a tab). */
class EdDoc(var path: String?, var uri: Uri?, var name: String) {
    var text = ""
    var modified = false
    var crlf = false
    var charset: Charset = Charsets.UTF_8
    var bom = false
    var sel = 0
    var scrollY = 0
    var savedAt = System.currentTimeMillis()
    val fresh: Boolean get() = path == null && uri == null
}

/** Marks a highlighted match so it can be taken off again. */
class EdHl(color: Int) : BackgroundColorSpan(color)

/** The text area: line numbers at the start edge (right in Urdu, left in English) and a soft band over the line the cursor is on. */
class EdText(c: Context, private val p: Pal) : EditText(c) {
    var numbers = true
    var starts = IntArray(1)
    private val np = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint()
    private val lp = Paint()
    private var gutterPx = 0
    private var padKey = -1

    /** Where every line (paragraph) begins; used for the line numbers and the current-line band. */
    fun recount() {
        val s = text.toString()
        var n = 1
        for (ch in s) if (ch == '\n') n++
        val a = IntArray(n)
        var k = 1
        for (i in s.indices) if (s[i] == '\n') a[k++] = i + 1
        starts = a
        updateGutter()
    }

    fun updateGutter() {
        val digits = maxOf(2, starts.size.toString().length)
        gutterPx = if (numbers) (digits * textSize * 0.5f + context.dp(14)).toInt() else 0
        val key = gutterPx * 1000 + (textSize * 10).toInt()
        if (key != padKey) {
            padKey = key
            setPaddingRelative(gutterPx + context.dp(10), context.dp(8), context.dp(10), context.dp(90))
        }
    }

    private fun paraIndex(off: Int): Int { val i = java.util.Arrays.binarySearch(starts, off); return if (i >= 0) i else maxOf(0, -i - 2) }

    override fun onDraw(cv: Canvas) {
        val l = layout
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        if (l != null) {
            val s = selectionStart
            if (s >= 0 && s == selectionEnd && s <= text.length) {
                val idx = paraIndex(s)
                val ps = starts[idx]
                val pe = if (idx + 1 < starts.size) starts[idx + 1] - 1 else text.length
                if (ps <= text.length) {
                    val first = l.getLineForOffset(ps); val last = l.getLineForOffset(maxOf(ps, minOf(pe, text.length)))
                    bp.color = (p.accent and 0xFFFFFF) or 0x1E000000
                    cv.drawRect(scrollX.toFloat(), (totalPaddingTop + l.getLineTop(first)).toFloat(), (scrollX + width).toFloat(), (totalPaddingTop + l.getLineBottom(last)).toFloat(), bp)
                }
            }
        }
        super.onDraw(cv)
        if (l != null && numbers) {
            val r = cv.clipBounds
            val f = l.getLineForVertical(maxOf(0, r.top - totalPaddingTop)); val t = l.getLineForVertical(maxOf(0, r.bottom - totalPaddingTop))
            np.textSize = textSize * 0.82f; np.color = (p.sub and 0xFFFFFF) or 0xCC000000.toInt()
            np.typeface = Typeface.MONOSPACE
            np.textAlign = if (rtl) Paint.Align.LEFT else Paint.Align.RIGHT
            val x = if (rtl) scrollX + width - gutterPx + context.dp(4).toFloat() else scrollX + gutterPx - context.dp(4).toFloat()
            for (i in f..minOf(t, l.lineCount - 1)) {
                val st = l.getLineStart(i)
                val k = java.util.Arrays.binarySearch(starts, st)
                if (k >= 0) cv.drawText((k + 1).toString(), x, (totalPaddingTop + l.getLineBaseline(i)).toFloat(), np)
            }
            lp.color = p.line; lp.strokeWidth = 1f
            val lx = if (rtl) scrollX + width - gutterPx - context.dp(2).toFloat() else scrollX + gutterPx + context.dp(2).toFloat()
            cv.drawLine(lx, r.top.toFloat(), lx, r.bottom.toFloat(), lp)
        }
    }
}

class TextEditorActivity : Activity() {
    private lateinit var p: Pal
    private val docs = ArrayList<EdDoc>()
    private var cur = -1
    private lateinit var root: LinearLayout
    private lateinit var top: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var ed: EdText
    private lateinit var tabStrip: LinearLayout
    private lateinit var saveBtn: TextView
    private lateinit var menuBtn: View
    private var loading = false

    private var findCard: View? = null
    private var findInput: EditText? = null
    private var findCount: TextView? = null
    private var matches: List<Int> = emptyList()
    private var matchLen = 0
    private var matchAt = -1
    private var gotoCard: View? = null
    private var lastFind: IntArray? = null     // the match the Find and Replace dialog selected last
    private var printView: android.webkit.WebView? = null
    private var pendingDoc: EdDoc? = null
    private var pendingThen: (() -> Unit)? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val rehighlight = Runnable { if (findCard != null) quickFind(false) }

    // ---------------------------------------------------------------- start

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        T.init(Store.str(this, "lang", "system"))
        p = Ui.pal(this)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
        openFrom(intent)
        if (docs.isEmpty()) newDoc()
    }

    private fun openFrom(i: Intent?) {
        if (i == null) return
        val path = i.getStringExtra("path")
        if (path != null) { openPath(path); return }
        val u = i.data ?: return
        openUri(u)
    }

    private fun prefInt(k: String, d: Int) = Store.str(this, "ed:$k", d.toString()).toIntOrNull() ?: d
    private fun prefBool(k: String, d: Boolean) = Store.bool(this, "ed:$k", d)

    // ---------------------------------------------------------------- layout

    private fun buildUi() {
        root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = Dir.value()
        window.decorView.layoutDirection = Dir.value()
        root.setBackgroundColor(p.bg)
        setContentView(root)

        top = LinearLayout(this); top.orientation = LinearLayout.VERTICAL
        top.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(p.bar, p.barEnd))
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        val sv = HorizontalScrollView(this); sv.isHorizontalScrollBarEnabled = false
        tabStrip = LinearLayout(this); tabStrip.gravity = Gravity.CENTER_VERTICAL; tabStrip.setPaddingRelative(dp(8), dp(6), dp(8), 0)
        sv.addView(tabStrip, FrameLayout.LayoutParams(WRAP, MATCH))
        top.addView(sv, LinearLayout.LayoutParams(MATCH, dp(40)))

        val bar = LinearLayout(this); bar.gravity = Gravity.CENTER_VERTICAL; bar.setPaddingRelative(dp(8), 0, dp(10), 0)
        menuBtn = Ui.iconButton(this, p, "menu") { showMenu() }
        bar.addView(menuBtn)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val openBtn = pill(L("Open", "کھولیں")) { openDialog() }
        bar.addView(openBtn, LinearLayout.LayoutParams(WRAP, dp(38)).also { it.marginEnd = dp(8) })
        saveBtn = pill(L("Save", "محفوظ")) { saveCurrent(null) }
        bar.addView(saveBtn, LinearLayout.LayoutParams(WRAP, dp(38)))
        top.addView(bar, LinearLayout.LayoutParams(MATCH, dp(50)))

        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        ed = EdText(this, p)
        ed.background = null
        ed.gravity = Gravity.TOP
        ed.setTextColor(p.text)
        ed.setHighlightColor(p.sel)
        ed.layoutDirection = Dir.value()
        ed.textDirection = if (T.rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        ed.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        ed.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        ed.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        ed.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                ed.recount()
                if (!loading) {
                    val d = docs.getOrNull(cur)
                    if (d != null && !d.modified) { d.modified = true; refreshTabs() }
                    if (findCard != null) { ui.removeCallbacks(rehighlight); ui.postDelayed(rehighlight, 350) }
                }
            }
        })
        scroll = ScrollView(this); scroll.isFillViewport = true; scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(ed, FrameLayout.LayoutParams(MATCH, WRAP))
        body.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        DeskScroll.attachScroll(body, p, scroll)
        applyPrefs()

        root.setOnApplyWindowInsetsListener { v, ins ->
            var l = 0; var t = 0; var r = 0; var b = 0
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())
                l = i.left; t = i.top; r = i.right; b = i.bottom
            } else {
                @Suppress("DEPRECATION") run { l = ins.systemWindowInsetLeft; t = ins.systemWindowInsetTop; r = ins.systemWindowInsetRight; b = ins.systemWindowInsetBottom }
            }
            v.setPadding(l, 0, r, b); top.setPadding(0, t, 0, 0)
            ins
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun pill(label: String, click: () -> Unit): TextView {
        val t = Ui.text(this, label, 15f, 0xFFFFFFFF.toInt(), true, false)
        t.gravity = Gravity.CENTER; t.minWidth = dp(84); t.setPadding(dp(18), 0, dp(18), 0)
        t.background = Ui.ripple(this, p, Ui.rounded(if (p.dark) p.accentSoft else p.accent, dpf(12f)), dpf(12f))
        t.isClickable = true; t.setOnClickListener { click() }
        return t
    }

    private fun applyPrefs() {
        ed.textSize = prefInt("size", 15).toFloat()
        ed.typeface = if (prefBool("mono", false)) Typeface.MONOSPACE else Typeface.DEFAULT
        ed.setHorizontallyScrolling(!prefBool("wrap", true))
        ed.numbers = prefBool("nums", true)
        ed.recount()
        ed.requestLayout(); ed.invalidate()
    }

    // ---------------------------------------------------------------- tabs

    private fun refreshTabs() {
        tabStrip.removeAllViews()
        for ((i, d) in docs.withIndex()) {
            val chip = LinearLayout(this); chip.gravity = Gravity.CENTER_VERTICAL
            chip.setPaddingRelative(dp(12), 0, dp(4), 0)
            chip.background = if (i == cur) Ui.rounded(p.bg, dpf(10f)).also { it.cornerRadii = floatArrayOf(dpf(10f), dpf(10f), dpf(10f), dpf(10f), 0f, 0f, 0f, 0f) }
                              else Ui.ripple(this, p, null, dpf(10f))
            chip.isClickable = true
            chip.setOnClickListener { if (i != cur) switchTo(i) }
            val ic = ImageView(this); ic.setImageDrawable(Glyph(Glyph.Kind.DOCS))
            chip.addView(ic, LinearLayout.LayoutParams(dp(18), dp(18)).also { it.marginEnd = dp(8) })
            val tx = Ui.text(this, d.name + (if (d.modified) "*" else ""), 13f, p.text, i == cur, false)
            tx.maxLines = 1; tx.ellipsize = android.text.TextUtils.TruncateAt.END; tx.maxWidth = dp(170)
            chip.addView(tx)
            chip.addView(Ui.glyphButton(this, p, "×", 15f, 28, 28) { closeDoc(i) }, LinearLayout.LayoutParams(dp(28), dp(28)).also { it.marginStart = dp(4) })
            tabStrip.addView(chip, LinearLayout.LayoutParams(WRAP, dp(34)).also { it.marginEnd = dp(2) })
        }
        tabStrip.addView(Ui.glyphButton(this, p, "+", 20f, 34, 34) { newDoc() })
    }

    private fun store() {
        val d = docs.getOrNull(cur) ?: return
        d.text = ed.text.toString(); d.sel = ed.selectionStart.coerceAtLeast(0); d.scrollY = scroll.scrollY
    }

    private fun switchTo(i: Int) {
        if (i < 0 || i >= docs.size) return
        if (cur in docs.indices) store()
        hideCards()
        cur = i
        val d = docs[i]
        loading = true
        ed.setText(d.text)
        ed.setSelection(d.sel.coerceIn(0, ed.text.length))
        loading = false
        ed.recount()
        scroll.post { scroll.scrollTo(0, d.scrollY) }
        refreshTabs()
    }

    private fun addDoc(d: EdDoc) { docs.add(d); switchTo(docs.size - 1) }

    private fun newDoc() {
        var n = 1
        fun nm() = L("New File", "نئی فائل") + (if (n > 1) " $n" else "") + ".txt"
        while (docs.any { it.fresh && it.name == nm() }) n++
        addDoc(EdDoc(null, null, nm()))
    }

    private fun closeDoc(i: Int) {
        if (i !in docs.indices) return
        if (i == cur) store()
        val d = docs[i]
        if (!d.modified) { removeDoc(i); return }
        switchTo(i)
        val name = d.name
        choice(
            L("Save changes to document \"$name\" before closing?", "بند کرنے سے پہلے دستاویز «$name» کی تبدیلیاں محفوظ کریں؟"),
            L("If you don't save, changes from the last ${dur(System.currentTimeMillis() - d.savedAt)} will be permanently lost.",
              "اگر آپ محفوظ نہ کریں تو پچھلے ${dur(System.currentTimeMillis() - d.savedAt)} کی تبدیلیاں ہمیشہ کے لیے ضائع ہو جائیں گی۔"),
            listOf(
                L("Close without Saving", "بغیر محفوظ کیے بند کریں") to { removeDoc(docs.indexOf(d)) },
                L("Cancel", "منسوخ") to {},
                (if (d.fresh) L("Save As", "اس نام سے محفوظ") else L("Save", "محفوظ کریں")) to { saveDoc(d) { removeDoc(docs.indexOf(d)) } }))
    }

    private fun removeDoc(i: Int) {
        if (i !in docs.indices) return
        docs.removeAt(i)
        if (docs.isEmpty()) { cur = -1; finish(); return }
        val to = if (i <= cur) maxOf(0, cur - 1) else cur
        cur = -1
        switchTo(to.coerceIn(0, docs.size - 1))
    }

    private fun dur(ms: Long): String {
        val m = ms / 60000; val h = m / 60
        return if (T.ur) when {
            h > 0 -> "$h گھنٹے اور ${m % 60} منٹ"
            m > 0 -> "$m منٹ"
            else -> "چند سیکنڈ"
        } else when {
            h > 0 -> "$h hour${if (h > 1) "s" else ""} and ${m % 60} minute${if (m % 60 != 1L) "s" else ""}"
            m > 0 -> "$m minute${if (m > 1) "s" else ""}"
            else -> "few seconds"
        }
    }

    override fun onBackPressed() {
        if (findCard != null || gotoCard != null) { hideCards(); return }
        quit()
    }

    /** Closes the window: every changed tab is asked about first. */
    private fun quit() {
        store()
        val i = docs.indexOfFirst { it.modified }
        if (i < 0) { finish(); return }
        val d = docs[i]
        switchTo(i)
        val name = d.name
        choice(
            L("Save changes to document \"$name\" before closing?", "بند کرنے سے پہلے دستاویز «$name» کی تبدیلیاں محفوظ کریں؟"),
            L("If you don't save, changes from the last ${dur(System.currentTimeMillis() - d.savedAt)} will be permanently lost.",
              "اگر آپ محفوظ نہ کریں تو پچھلے ${dur(System.currentTimeMillis() - d.savedAt)} کی تبدیلیاں ہمیشہ کے لیے ضائع ہو جائیں گی۔"),
            listOf(
                L("Close without Saving", "بغیر محفوظ کیے بند کریں") to { d.modified = false; quit() },
                L("Cancel", "منسوخ") to {},
                (if (d.fresh) L("Save As", "اس نام سے محفوظ") else L("Save", "محفوظ کریں")) to { saveDoc(d) { quit() } }))
    }

    // ---------------------------------------------------------------- opening and saving

    private fun openPath(path: String) {
        val i = docs.indexOfFirst { it.path == path }
        if (i >= 0) { switchTo(i); return }
        val f = File(path)
        val d = load(f, null, f.name) ?: return
        d.path = f.absolutePath
        if (docs.size == 1 && docs[0].fresh && !docs[0].modified) { docs.clear(); cur = -1 }
        addDoc(d)
    }

    private fun openUri(u: Uri) {
        val f = uriToFile(u)
        if (f != null) { openPath(f.absolutePath); return }
        val i = docs.indexOfFirst { it.uri == u }
        if (i >= 0) { switchTo(i); return }
        val d = load(null, u, nameOf(u)) ?: return
        d.uri = u
        if (docs.size == 1 && docs[0].fresh && !docs[0].modified) { docs.clear(); cur = -1 }
        addDoc(d)
    }

    private fun nameOf(u: Uri): String {
        try { contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) return it.getString(0) } } catch (_: Exception) {}
        return u.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun uriToFile(u: Uri): File? {
        try {
            if (u.scheme == "file") return u.path?.let { File(it) }
            if (u.authority == "com.android.externalstorage.documents") {
                val parts = DocumentsContract.getDocumentId(u).split(":", limit = 2)
                if (parts.size == 2) {
                    val base = if (parts[0] == "primary") Environment.getExternalStorageDirectory() else File("/storage/" + parts[0])
                    val f = File(base, parts[1])
                    if (f.isFile && f.canRead() && f.canWrite()) return f
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun load(f: File?, u: Uri?, name: String): EdDoc? {
        val max = 16 * 1024 * 1024
        val b: ByteArray = try {
            if (f != null) { if (f.length() > max) { toastL("This file is too large for the editor", "یہ فائل ایڈیٹر کے لیے بہت بڑی ہے"); return null }; f.readBytes() }
            else contentResolver.openInputStream(u!!)!!.use { it.readBytes() }
        } catch (e: Exception) { toastL("Could not open this file", "یہ فائل کھل نہیں سکی"); return null }
        if (b.size > max) { toastL("This file is too large for the editor", "یہ فائل ایڈیٹر کے لیے بہت بڑی ہے"); return null }
        val d = EdDoc(null, null, name)
        var cs: Charset = Charsets.UTF_8; var off = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) { d.bom = true; off = 3 }
        else if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) { cs = Charsets.UTF_16LE; d.bom = true; off = 2 }
        else if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) { cs = Charsets.UTF_16BE; d.bom = true; off = 2 }
        else {
            val n = minOf(b.size, 8000)
            for (k in 0 until n) if (b[k] == 0.toByte()) { toastL("This looks like a binary file, not text", "یہ ٹیکسٹ نہیں بلکہ بائنری فائل لگتی ہے"); return null }
        }
        var s: String
        if (cs == Charsets.UTF_8) {
            try { s = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, off, b.size - off)).toString() }
            catch (e: CharacterCodingException) { cs = Charset.forName("windows-1252"); s = String(b, off, b.size - off, cs) }
        } else s = String(b, off, b.size - off, cs)
        if (s.indexOf('\r') >= 0) { d.crlf = s.contains("\r\n"); s = s.replace("\r\n", "\n").replace('\r', '\n') }
        d.charset = cs; d.text = s
        return d
    }

    private fun encode(d: EdDoc): ByteArray {
        var s = d.text
        if (d.crlf) s = s.replace("\n", "\r\n")
        val body = s.toByteArray(d.charset)
        if (!d.bom) return body
        val bom = when (d.charset) {
            Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            Charsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        }
        return bom + body
    }

    private fun saveCurrent(then: (() -> Unit)?) { store(); docs.getOrNull(cur)?.let { saveDoc(it, then) } }

    /** Writes the tab to its file; a tab that has no file yet asks where to put it. */
    private fun saveDoc(d: EdDoc, then: (() -> Unit)? = null) {
        if (docs.indexOf(d) == cur) store()
        if (d.fresh) { saveAs(d, then); return }
        try {
            val bytes = encode(d)
            if (d.path != null) File(d.path!!).writeBytes(bytes)
            else contentResolver.openOutputStream(d.uri!!, "wt")!!.use { it.write(bytes) }
            d.modified = false; d.savedAt = System.currentTimeMillis()
            refreshTabs(); toastL("Saved", "محفوظ ہو گیا")
            then?.invoke()
        } catch (e: Exception) { toastL("Could not save the file", "فائل محفوظ نہیں ہو سکی") }
    }

    private fun saveAs(d: EdDoc, then: (() -> Unit)? = null) {
        pendingDoc = d; pendingThen = then
        val ext = d.name.substringAfterLast('.', "").lowercase()
        val mime = if (ext == "txt" || ext.isEmpty()) "text/plain" else (android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream")
        try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, d.name), 11) }
        catch (e: Exception) { toastL("Could not open the save dialog", "محفوظ کرنے کی ونڈو نہیں کھل سکی") }
    }

    private fun openDialog() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 12) }
        catch (e: Exception) { toastL("Could not open the file dialog", "فائل چننے کی ونڈو نہیں کھل سکی") }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) { if (req == 11) { pendingDoc = null; pendingThen = null }; return }
        if (req == 11) {
            val u = data.data ?: return
            val d = pendingDoc ?: return
            val then = pendingThen
            pendingDoc = null; pendingThen = null
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            val f = uriToFile(u)
            d.name = if (f != null) f.name else nameOf(u)
            if (f != null) d.path = f.absolutePath else d.uri = u
            saveDoc(d, then)
        } else if (req == 12) {
            val cd = data.clipData
            if (cd != null) for (k in 0 until cd.itemCount) openUri(cd.getItemAt(k).uri) else data.data?.let { openUri(it) }
        }
    }

    private fun reload() {
        val d = docs.getOrNull(cur) ?: return
        if (d.fresh) return
        val doit = {
            val n = load(d.path?.let { File(it) }, d.uri, d.name)
            if (n != null) { d.text = n.text; d.crlf = n.crlf; d.charset = n.charset; d.bom = n.bom; d.modified = false; d.savedAt = System.currentTimeMillis(); d.sel = 0; val i = docs.indexOf(d); cur = -1; switchTo(i) }
        }
        if (!d.modified) doit() else choice(L("Reload this file?", "یہ فائل دوبارہ لوڈ کریں؟"), L("Your changes in this tab will be lost.", "اس ٹیب کی تبدیلیاں ضائع ہو جائیں گی۔"),
            listOf(L("Cancel", "منسوخ") to {}, L("Reload", "دوبارہ لوڈ") to { doit() }))
    }

    private fun printDoc() {
        store()
        val d = docs.getOrNull(cur) ?: return
        try {
            val wv = android.webkit.WebView(this); printView = wv
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(v: android.webkit.WebView, u: String?) {
                    val pm = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
                    pm.print(d.name, v.createPrintDocumentAdapter(d.name), android.print.PrintAttributes.Builder().build())
                }
            }
            wv.loadDataWithBaseURL(null, "<html><body dir=\"auto\"><pre style=\"white-space:pre-wrap;font-size:12px\">" + android.text.TextUtils.htmlEncode(d.text) + "</pre></body></html>", "text/html", "UTF-8", null)
        } catch (e: Exception) { toastL("Printing is not available", "پرنٹنگ دستیاب نہیں") }
    }

    private fun toastL(en: String, ur: String) { android.widget.Toast.makeText(applicationContext, L(en, ur), android.widget.Toast.LENGTH_SHORT).show() }

    // ---------------------------------------------------------------- menu

    private fun showMenu() {
        hideCards()
        val view = listOf(
            MenuItem(L("Word wrap", "الفاظ لپیٹیں"), checked = prefBool("wrap", true)) { Store.setBool(this, "ed:wrap", !prefBool("wrap", true)); applyPrefs() },
            MenuItem(L("Line numbers", "لائن نمبر"), checked = prefBool("nums", true)) { Store.setBool(this, "ed:nums", !prefBool("nums", true)); applyPrefs() },
            MenuItem(L("Zoom in", "بڑا کریں"), hint = "Ctrl +") { zoom(1) },
            MenuItem(L("Zoom out", "چھوٹا کریں"), hint = "Ctrl -") { zoom(-1) },
            MenuItem(L("Reset zoom", "سائز بحال کریں"), hint = "Ctrl 0") { zoom(0) })
        val tools = listOf(
            MenuItem(L("UPPERCASE", "بڑے حروف")) { transform { it.uppercase() } },
            MenuItem(L("lowercase", "چھوٹے حروف")) { transform { it.lowercase() } },
            MenuItem(L("Trim trailing spaces", "آخر کی خالی جگہیں ہٹائیں")) { transformAll { t -> t.split("\n").joinToString("\n") { it.trimEnd() } } },
            MenuItem(L("Sort lines", "لائنیں ترتیب دیں")) { transform { t -> t.split("\n").sorted().joinToString("\n") } },
            MenuItem(L("Remove duplicate lines", "دہری لائنیں ہٹائیں")) { transformAll { t -> t.split("\n").distinct().joinToString("\n") } },
            MenuItem(L("Insert date and time", "تاریخ اور وقت ڈالیں")) { insertText(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())) },
            MenuItem(L("Select all", "سب منتخب کریں"), hint = "Ctrl A") { ed.requestFocus(); ed.selectAll() })
        val items = listOf<MenuItem?>(
            MenuItem("", row = listOf(
                MenuItem(L("Reload", "دوبارہ لوڈ"), icon = "refresh") { reload() },
                MenuItem(L("Print", "پرنٹ"), icon = "print") { printDoc() })),
            MenuItem(L("New Window", "نئی ونڈو"), hint = "Ctrl N") { newWindow() },
            null,
            MenuItem(L("Save As…", "اس نام سے محفوظ کریں…"), hint = "Ctrl Shift S") { store(); docs.getOrNull(cur)?.let { saveAs(it) } },
            MenuItem(L("Save All", "سب محفوظ کریں")) { saveAll() },
            null,
            MenuItem(L("Find…", "تلاش…"), hint = "Ctrl F") { showFind() },
            MenuItem(L("Find and Replace…", "تلاش اور تبدیلی…"), hint = "Ctrl H") { showReplace() },
            MenuItem(L("Clear Highlight", "ہائی لائٹ ہٹائیں")) { clearHl() },
            MenuItem(L("Go to Line…", "لائن پر جائیں…"), hint = "Ctrl G") { showGoto() },
            null,
            MenuItem(L("View", "منظر"), sub = view),
            MenuItem(L("Tools", "اوزار"), sub = tools),
            null,
            MenuItem(L("Preference", "ترجیحات")) { showPrefs() })
        val loc = IntArray(2); menuBtn.getLocationOnScreen(loc)
        if (T.rtl) Pop(this).showAt(menuBtn, loc[0] + menuBtn.width, loc[1] + menuBtn.height, items, alignEnd = true)
        else Pop(this).showAt(menuBtn, loc[0], loc[1] + menuBtn.height, items)
    }

    private fun newWindow() {
        startActivity(Intent(this, TextEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
    }

    private fun saveAll() {
        store()
        val list = docs.filter { it.modified && !it.fresh }
        for (d in list) saveDoc(d)
        docs.firstOrNull { it.modified && it.fresh }?.let { saveAs(it) }
    }

    private fun zoom(d: Int) {
        val n = if (d == 0) 15 else (prefInt("size", 15) + d).coerceIn(8, 40)
        Store.setStr(this, "ed:size", n.toString()); applyPrefs()
    }

    private fun insertText(s: String) {
        ed.requestFocus()
        val a = minOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(0); val b = maxOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(0)
        ed.text.replace(a, b, s)
    }

    /** Changes the selected text, or the whole text when nothing is selected. */
    private fun transform(f: (String) -> String) {
        val len = ed.text.length
        var a = minOf(ed.selectionStart, ed.selectionEnd); var b = maxOf(ed.selectionStart, ed.selectionEnd)
        if (a < 0 || a == b) { a = 0; b = len }
        val r = f(ed.text.substring(a, b))
        ed.text.replace(a, b, r)
        ed.setSelection(a, a + r.length)
    }

    private fun transformAll(f: (String) -> String) {
        val r = f(ed.text.toString())
        val keep = ed.selectionStart.coerceAtLeast(0)
        ed.text.replace(0, ed.text.length, r)
        ed.setSelection(keep.coerceAtMost(ed.text.length))
    }

    // ---------------------------------------------------------------- small floating boxes (Find, Go to line)

    private fun hideCards() {
        findCard?.let { body.removeView(it) }; gotoCard?.let { body.removeView(it) }
        findCard = null; gotoCard = null; findInput = null; findCount = null
        ui.removeCallbacks(rehighlight)
        clearHl()
        val v = currentFocus; if (v != null) (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** A small rounded box that sits under the Save button and can be dragged anywhere with a finger. */
    private fun makeCard(): LinearLayout {
        val card = LinearLayout(this); card.orientation = LinearLayout.HORIZONTAL; card.gravity = Gravity.CENTER_VERTICAL
        card.background = Ui.rounded(p.card, dpf(14f), p.line, 1); card.elevation = dpf(8f)
        card.setPadding(dp(10), dp(10), dp(10), dp(10))
        var dx = 0f; var dy = 0f
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = v.x - e.rawX; dy = v.y - e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    v.x = (e.rawX + dx).coerceIn(0f, maxOf(0f, (body.width - v.width).toFloat()))
                    v.y = (e.rawY + dy).coerceIn(0f, maxOf(0f, (body.height - v.height).toFloat()))
                }
            }
            true
        }
        val w = minOf(dp(300), (body.width * 0.92f).toInt()).let { if (it <= 0) dp(280) else it }
        body.addView(card, FrameLayout.LayoutParams(w, WRAP))
        card.visibility = View.INVISIBLE
        card.post {
            val a = IntArray(2); val bb = IntArray(2)
            saveBtn.getLocationInWindow(a); body.getLocationInWindow(bb)
            val sx = (a[0] - bb[0]).toFloat()
            val x = if (T.rtl) sx else sx + saveBtn.width - card.width
            card.x = x.coerceIn(0f, maxOf(0f, (body.width - card.width).toFloat())); card.y = dpf(8f)
            card.visibility = View.VISIBLE
        }
        return card
    }

    private fun boxInput(hint: String): EditText {
        val e = EditText(this)
        e.background = null; e.hint = hint; e.setHintTextColor(p.sub); e.setTextColor(p.text); e.textSize = 15f
        e.setSingleLine(); e.setPadding(dp(4), dp(4), dp(4), dp(4)); e.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_SEARCH
        e.layoutDirection = Dir.value(); e.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        e.textDirection = if (T.rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        return e
    }

    private fun showFind() {
        val sel = if (ed.selectionStart != ed.selectionEnd) ed.text.substring(minOf(ed.selectionStart, ed.selectionEnd), maxOf(ed.selectionStart, ed.selectionEnd)) else ""
        hideCards()
        val card = makeCard(); findCard = card
        val input = boxInput(L("Find", "تلاش")); findInput = input
        val count = Ui.text(this, "", 12f, p.sub, false, false); findCount = count
        card.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(count, LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginStart = dp(6); it.marginEnd = dp(2) })
        card.addView(Ui.glyphButton(this, p, "▲", 11f, 30, 32) { stepFind(-1) })
        card.addView(Ui.glyphButton(this, p, "▼", 11f, 30, 32) { stepFind(1) })
        card.addView(Ui.glyphButton(this, p, "×", 18f, 30, 32) { hideCards() })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) { quickFind(true) }
        })
        input.setOnEditorActionListener { _, _, _ -> stepFind(1); true }
        input.setOnKeyListener { _, k, e ->
            if ((k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) && e.action == KeyEvent.ACTION_DOWN) { stepFind(if (e.isShiftPressed) -1 else 1); true } else false
        }
        if (sel.isNotEmpty() && !sel.contains('\n') && sel.length < 200) { input.setText(sel); input.setSelection(sel.length) }
        focusInput(input)
    }

    private fun focusInput(e: EditText) {
        e.requestFocus()
        e.postDelayed({ (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(e, InputMethodManager.SHOW_IMPLICIT) }, 150)
    }

    private fun clearHl() {
        val e = ed.text
        for (s in e.getSpans(0, e.length, EdHl::class.java)) e.removeSpan(s)
    }

    private fun hlSoft() = (p.accent and 0xFFFFFF) or 0x44000000
    private fun hlCur() = (p.accent and 0xFFFFFF) or 0x99000000.toInt()

    /** Marks every place the text in the box occurs; [jump] also moves to the first one after the cursor. */
    private fun quickFind(jump: Boolean) {
        val q = findInput?.text?.toString() ?: ""
        clearHl()
        if (q.isEmpty()) { matches = emptyList(); matchAt = -1; showCount(); return }
        val t = ed.text.toString()
        val list = ArrayList<Int>()
        var i = t.indexOf(q, 0, true)
        while (i >= 0) { list.add(i); i = t.indexOf(q, i + maxOf(1, q.length), true) }
        matches = list; matchLen = q.length
        if (list.isEmpty()) matchAt = -1
        else if (jump || matchAt !in list.indices) { val caret = ed.selectionStart.coerceAtLeast(0); matchAt = list.indexOfFirst { it >= caret }.let { if (it < 0) 0 else it } }
        paintMatches(jump)
    }

    private fun paintMatches(select: Boolean) {
        clearHl()
        val e = ed.text
        for ((k, s) in matches.withIndex()) {
            if (k >= 4000 && k != matchAt) continue
            val end = minOf(e.length, s + matchLen)
            if (s < end) e.setSpan(EdHl(if (k == matchAt) hlCur() else hlSoft()), s, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (select && matchAt in matches.indices) {
            val s = matches[matchAt]
            ed.setSelection(s.coerceAtMost(e.length), minOf(e.length, s + matchLen))
            reveal(s)
        }
        showCount()
    }

    private fun showCount() {
        val c = findCount ?: return
        val q = findInput?.text?.toString() ?: ""
        if (q.isEmpty()) { c.text = ""; return }
        if (matches.isEmpty()) { c.text = L("Not found", "نہیں ملا"); c.setTextColor(p.warn); return }
        c.setTextColor(p.sub)
        c.text = if (T.ur) "${matchAt + 1} از ${matches.size}" else "${matchAt + 1} of ${matches.size}"
    }

    private fun stepFind(d: Int) {
        if (matches.isEmpty()) return
        matchAt = (matchAt + d + matches.size) % matches.size
        paintMatches(true)
    }

    private fun reveal(off: Int) {
        ed.post {
            val l = ed.layout ?: return@post
            val y = ed.totalPaddingTop + l.getLineTop(l.getLineForOffset(off.coerceIn(0, ed.text.length)))
            if (y < scroll.scrollY + dp(60) || y > scroll.scrollY + scroll.height - dp(120)) scroll.smoothScrollTo(0, maxOf(0, y - scroll.height / 3))
        }
    }

    private fun showGoto() {
        hideCards()
        val card = makeCard(); gotoCard = card
        val input = boxInput(L("Line 1–${ed.starts.size}", "لائن 1–${ed.starts.size}"))
        input.inputType = EditorInfo.TYPE_CLASS_NUMBER; input.textSize = 18f
        input.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        card.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(Ui.glyphButton(this, p, "×", 18f, 30, 32) { hideCards(); ed.requestFocus() })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val n = e?.toString()?.trim()?.toIntOrNull() ?: return
                val line = n.coerceIn(1, ed.starts.size)
                val off = ed.starts[line - 1].coerceAtMost(ed.text.length)
                ed.setSelection(off); reveal(off)
            }
        })
        input.setOnEditorActionListener { _, _, _ -> hideCards(); ed.requestFocus(); true }
        input.setOnKeyListener { _, k, e -> if ((k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) && e.action == KeyEvent.ACTION_DOWN) { hideCards(); ed.requestFocus(); true } else false }
        focusInput(input)
    }

    // ---------------------------------------------------------------- dialogs

    private fun cardDialog(content: View, widthDp: Int = 360): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(content)
        d.window?.setBackgroundDrawable(ColorDrawable(0))
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.window?.setDimAmount(0.25f)
        d.show(); Dir.apply(d)
        val w = minOf(dp(widthDp), (resources.displayMetrics.widthPixels * 0.94f).toInt())
        d.window?.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        return d
    }

    private fun filled(label: String, enabled: Boolean = true, click: () -> Unit): TextView {
        val t = Ui.text(this, label, 13f, 0xFFFFFFFF.toInt(), true, false)
        t.gravity = Gravity.CENTER; t.maxLines = 2; t.setPadding(dp(8), dp(4), dp(8), dp(4))
        t.background = Ui.ripple(this, p, Ui.rounded(if (p.dark) p.accentSoft else p.accent, dpf(12f)), dpf(12f))
        t.isClickable = true; t.alpha = if (enabled) 1f else 0.45f
        t.setOnClickListener { if (t.alpha >= 1f) click() }
        return t
    }

    /** A question with a few answer buttons ("Close without Saving", "Cancel", "Save As"). */
    private fun choice(title: String, msg: String, buttons: List<Pair<String, () -> Unit>>) {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
        box.background = Ui.rounded(p.card, dpf(22f), p.line, 1); box.setPadding(dp(20), dp(20), dp(20), dp(16))
        box.addView(Ui.text(this, title, 16f, p.text, true, false).atStart())
        box.addView(Ui.text(this, msg, 13f, p.sub, false, false).atStart(), LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(10) })
        val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
        var dlg: Dialog? = null
        for ((label, act) in buttons) row.addView(filled(label) { dlg?.dismiss(); act() }, LinearLayout.LayoutParams(0, dp(44), 1f).also { it.marginStart = dp(3); it.marginEnd = dp(3) })
        box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(18) })
        dlg = cardDialog(box, 420)
    }

    private class Chk(val row: LinearLayout, val get: () -> Boolean)

    private fun chk(label: String, init: Boolean, onChange: (Boolean) -> Unit = {}): Chk {
        var on = init
        val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL; row.isClickable = true
        row.background = Ui.ripple(this, p, null, dpf(8f)); row.setPadding(dp(4), dp(6), dp(4), dp(6))
        val box = TextView(this); box.gravity = Gravity.CENTER; box.textSize = 12f; box.setTextColor(0xFFFFFFFF.toInt())
        fun paint() { box.text = if (on) "✓" else ""; box.background = if (on) Ui.rounded(if (p.dark) p.accentSoft else p.accent, dpf(4f)) else Ui.rounded(0, dpf(4f), p.sub, dp(1)) }
        paint()
        row.addView(box, LinearLayout.LayoutParams(dp(18), dp(18)).also { it.marginEnd = dp(8) })
        row.addView(Ui.text(this, label, 13f, p.text, false, false))
        row.setOnClickListener { on = !on; paint(); onChange(on) }
        return Chk(row) { on }
    }

    private fun histKey(k: String) = "ed:$k"
    private fun remember(k: String, s: String) {
        if (s.isEmpty() || s.contains('\n')) return
        Store.setList(this, histKey(k), (listOf(s) + Store.list(this, histKey(k)).filter { it != s }).take(10))
    }

    private fun fieldRow(label: String, k: String, init: String): Pair<LinearLayout, EditText> {
        val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
        row.addView(Ui.text(this, label, 13f, p.text, false, false).also { it.minWidth = dp(74) }.atStart(), LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginEnd = dp(8) })
        val e = EditText(this); e.setSingleLine(); e.textSize = 14f; e.setTextColor(p.text); e.setText(init)
        e.background = Ui.rounded(p.bg, dpf(10f), p.line, 1); e.setPadding(dp(10), 0, dp(10), 0)
        e.layoutDirection = Dir.value(); e.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        e.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        row.addView(e, LinearLayout.LayoutParams(0, dp(40), 1f))
        val dd = Ui.glyphButton(this, p, "⌄", 16f, 34, 40) {
            val m = android.widget.PopupMenu(this, it)
            for (h in Store.list(this, histKey(k))) m.menu.add(h)
            m.setOnMenuItemClickListener { mi -> e.setText(mi.title); e.setSelection(e.text.length); true }
            m.show()
        }
        row.addView(dd)
        return row to e
    }

    private fun showReplace() {
        store()
        hideCards()
        val sel = if (ed.selectionStart != ed.selectionEnd) ed.text.substring(minOf(ed.selectionStart, ed.selectionEnd), maxOf(ed.selectionStart, ed.selectionEnd)) else ""
        val first = if (sel.isNotEmpty() && !sel.contains('\n') && sel.length < 300) sel else (Store.list(this, histKey("fh")).firstOrNull() ?: "")
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
        box.background = Ui.rounded(p.card, dpf(22f), p.line, 1); box.setPadding(dp(16), dp(18), dp(16), dp(16))
        val title = Ui.text(this, L("Find and Replace", "تلاش اور تبدیلی"), 16f, p.text, true, false); title.gravity = Gravity.CENTER
        box.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        val (fr, fe) = fieldRow(L("Find", "تلاش"), "fh", first)
        val (rr, re) = fieldRow(L("Replace with", "اس سے بدلیں"), "rh", "")
        box.addView(fr, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(14) })
        box.addView(rr, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(8) })
        val cCase = chk(L("Match case", "حروف کی قسم دیکھیں"), prefBool("f_case", false)) { Store.setBool(this, "ed:f_case", it) }
        val cBack = chk(L("Search Backwards", "پیچھے کی طرف تلاش"), false)
        val cWord = chk(L("Match entire word only", "صرف پورا لفظ"), prefBool("f_word", false)) { Store.setBool(this, "ed:f_word", it) }
        val cWrap = chk(L("Wrap around", "شروع سے دوبارہ"), prefBool("f_wrap", true)) { Store.setBool(this, "ed:f_wrap", it) }
        val cReg = chk(L("Regular expression", "ریگولر ایکسپریشن"), prefBool("f_reg", false)) { Store.setBool(this, "ed:f_reg", it) }
        val g1 = LinearLayout(this); g1.addView(cCase.row, LinearLayout.LayoutParams(0, WRAP, 1f)); g1.addView(cBack.row, LinearLayout.LayoutParams(0, WRAP, 1f))
        val g2 = LinearLayout(this); g2.addView(cWord.row, LinearLayout.LayoutParams(0, WRAP, 1f)); g2.addView(cWrap.row, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(g1, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(10) })
        box.addView(g2, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(cReg.row, LinearLayout.LayoutParams(MATCH, WRAP))

        fun pattern(): Pattern? {
            val q = fe.text.toString()
            if (q.isEmpty()) return null
            return try {
                var pat = if (cReg.get()) q else Pattern.quote(q)
                if (cWord.get()) pat = "(?<![\\p{L}\\p{N}_])(?:$pat)(?![\\p{L}\\p{N}_])"
                var fl = if (cReg.get()) Pattern.MULTILINE else 0
                if (!cCase.get()) fl = fl or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                Pattern.compile(pat, fl)
            } catch (e: PatternSyntaxException) { toastL("Invalid regular expression", "ریگولر ایکسپریشن درست نہیں"); null }
        }
        fun mark(s: Int, e: Int) {
            clearHl()
            val t = ed.text
            if (s < e && e <= t.length) t.setSpan(EdHl(hlCur()), s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ed.setSelection(s, e); reveal(s); lastFind = intArrayOf(s, e)
        }
        fun findNext(): Boolean {
            val pt = pattern() ?: return false
            remember("fh", fe.text.toString())
            val t = ed.text.toString()
            val m = pt.matcher(t)
            val a = minOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(0); val b = maxOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(0)
            var hit: IntArray? = null
            if (!cBack.get()) {
                var from = b
                while (from <= t.length && m.find(from)) { if (m.end() > m.start()) { hit = intArrayOf(m.start(), m.end()); break }; from = m.end() + 1 }
                if (hit == null && cWrap.get()) { var f2 = 0; while (f2 <= t.length && m.find(f2)) { if (m.end() > m.start()) { hit = intArrayOf(m.start(), m.end()); break }; f2 = m.end() + 1 } }
            } else {
                var last: IntArray? = null; var lastAny: IntArray? = null
                m.reset()
                while (m.find()) { if (m.end() > m.start()) { val r = intArrayOf(m.start(), m.end()); lastAny = r; if (r[0] < a) last = r } }
                hit = last ?: (if (cWrap.get()) lastAny else null)
            }
            if (hit == null) { toastL("Not found", "نہیں ملا"); return false }
            mark(hit[0], hit[1]); return true
        }
        fun replaceOne() {
            val pt = pattern() ?: return
            remember("rh", re.text.toString())
            val cur = lastFind
            val s = minOf(ed.selectionStart, ed.selectionEnd); val e = maxOf(ed.selectionStart, ed.selectionEnd)
            if (cur != null && cur[0] == s && cur[1] == e && e <= ed.text.length) {
                val t = ed.text.toString()
                val m = pt.matcher(t)
                if (m.find(s) && m.start() == s && m.end() == e) {
                    val sb = StringBuffer()
                    m.appendReplacement(sb, if (cReg.get()) re.text.toString() else Matcher.quoteReplacement(re.text.toString()))
                    val rep = sb.substring(s)
                    clearHl()
                    ed.text.replace(s, e, rep)
                    ed.setSelection(s + rep.length)
                }
            }
            findNext()
        }
        fun replaceAll() {
            val pt = pattern() ?: return
            remember("fh", fe.text.toString()); remember("rh", re.text.toString())
            val t = ed.text.toString()
            val m = pt.matcher(t)
            var n = 0
            while (m.find()) if (m.end() > m.start()) n++
            if (n == 0) { toastL("Not found", "نہیں ملا"); return }
            val out = pt.matcher(t).replaceAll(if (cReg.get()) re.text.toString() else Matcher.quoteReplacement(re.text.toString()))
            clearHl()
            val keep = ed.selectionStart.coerceAtLeast(0)
            ed.text.replace(0, ed.text.length, out)
            ed.setSelection(keep.coerceAtMost(ed.text.length))
            android.widget.Toast.makeText(applicationContext, if (T.ur) "$n مقامات پر تبدیل ہوا" else "$n replaced", android.widget.Toast.LENGTH_SHORT).show()
        }
        val brow = LinearLayout(this)
        val bAll = filled(L("Replace All", "سب بدلیں")) { replaceAll() }
        val bOne = filled(L("Replace", "بدلیں")) { replaceOne() }
        val bFind = filled(L("Find", "تلاش")) { findNext() }
        for (b in listOf(bAll, bOne, bFind)) brow.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).also { it.marginStart = dp(3); it.marginEnd = dp(3) })
        fun enable() { val on = fe.text.isNotEmpty(); for (b in listOf(bAll, bOne, bFind)) b.alpha = if (on) 1f else 0.45f }
        fe.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) { enable() }
        })
        enable()
        box.addView(brow, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(14) })
        fe.setOnEditorActionListener { _, _, _ -> findNext(); true }
        val d = cardDialog(box, 400)
        d.setOnDismissListener { clearHl() }
    }

    private fun showPrefs() {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
        box.background = Ui.rounded(p.card, dpf(22f), p.line, 1); box.setPadding(dp(18), dp(18), dp(18), dp(14))
        box.addView(Ui.text(this, L("Preference", "ترجیحات"), 16f, p.text, true, false).also { it.gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH, WRAP))
        val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
        row.addView(Ui.text(this, L("Font size", "لکھائی کا سائز"), 13f, p.text, false, false).atStart(), LinearLayout.LayoutParams(0, WRAP, 1f))
        val v = Ui.text(this, prefInt("size", 15).toString(), 14f, p.text, true, false); v.gravity = Gravity.CENTER
        row.addView(Ui.glyphButton(this, p, "−", 20f, 40, 40) { zoom(-1); v.text = prefInt("size", 15).toString() })
        row.addView(v, LinearLayout.LayoutParams(dp(36), WRAP))
        row.addView(Ui.glyphButton(this, p, "+", 20f, 40, 40) { zoom(1); v.text = prefInt("size", 15).toString() })
        box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(12) })
        box.addView(chk(L("Word wrap", "الفاظ لپیٹیں"), prefBool("wrap", true)) { Store.setBool(this, "ed:wrap", it); applyPrefs() }.row, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(6) })
        box.addView(chk(L("Line numbers", "لائن نمبر"), prefBool("nums", true)) { Store.setBool(this, "ed:nums", it); applyPrefs() }.row, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(chk(L("Monospace font", "برابر چوڑائی کی لکھائی"), prefBool("mono", false)) { Store.setBool(this, "ed:mono", it); applyPrefs() }.row, LinearLayout.LayoutParams(MATCH, WRAP))
        var dlg: Dialog? = null
        box.addView(filled(L("Close", "بند کریں")) { dlg?.dismiss() }, LinearLayout.LayoutParams(MATCH, dp(42)).also { it.topMargin = dp(12) })
        dlg = cardDialog(box, 340)
    }

    // ---------------------------------------------------------------- keyboard

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            val ctrl = e.isCtrlPressed; val shift = e.isShiftPressed
            when {
                ctrl && e.keyCode == KeyEvent.KEYCODE_S -> { if (shift) { store(); docs.getOrNull(cur)?.let { saveAs(it) } } else saveCurrent(null); return true }
                ctrl && e.keyCode == KeyEvent.KEYCODE_O -> { openDialog(); return true }
                ctrl && e.keyCode == KeyEvent.KEYCODE_N -> { newWindow(); return true }
                ctrl && e.keyCode == KeyEvent.KEYCODE_T -> { newDoc(); return true }
                ctrl && e.keyCode == KeyEvent.KEYCODE_W -> { closeDoc(cur); return true }
                ctrl && e.keyCode == KeyEvent.KEYCODE_F -> { showFind(); return true }
                ctrl && e.keyCode == KeyEvent.KEYCODE_H -> { showReplace(); return true }
                ctrl && e.keyCode == KeyEvent.KEYCODE_G -> { showGoto(); return true }
                ctrl && (e.keyCode == KeyEvent.KEYCODE_PLUS || e.keyCode == KeyEvent.KEYCODE_EQUALS || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ADD) -> { zoom(1); return true }
                ctrl && (e.keyCode == KeyEvent.KEYCODE_MINUS || e.keyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT) -> { zoom(-1); return true }
                ctrl && e.keyCode == KeyEvent.KEYCODE_0 -> { zoom(0); return true }
                ctrl && e.keyCode == KeyEvent.KEYCODE_TAB && docs.size > 1 -> { store(); switchTo(if (shift) (cur - 1 + docs.size) % docs.size else (cur + 1) % docs.size); return true }
                e.keyCode == KeyEvent.KEYCODE_ESCAPE && (findCard != null || gotoCard != null) -> { hideCards(); ed.requestFocus(); return true }
                e.keyCode == KeyEvent.KEYCODE_TAB && !ctrl && currentFocus === ed -> { insertText("\t"); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onPause() { super.onPause(); store() }
}
