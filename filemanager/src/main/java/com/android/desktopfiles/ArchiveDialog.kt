package com.android.desktopfiles

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** What the Create archive window decides. */
class ArchSpec(var name: String, var dest: File) {
    var format = "zip"
    var level = 2
    var enc = 0
    var password = ""
    var splitMb = 0L
    var delete = false
    var separate = false
}

object ArchiveDialog {
    private val SPLITS = listOf(0L, 5L, 10L, 24L, 50L, 100L)

    /** Remembered between uses. */
    fun load(c: android.content.Context, s: ArchSpec) {
        s.format = Store.str(c, "arc_fmt", "zip").takeIf { it in Archiver.FORMATS } ?: "zip"
        s.level = Store.str(c, "arc_lvl", "2").toIntOrNull()?.coerceIn(0, 5) ?: 2
    }

    private fun save(c: android.content.Context, s: ArchSpec) { Store.setStr(c, "arc_fmt", s.format); Store.setStr(c, "arc_lvl", s.level.toString()) }

    private fun withExt(name: String, fmt: String): String {
        var b = name
        for (f in Archiver.FORMATS.sortedByDescending { it.length }) if (b.lowercase().endsWith(".$f")) { b = b.dropLast(f.length + 1); break }
        return b.ifEmpty { "Archive" } + "." + fmt
    }

    /** Shows the window; [ok] gets the spec when the person presses OK (after the checks). [others] are places the "…" button offers to save in. */
    fun show(act: Activity, spec: ArchSpec, others: List<Pair<String, File>>, ok: (ArchSpec) -> Unit) {
        val p = Ui.pal(act)
        spec.name = withExt(spec.name, spec.format)
        val sv = ScrollView(act)
        val root = LinearLayout(act); root.orientation = LinearLayout.VERTICAL; root.setPadding(act.dp(20), act.dp(16), act.dp(20), act.dp(4)); sv.addView(root)

        // title and the ⋮ menu
        val head = LinearLayout(act); head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Ui.text(act, "Create archive", 20f, p.accent, true), LinearLayout.LayoutParams(0, -2, 1f))
        val more = Ui.text(act, "⋮", 22f, p.text, true); more.gravity = Gravity.CENTER; more.isClickable = true
        more.background = Ui.ripple(act, p, null, act.dpf(8f))
        more.setOnClickListener { Pop(act).showBelow(more, listOf(MenuItem("7z archive solid mode", enabled = false, checked = true))) }
        head.addView(more, LinearLayout.LayoutParams(act.dp(40), act.dp(40)))
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        fun label(s: String) = Ui.text(act, s, 13f, p.sub).atStart().also { it.setPadding(0, act.dp(10), 0, act.dp(3)) }

        // name and place
        root.addView(label("Archive:"))
        val nameRow = LinearLayout(act); nameRow.gravity = Gravity.CENTER_VERTICAL
        val nameBox = EditText(act); nameBox.setText(spec.name); nameBox.setSingleLine(true); nameBox.inputType = InputType.TYPE_CLASS_TEXT
        nameBox.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        nameRow.addView(nameBox, LinearLayout.LayoutParams(0, -2, 1f))
        val where = Ui.text(act, "", 12f, p.sub, false, false).atStart()
        fun showWhere() { where.text = T.t("Save in:") + " " + spec.dest.absolutePath }
        showWhere()
        val dots = Ui.text(act, "…", 18f, p.text, true); dots.gravity = Gravity.CENTER; dots.isClickable = true
        dots.background = Ui.rounded(p.line, act.dpf(8f))
        dots.setOnClickListener {
            val items = ArrayList<MenuItem?>()
            for ((l, f) in others) items += MenuItem(l, checked = f == spec.dest) { spec.dest = f; showWhere() }
            Pop(act).showBelow(dots, items)
        }
        nameRow.addView(dots, LinearLayout.LayoutParams(act.dp(44), act.dp(40)).also { it.marginStart = act.dp(8) })
        root.addView(nameRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(where, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = act.dp(3) })

