package com.android.desktopfiles

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import java.io.File

/** Coloured tags on files and folders. The tags and which path has which tag are kept in the app settings. */
object Tags {
    class Tag(val id: String, val name: String, val color: Int)

    val palette = intArrayOf(0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
        0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt(), 0xFF757575.toInt())

    private var app: Context? = null
    private var defs: List<Tag>? = null
    private var map: HashMap<String, LinkedHashSet<String>>? = null

    fun init(c: Context) { if (app == null) app = c.applicationContext }

    @Synchronized fun all(): List<Tag> {
        defs?.let { return it }
        val c = app ?: return emptyList()
        val l = Store.list(c, "tags").mapNotNull {
            val a = it.split("|", limit = 3)
            if (a.size == 3) Tag(a[0], a[2], a[1].toLongOrNull()?.toInt() ?: palette[7]) else null
        }
        defs = l; return l
    }

    fun byId(id: String): Tag? = all().firstOrNull { it.id == id }

    @Synchronized private fun saveDefs(l: List<Tag>) {
        val c = app ?: return
        Store.setList(c, "tags", l.map { it.id + "|" + it.color + "|" + it.name })
        defs = l
    }

    @Synchronized fun add(name: String, color: Int): Tag {
        val t = Tag(System.currentTimeMillis().toString(36), name.replace("|", " ").replace("\n", " ").trim(), color)
        saveDefs(all() + t); return t
    }

    @Synchronized fun rename(id: String, name: String) {
        saveDefs(all().map { if (it.id == id) Tag(id, name.replace("|", " ").replace("\n", " ").trim(), it.color) else it })
    }

    @Synchronized fun remove(id: String) {
        saveDefs(all().filter { it.id != id })
        val m = load(); for (v in m.values) v.remove(id); save()
    }

    @Synchronized private fun load(): HashMap<String, LinkedHashSet<String>> {
        map?.let { return it }
        val m = HashMap<String, LinkedHashSet<String>>()
        val c = app
        if (c != null) for (l in Store.list(c, "tagmap")) {
            val i = l.lastIndexOf('\t'); if (i <= 0) continue
            m[l.substring(0, i)] = LinkedHashSet(l.substring(i + 1).split(",").filter { it.isNotEmpty() })
        }
        map = m; return m
    }

    @Synchronized private fun save() {
        val c = app ?: return
        Store.setList(c, "tagmap", load().filter { it.value.isNotEmpty() }.map { it.key + "\t" + it.value.joinToString(",") })
    }

    @Synchronized fun of(path: String): List<Tag> {
        val ids = load()[path] ?: return emptyList()
        return all().filter { it.id in ids }
    }

    /** Adds the tag to all of [paths], or takes it off when they all have it already. */
    @Synchronized fun toggle(paths: List<String>, id: String) {
        val m = load()
        val allHave = paths.all { m[it]?.contains(id) == true }
        for (p in paths) { if (allHave) m[p]?.remove(id) else m.getOrPut(p) { LinkedHashSet() }.add(id) }
        save()
    }

    /** A file or folder was renamed or moved: its tags (and those of everything inside) follow it. */
    @Synchronized fun move(from: String, to: String) {
        val m = load()
        val keys = m.keys.filter { it == from || it.startsWith("$from/") }
        if (keys.isEmpty()) return
        for (k in keys) { val v = m.remove(k) ?: continue; m[to + k.substring(from.length)] = v }
        save()
    }

    @Synchronized fun files(id: String): List<File> = load().filter { id in it.value.orEmpty() }.keys.map { File(it) }.filter { it.exists() }

    /** [name] followed by one coloured dot for each tag of the path. */
    fun decorate(name: String, path: String): CharSequence {
        val t = of(path)
        if (t.isEmpty()) return name
        val sb = SpannableStringBuilder(name)
        for (x in t) { val s = sb.length; sb.append("  ●"); sb.setSpan(ForegroundColorSpan(x.color), s + 2, s + 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return sb
    }

    fun dot(c: Context, color: Int) = GradientDrawable().also { it.shape = GradientDrawable.OVAL; it.setColor(color); it.setSize(c.dp(14), c.dp(14)) }
}

object TagUi {
    /** Asks for a name and a colour, makes the tag and hands it back. */
    fun create(act: Activity, done: (Tags.Tag) -> Unit) {
        var chosen = Tags.palette[4]
        val box = LinearLayout(act); box.orientation = LinearLayout.VERTICAL; box.setPadding(act.dp(20), act.dp(8), act.dp(20), 0)
        val et = EditText(act); et.hint = T.t("Tag name"); et.setSingleLine(); box.addView(et)
        val row = LinearLayout(act); row.gravity = Gravity.CENTER_VERTICAL; row.setPadding(0, act.dp(12), 0, act.dp(4))
        val dots = ArrayList<View>()
        fun paint() { for ((i, v) in dots.withIndex()) v.alpha = if (Tags.palette[i] == chosen) 1f else 0.35f }
        for (col in Tags.palette) {
            val v = View(act); v.background = Tags.dot(act, col).also { it.setSize(act.dp(28), act.dp(28)) }
            v.setOnClickListener { chosen = col; paint() }
            dots.add(v); row.addView(v, LinearLayout.LayoutParams(act.dp(28), act.dp(28)).also { it.rightMargin = act.dp(10) })
        }
        paint()
        val hs = HorizontalScrollView(act); hs.addView(row); box.addView(hs)
        AlertDialog.Builder(act).setTitle(T.t("New tag")).setView(box)
            .setPositiveButton(T.t("Create")) { _, _ -> val n = et.text.toString().trim(); if (n.isNotEmpty()) done(Tags.add(n, chosen)) }
            .setNegativeButton(T.t("Cancel"), null).show()
    }
}
