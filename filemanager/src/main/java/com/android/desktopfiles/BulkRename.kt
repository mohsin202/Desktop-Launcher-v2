package com.android.desktopfiles

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Renaming two or more files/folders at once, as "Rename" does when more than one item is selected (the toolbar icon and the
 * right-click menu both lead here; a single item keeps the simple one-line dialog in [Dialogs.ask]).
 *
 * Two modes, switched with the radio buttons:
 *  - "Rename using a template": one text field, starting with the [Original file name] token. The "+Add" button inserts that
 *    token at the cursor; {n} / {n:000} add a counter (padded to 3 digits in the second form), {ext} the file's extension.
 *  - "Find and replace text": every occurrence of "Existing Text" in the current name is swapped for "Replace With".
 *
 * Either way a live preview lists every old name next to its new one before anything on disk changes.
 */
object BulkRename {
    private const val TOKEN_NAME = "[Original file name]"

    fun show(act: Activity, files: List<File>, after: () -> Unit) {
        if (files.size < 2) return
        val p = Ui.pal(act)
        val sv = ScrollView(act)
        val root = LinearLayout(act); root.orientation = LinearLayout.VERTICAL
        root.setPaddingRelative(act.dp(20), act.dp(16), act.dp(20), act.dp(4))
        sv.addView(root)

        val group = RadioGroup(act); group.orientation = LinearLayout.VERTICAL
        val rTemplate = RadioButton(act); rTemplate.text = T.t("Rename using a template"); rTemplate.id = View.generateViewId()
        val rFind = RadioButton(act); rFind.text = T.t("Find and replace text"); rFind.id = View.generateViewId()
        group.addView(rTemplate, LinearLayout.LayoutParams(-1, -2))
        group.addView(rFind, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = act.dp(4) })
        root.addView(group)