        // a drop-down line: small label, the choice and an arrow; opens our own menu
        fun dropdown(title: String, current: () -> String, items: () -> List<MenuItem?>, enabled: () -> Boolean = { true }): Pair<View, () -> Unit> {
            val box = LinearLayout(act); box.orientation = LinearLayout.VERTICAL
            box.addView(label(title))
            val line = LinearLayout(act); line.gravity = Gravity.CENTER_VERTICAL; line.setPadding(act.dp(10), act.dp(9), act.dp(10), act.dp(9))
            line.background = Ui.rounded(0, act.dpf(10f), p.line, act.dp(1)); line.isClickable = true
            val t = Ui.text(act, current(), 15f, p.text, false, false).atStart()
            line.addView(t, LinearLayout.LayoutParams(0, -2, 1f)); line.addView(Ui.text(act, "▾", 14f, p.sub, false, false))
            line.setOnClickListener { if (enabled()) Pop(act).showBelow(line, items()) }
            box.addView(line, LinearLayout.LayoutParams(-1, -2))
            val refresh = { t.text = T.t(current()); line.alpha = if (enabled()) 1f else 0.45f }
            refresh()
            return box to refresh
        }

        var refreshAll: () -> Unit = {}
        val fmt = dropdown("Archive format:", { spec.format }, {
            Archiver.FORMATS.map { f -> MenuItem(f, checked = spec.format == f) {
                spec.format = f; if (f != "zip") spec.enc = 0
                nameBox.setText(withExt(nameBox.text.toString(), f)); refreshAll()
            } }
        })
        val lvl = dropdown("Compression level:", { Archiver.LEVELS[spec.level] }, {
            Archiver.LEVELS.mapIndexed { i, l -> MenuItem(l, checked = spec.level == i) { spec.level = i; refreshAll() } }
        })
        val two = LinearLayout(act)
        two.addView(fmt.first, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = act.dp(8) })
        two.addView(lvl.first, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(two, LinearLayout.LayoutParams(-1, -2))

        val enc = dropdown("Encryption:", { if (spec.format == "zip") Archiver.ENCRYPTION[spec.enc] else "Only for ZIP" }, {
            Archiver.ENCRYPTION.mapIndexed { i, l -> MenuItem(l, checked = spec.enc == i) { spec.enc = i; refreshAll() } }
        }, { spec.format == "zip" })
        root.addView(enc.first, LinearLayout.LayoutParams(-1, -2))

        // password with an eye and a generator
        root.addView(label("Password:"))
        val pwRow = LinearLayout(act); pwRow.gravity = Gravity.CENTER_VERTICAL
        val pwBox = EditText(act); pwBox.hint = T.t("Enter password"); pwBox.setSingleLine(true)
        pwBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        pwBox.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        pwRow.addView(pwBox, LinearLayout.LayoutParams(0, -2, 1f))
        var shown = false
        val eye = Ui.text(act, "👁", 18f, p.accent, true, false); eye.gravity = Gravity.CENTER; eye.isClickable = true
        eye.setOnClickListener {
            shown = !shown
            val keep = pwBox.text.length
            pwBox.inputType = InputType.TYPE_CLASS_TEXT or (if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            pwBox.setSelection(keep)
        }
        pwRow.addView(eye, LinearLayout.LayoutParams(act.dp(40), act.dp(40)).also { it.marginStart = act.dp(4) })
        val gen = Ui.text(act, "…", 18f, p.text, true); gen.gravity = Gravity.CENTER; gen.isClickable = true
        gen.background = Ui.rounded(p.line, act.dpf(8f))
        gen.setOnClickListener {
            Pop(act).showBelow(gen, listOf(MenuItem("Generate a password") {
                val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
                val r = java.security.SecureRandom()
                pwBox.setText((1..16).map { chars[r.nextInt(chars.length)] }.joinToString(""))
                if (!shown) eye.performClick()
            }))
        }
        pwRow.addView(gen, LinearLayout.LayoutParams(act.dp(44), act.dp(40)).also { it.marginStart = act.dp(4) })
        root.addView(pwRow, LinearLayout.LayoutParams(-1, -2))

        val split = dropdown("Split into volumes:", { if (spec.splitMb == 0L) "No" else spec.splitMb.toString() + "MB" }, {
            val l = ArrayList<MenuItem?>()
            for (s in SPLITS) l += MenuItem(if (s == 0L) "No" else s.toString() + "MB", checked = spec.splitMb == s) { spec.splitMb = s; refreshAll() }
            l += MenuItem("Other size…") {
                Dialogs.ask(act, "Split size in MB", if (spec.splitMb > 0) spec.splitMb.toString() else "100", "OK") { v ->
                    val n = v.trim().toLongOrNull()
                    if (n != null && n > 0) { spec.splitMb = n; refreshAll() } else act.toast("Enter a number")
                }
            }
            l
        })
        root.addView(split.first, LinearLayout.LayoutParams(-1, -2))

        fun check(text: String, on: Boolean, set: (Boolean) -> Unit): CheckBox {
            val cb = CheckBox(act); cb.text = T.t(text); cb.setTextColor(p.text); cb.textSize = 14f; cb.isChecked = on
            cb.buttonTintList = android.content.res.ColorStateList.valueOf(p.accent)
            cb.setOnCheckedChangeListener { _, v -> set(v) }
            root.addView(cb, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = act.dp(6) })
            return cb
        }
        check("Delete source files after compression", spec.delete) { spec.delete = it }
        check("Create separate archive", spec.separate) { spec.separate = it }

        refreshAll = {
            fmt.second(); lvl.second(); enc.second(); split.second()
            val needPw = spec.format == "zip" && spec.enc != 0
            pwBox.isEnabled = needPw; pwBox.alpha = if (needPw) 1f else 0.45f; eye.alpha = pwBox.alpha; gen.alpha = pwBox.alpha
        }
        refreshAll()

        val dlg = AlertDialog.Builder(act).setView(sv)
            .setPositiveButton(T.t("OK"), null).setNegativeButton(T.t("Cancel"), null).showDir()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = nameBox.text.toString().trim().replace('/', '_')
            if (n.isEmpty()) { act.toast("Enter a name"); return@setOnClickListener }
            spec.name = withExt(n, spec.format)
            spec.password = pwBox.text.toString()
            if (spec.format == "zip" && spec.enc != 0 && spec.password.isEmpty()) { act.toast("Enter a password"); return@setOnClickListener }
            if (spec.format != "zip") spec.enc = 0
            save(act, spec)
            dlg.dismiss()
            ok(spec)
        }
    }

    /** Makes the archive(s) as a job: the Status centre shows the line and the file being packed. */
    fun run(host: Host, files: List<File>, s: ArchSpec, then: () -> Unit) {
        if (files.isEmpty()) return
        val c = host.act
        val units: List<Pair<List<File>, String>> =
            if (s.separate) files.map { listOf(it) to withExt(if (it.isDirectory) it.name else it.nameWithoutExtension.ifEmpty { it.name }, s.format) }
            else listOf(files to withExt(s.name, s.format))
        val job = Job("Compressing " + files.size + (if (files.size == 1) " item to " else " items to ") + units[0].second + (if (units.size > 1) " …" else ""))
        val tmp = File(Fs.ext(), ".deskphone/arc")
        val o = Archiver.Opts(s.format, s.level, s.enc, s.password)
        Jobs.start(job, { j ->
            s.dest.mkdirs()
            j.total = Archiver.totalSize(files)
            for ((src, nm) in units) {
                if (j.cancelled) break
                val out = Fs.unique(s.dest, nm)
                try {
                    Archiver.create(src, out, o, j, tmp)
                    if (!j.cancelled && s.splitMb > 0) Archiver.split(out, s.splitMb * 1024 * 1024, j)
                } catch (e: Throwable) { j.failed++; j.detail = e.message ?: e.javaClass.simpleName; out.delete() }
            }
            tmp.deleteRecursively()
        }) {
            if (s.delete && job.failed == 0 && !job.cancelled) files.forEach { it.deleteRecursively() }
            then()
        }
        host.showStatus()
    }
}
