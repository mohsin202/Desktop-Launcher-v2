package com.android.desktopfiles

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Names and icons for places (Quick access, sidebar). */
object Places {
    fun label(item: String): String = when {
        item == "sp:trash" -> "Trash"
        item == "sp:apps" -> "Apps"
        item == "sp:documents" -> "Documents"
        item == "sp:pictures" -> "Pictures"
        item == "sp:music" -> "Music"
        item == "sp:videos" -> "Videos"
        item.startsWith("dir:") -> File(item.substring(4)).let { if (it.absolutePath == Fs.ext().absolutePath) "Internal storage" else it.name }
        else -> item
    }

    fun glyph(item: String): Glyph = when {
        item == "sp:trash" -> Glyph(Glyph.Kind.BIN)
        item == "sp:apps" -> Glyph(Glyph.Kind.APPS)
        item == "sp:documents" -> Glyph(Glyph.Kind.DOCS)
        item == "sp:pictures" -> Glyph(Glyph.Kind.PICS)
        item == "sp:music" -> Glyph(Glyph.Kind.MUSIC)
        item == "sp:videos" -> Glyph(Glyph.Kind.VIDEOS)
        else -> when (File(item.removePrefix("dir:")).name) {
            "Desktop" -> Glyph(Glyph.Kind.DESKTOP)
            "Download", "Downloads" -> Glyph(Glyph.Kind.DOWNLOAD)
            "Documents" -> Glyph(Glyph.Kind.DOCS)
            "Pictures" -> Glyph(Glyph.Kind.PICS)
            "Music" -> Glyph(Glyph.Kind.MUSIC)
            "Movies" -> Glyph(Glyph.Kind.VIDEOS)
            else -> Glyph(Glyph.Kind.FOLDER)
        }
    }

    fun loc(item: String): Loc? = when {
        item == "sp:trash" -> Loc.Trash
        item.startsWith("sp:") -> Loc.Special(item.substring(3))
        item.startsWith("dir:") -> Loc.Dir(item.substring(4))
        else -> null
    }

    fun volGlyph(v: Vol): Glyph = Glyph(when (v.kind) {
        Vol.Kind.ROOT -> Glyph.Kind.ROOT
        Vol.Kind.INTERNAL -> Glyph.Kind.DRIVE
        Vol.Kind.USB -> Glyph.Kind.USB
        Vol.Kind.SD -> Glyph.Kind.USB
        Vol.Kind.PHONE -> Glyph.Kind.PHONE
    })
}

/** Home: Quick access, Drives, Recent files, and the network info bar. */
class HomePage(private val host: Host) : Page {
    private val c: Context = host.act
    private val p = Ui.pal(c)
    private val scroll = ScrollView(c)
    private val col = LinearLayout(c)
    override val view: View get() = scroll

    init {
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(c.dp(16), c.dp(8), c.dp(16), c.dp(24))
        scroll.addView(col, MATCH, WRAP)
        scroll.setBackgroundColor(0)
        if (RootSu.state == 0) RootSu.check { build() }
        build()
    }

    override fun refresh() = build()

    private fun section(title: String, key: String, body: () -> View) {
        val closed = Store.collapsed(c, key)
        val head = LinearLayout(c); head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(0, c.dp(14), 0, c.dp(8))
        head.isClickable = true
        head.addView(Ui.text(c, if (closed) "▸" else "▾", 14f, p.sub), LinearLayout.LayoutParams(c.dp(22), WRAP))
        head.addView(Ui.text(c, title, 14f, p.text, true))
        head.setOnClickListener { Store.setCollapsed(c, key, !closed); build() }
        col.addView(head)
        if (!closed) col.addView(body())
    }