        // -- template mode --
        val templateBox = LinearLayout(act); templateBox.orientation = LinearLayout.VERTICAL
        templateBox.setPaddingRelative(0, act.dp(10), 0, 0)
        val templateRow = LinearLayout(act); templateRow.gravity = Gravity.CENTER_VERTICAL
        val templateEdit = EditText(act); templateEdit.setText(TOKEN_NAME); templateEdit.setSingleLine()
        templateEdit.setSelection(templateEdit.text.length)
        templateRow.addView(templateEdit, LinearLayout.LayoutParams(0, -2, 1f))
        val addBtn = TextView(act); addBtn.text = "+" + T.t("Add")
        addBtn.setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(6))
        addBtn.background = Ui.rounded(p.accentSoft, act.dpf(16f)); addBtn.setTextColor(p.accent)
        addBtn.isClickable = true; addBtn.background = Ui.ripple(act, p, Ui.rounded(p.accentSoft, act.dpf(16f)), act.dpf(16f))
        addBtn.setOnClickListener {
            val pos = templateEdit.selectionStart.coerceIn(0, templateEdit.text.length)
            templateEdit.text.insert(pos, TOKEN_NAME)
        }
        templateRow.addView(addBtn, LinearLayout.LayoutParams(-2, -2).also { it.marginStart = act.dp(8) })
        templateBox.addView(templateRow)
        templateBox.addView(Ui.text(act, "{n} / {n:000} = " + T.t("a counter") + ", {ext} = " + T.t("extension"), 11f, p.sub)
            .also { it.setPaddingRelative(0, act.dp(6), 0, 0) })
        root.addView(templateBox)

        // -- find & replace mode --
        val findBox = LinearLayout(act); findBox.orientation = LinearLayout.VERTICAL; findBox.visibility = View.GONE
        findBox.setPaddingRelative(0, act.dp(10), 0, 0)
        fun field(label: String): EditText {
            findBox.addView(Ui.text(act, label, 12f, p.sub).also { it.setPaddingRelative(0, act.dp(8), 0, act.dp(2)) })
            val e = EditText(act); e.setSingleLine()
            findBox.addView(e, LinearLayout.LayoutParams(-1, -2))
            return e
        }
        val findEdit = field("Existing Text:")
        val replaceEdit = field("Replace With:")
        root.addView(findBox)

        // -- preview --
        root.addView(Ui.text(act, "Preview", 12f, p.sub, true).also { it.setPaddingRelative(0, act.dp(16), 0, act.dp(4)) })
        val previewBox = LinearLayout(act); previewBox.orientation = LinearLayout.VERTICAL
        previewBox.background = Ui.rounded(p.card, act.dpf(10f), p.line, 1)
        previewBox.setPaddingRelative(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
        root.addView(previewBox, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = act.dp(6) })

        fun newNames(): List<Pair<File, String>> {
            if (rFind.isChecked) {
                val find = findEdit.text.toString(); val rep = replaceEdit.text.toString()
                return files.map { f -> f to (if (find.isEmpty()) f.name else f.name.replace(find, rep)) }
            }
            val tpl = templateEdit.text.toString()
            return files.mapIndexed { i, f ->
                val base = f.name.substringBeforeLast('.', f.name)
                val ext = f.name.substringAfterLast('.', "")
                var name = tpl.replace(TOKEN_NAME, base)
                name = Regex("\\{n(?::(0+))?\\}").replace(name) { m ->
                    val pad = m.groupValues[1].length
                    val num = (i + 1).toString()
                    if (pad > 0) num.padStart(pad, '0') else num
                }
                name = name.replace("{ext}", ext)
                if (f.isFile && ext.isNotEmpty() && !name.endsWith(".$ext")) name = "$name.$ext"
                f to name
            }
        }

        fun refreshPreview() {
            previewBox.removeAllViews()
            val list = newNames()
            for ((f, n) in list.take(60)) {
                val row = LinearLayout(act); row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(0, act.dp(3), 0, act.dp(3))
                val a = Ui.text(act, f.name, 12f, p.sub, false, false)
                a.maxLines = 1; a.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                row.addView(a, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(Ui.text(act, "  →  ", 12f, p.sub))
                val changed = n.isNotBlank() && n != f.name
                val b = Ui.text(act, if (n.isBlank()) f.name else n, 12f, if (changed) p.accent else p.sub, false, false)
                b.maxLines = 1; b.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                row.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
                previewBox.addView(row)
            }
            if (list.size > 60) previewBox.addView(Ui.text(act, "+" + (list.size - 60), 11f, p.sub))
        }

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refreshPreview() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        templateEdit.addTextChangedListener(watcher)
        findEdit.addTextChangedListener(watcher)
        replaceEdit.addTextChangedListener(watcher)
        group.setOnCheckedChangeListener { _, id ->
            templateBox.visibility = if (id == rTemplate.id) View.VISIBLE else View.GONE
            findBox.visibility = if (id == rFind.id) View.VISIBLE else View.GONE
            refreshPreview()
        }
        group.check(rTemplate.id)
        refreshPreview()
        root.layoutDirection = Dir.value()

        val d = AlertDialog.Builder(act)
            .setTitle(T.t("Rename") + " (" + files.size + ")")
            .setView(DeskScroll.wrap(sv, p))
            .setPositiveButton(T.t("Rename")) { _, _ ->
                val pairs = newNames().filter { it.second.isNotBlank() && it.second != it.first.name }
                if (pairs.isEmpty()) { act.toast("No changes"); return@setPositiveButton }
                val names = HashSet<String>()
                val clashing = pairs.any { !names.add(it.second) }
                if (clashing) { act.toast("Two of the new names are the same"); return@setPositiveButton }
                Jobs.start(Job("Renaming " + pairs.size + " items"), { j ->
                    j.total = pairs.size.toLong()
                    for ((f, n) in pairs) {
                        if (j.cancelled) break
                        j.detail = f.name
                        val t = File(f.parentFile, n)
                        if (RootSu.exists(t)) j.failed++
                        else if (f.renameTo(t) || (RootSu.ensure() && RootSu.sh("mv " + RootSu.q(f) + " " + RootSu.q(t)))) Tags.move(f.absolutePath, t.absolutePath)
                        else j.failed++
                        j.done++
                    }
                }) { after() }
            }
            .setNegativeButton(T.t("Cancel"), null).showDir()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
    }
}
