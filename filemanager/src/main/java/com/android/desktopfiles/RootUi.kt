package com.android.desktopfiles

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Permissions dialog (chmod / chown through su) for files in system areas. */
object RootUi {
    fun permissions(act: Activity, f: File, done: () -> Unit) {
        Thread {
            val pm = if (RootSu.ensure()) RootSu.permOf(f.absolutePath) else null
            act.runOnUiThread {
                if (pm == null) { act.toast("Could not read the permissions"); return@runOnUiThread }
                show(act, f, pm, done)
            }
        }.start()
    }

    private fun show(act: Activity, f: File, pm: RootSu.Perm, done: () -> Unit) {
        val p = Ui.pal(act)
        val special = if (pm.mode.length > 3) pm.mode.substring(0, pm.mode.length - 3) else ""
        val digits = pm.mode.takeLast(3).padStart(3, '0')
        val box = LinearLayout(act); box.orientation = LinearLayout.VERTICAL; box.setPaddingRelative(act.dp(20), act.dp(8), act.dp(20), act.dp(4))
        val grid = Array(3) { arrayOfNulls<CheckBox>(3) }
        val head = LinearLayout(act)
        head.addView(TextView(act), LinearLayout.LayoutParams(act.dp(80), WRAP))
        for (t in listOf("Read", "Write", "Execute")) head.addView(TextView(act).also { it.text = T.t(t); it.textSize = 12f; it.gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(head)
        for ((r, who) in listOf("Owner", "Group", "Others").withIndex()) {
            val row = LinearLayout(act); row.gravity = Gravity.CENTER_VERTICAL
            row.addView(TextView(act).also { it.text = T.t(who) }, LinearLayout.LayoutParams(act.dp(80), WRAP))
            val d = digits[r] - '0'
            for (cIdx in 0..2) {
                val cb = CheckBox(act); cb.isChecked = d and (4 shr cIdx) != 0; grid[r][cIdx] = cb
                row.addView(cb, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            box.addView(row)
        }
        val owner = EditText(act); owner.setText(pm.owner); owner.setSingleLine(); owner.hint = T.t("Owner")
        val group = EditText(act); group.setText(pm.group); group.setSingleLine(); group.hint = T.t("Group")
        val og = LinearLayout(act)
        og.addView(owner, LinearLayout.LayoutParams(0, WRAP, 1f)); og.addView(group, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(og)
        val rec = CheckBox(act); rec.text = T.t("Apply to everything inside")
        if (f.isDirectory) box.addView(rec)
        val d = AlertDialog.Builder(act).setTitle(T.t("Permissions: " + f.name)).setView(ScrollView(act).also { it.addView(box) })
            .setPositiveButton(T.t("Apply")) { _, _ ->
                var mode = special
                for (r in 0..2) mode += (0..2).sumOf { if (grid[r][it]!!.isChecked) 4 shr it else 0 }
                val o = owner.text.toString().trim(); val g = group.text.toString().trim()
                val recurse = rec.isChecked
                Thread {
                    val q = RootSu.q(f)
                    val r = if (recurse) "-R " else ""
                    var ok = RootSu.sh("chmod $r$mode $q")
                    if (ok && (o != pm.owner || g != pm.group) && o.isNotEmpty() && g.isNotEmpty()) ok = RootSu.sh("chown $r$o:$g $q")
                    act.runOnUiThread { act.toast(if (ok) "Permissions changed" else "Could not change the permissions"); done() }
                }.start()
            }.setNegativeButton(T.t("Cancel"), null).create()
        d.show()
    }
}