    private fun card(icon: android.graphics.drawable.Drawable, name: String, w: Int, sub: View? = null, badge: Boolean = false, click: () -> Unit, long: ((View) -> Unit)? = null): View {
        val card = LinearLayout(c); card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(c.dp(12), c.dp(10), c.dp(12), c.dp(10))
        card.background = Ui.ripple(c, p, Ui.rounded(p.card, c.dpf(10f), p.line, 1), c.dpf(10f))
        card.isClickable = true
        card.setOnClickListener { click() }
        if (long != null) { card.setOnLongClickListener { long(it); true }; card.setOnContextClickListener { long(it); true } }
        val iconBox = android.widget.FrameLayout(c)
        val iv = ImageView(c); iv.setImageDrawable(icon)
        iconBox.addView(iv, android.widget.FrameLayout.LayoutParams(c.dp(36), c.dp(36)))
        if (badge) {
            val b = Ui.text(c, "!", 11f, 0xFFFFFFFF.toInt(), true); b.gravity = Gravity.CENTER
            b.background = Ui.rounded(0xFFE53935.toInt(), c.dpf(9f))
            iconBox.addView(b, android.widget.FrameLayout.LayoutParams(c.dp(18), c.dp(18), Gravity.BOTTOM or Gravity.END))
        }
        card.addView(iconBox, LinearLayout.LayoutParams(c.dp(40), c.dp(40)))
        val txt = LinearLayout(c); txt.orientation = LinearLayout.VERTICAL
        val n = Ui.text(c, name, 14f, p.text, true); n.maxLines = 1; n.ellipsize = android.text.TextUtils.TruncateAt.END
        txt.addView(n)
        if (sub != null) txt.addView(sub)
        card.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f).also { it.leftMargin = c.dp(10) })
        card.layoutParams = LinearLayout.LayoutParams(c.dp(w), WRAP)
        return card
    }

    private fun flow(): Flow {
        val f = Flow(c, c.dp(10)); return f
    }

    private fun build() {
        col.removeAllViews()
        val title = Ui.text(c, "Home", 22f, p.text, true); title.setPadding(0, c.dp(8), 0, 0)
        col.addView(title)

        if (!Store.bool(c, "hideNetBar", false)) {
            val bar = LinearLayout(c); bar.gravity = Gravity.CENTER_VERTICAL
            bar.setPadding(c.dp(12), c.dp(8), c.dp(8), c.dp(8))
            bar.background = Ui.rounded(p.accentSoft, c.dpf(10f))
            val t = Ui.text(c, "Network locations (SMB) will come in a later update.", 13f, p.text)
            bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            val b = Ui.text(c, "Disable", 13f, p.accent, true); b.setPadding(c.dp(12), c.dp(8), c.dp(12), c.dp(8))
            b.isClickable = true; b.background = Ui.ripple(c, p, null, c.dpf(8f))
            b.setOnClickListener { Store.setBool(c, "hideNetBar", true); build() }
            bar.addView(b)
            col.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = c.dp(10) })
        }

        section("Quick access", "qa") {
            val f = flow()
            for (item in Store.pins(c)) {
                val loc = Places.loc(item) ?: continue
                f.addView(card(Places.glyph(item), Places.label(item), 190, click = { host.go(loc) }, long = { v -> placeMenu(v, item, loc) }))
            }
            f
        }

        if (Tags.all().isNotEmpty()) section("Tags", "tags") {
            val f = flow()
            for (t in Tags.all()) f.addView(card(Tags.dot(c, t.color), t.name, 190, click = { host.go(Loc.Special("tag:" + t.id)) }))
            f
        }

        section("Drives", "drives") {
            val f = flow()
            for (v in Vols.list(c)) {
                if (v.kind == Vol.Kind.ROOT) {
                    val noRoot = RootSu.state == 2
                    val sub = Ui.text(c, if (noRoot) "Get root access for the root directory" else if (RootSu.state == 0) "Checking root access…" else "Root access available", 11f, p.sub)
                    f.addView(card(Places.volGlyph(v), "Root", 270, sub, noRoot, click = {
                        if (noRoot) c.toast("Get root access for the root directory") else host.go(Loc.Dir("/"))
                    }))
                } else {
                    val box = LinearLayout(c); box.orientation = LinearLayout.VERTICAL
                    val frac = if (v.total > 0) (v.total - v.free).toFloat() / v.total else 0f
                    val bar = UsageBar(c, p, frac)
                    box.addView(bar, LinearLayout.LayoutParams(MATCH, c.dp(6)).also { it.topMargin = c.dp(6); it.bottomMargin = c.dp(4) })
                    box.addView(Ui.text(c, Fs.size(c, v.free) + " free of " + Fs.size(c, v.total), 11f, p.sub))
                    val d = v.dir
                    f.addView(card(Places.volGlyph(v), v.name, 270, box, click = { if (d != null) host.go(Loc.Dir(d.absolutePath)) }))
                }
            }
            for (ph in Mtp.list(c)) {
                val sub = Ui.text(c, "USB phone (MTP)", 11f, p.sub)
                f.addView(card(Glyph(Glyph.Kind.PHONE), Mtp.label(ph), 270, sub, click = { host.openPhone(ph) }))
            }
            f
        }

        section("Recent files", "recent") {
            val box = LinearLayout(c); box.orientation = LinearLayout.VERTICAL
            val files = Store.list(c, "recentfiles").map { File(it) }.filter { it.exists() }.take(12)
            if (files.isEmpty()) box.addView(Ui.text(c, "Files you open will appear here.", 13f, p.sub))
            for (f in files) {
                val row = LinearLayout(c); row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(c.dp(8), c.dp(6), c.dp(8), c.dp(6)); row.isClickable = true
                row.background = Ui.ripple(c, p, null, c.dpf(8f))
                val iv = ImageView(c); Thumbs.bind(iv, f, c.dp(64))
                row.addView(iv, LinearLayout.LayoutParams(c.dp(32), c.dp(32)))
                val nm = Ui.text(c, Fs.shown(f), 14f, p.text, false, false); nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.END
                row.addView(nm, LinearLayout.LayoutParams(0, WRAP, 1f).also { it.leftMargin = c.dp(10) })
                val loc = Ui.text(c, f.parent ?: "", 11f, p.sub); loc.maxLines = 1; loc.ellipsize = android.text.TextUtils.TruncateAt.START
                row.addView(loc, LinearLayout.LayoutParams(c.dp(220), WRAP))
                row.setOnClickListener { host.openFile(f) }
                row.setOnContextClickListener { _ -> f.parentFile?.let { host.go(Loc.Dir(it.absolutePath)) }; true }
                row.setOnLongClickListener { f.parentFile?.let { pf -> host.go(Loc.Dir(pf.absolutePath)) }; true }
                box.addView(row)
            }
            box
        }
    }

    private fun placeMenu(v: View, item: String, loc: Loc) {
        val loc2 = IntArray(2); v.getLocationOnScreen(loc2)
        Pop(c).showAt(v, loc2[0] + v.width / 2, loc2[1] + v.height / 2, listOf(
            MenuItem("Open") { host.go(loc) },
            MenuItem("Open in new tab") { host.newTab(loc) },
            MenuItem("Open in new window") { host.newWindow(loc) },
            null,
            MenuItem("Unpin from Quick access") { Store.unpin(c, item); host.pinsChanged() }
        ))
    }
}
