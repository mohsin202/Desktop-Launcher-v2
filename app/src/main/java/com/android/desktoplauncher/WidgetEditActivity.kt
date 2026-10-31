package com.android.desktoplauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Edit page of the special clock: a small picture of the whole desktop with the clock at the same place and
 * relative size, a Save button, and the sections Size (weight), Style and Seconds. Everything shows live in the picture.
 */
class WidgetEditActivity : Activity() {
    private var wid = 0
    private lateinit var cfg: ClockCfg
    private lateinit var clock: SpecialClockView
    private lateinit var root: LinearLayout
    private var swatches: LinearLayout? = null
    private var alphaBox: LinearLayout? = null
    private val radios = ArrayList<RadioButton>()

    private fun t(s: String) = UiText.text(this, s)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setDecorFitsSystemWindows(false)
        wid = intent.getIntExtra("wid", 0)
        cfg = ClockCfg.load(this, wid)
        val sv = ScrollView(this)
        sv.setBackgroundColor(Colors.surfaceSolid(this))
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(20), dp(20), dp(30))
        sv.addView(root)
        UiText.applyDirection(sv, this)
        setContentView(sv)
        sv.setOnApplyWindowInsetsListener { v, ins ->
            val bars = Insets.of(ins)
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            ins
        }
        build()
    }

    private fun card(title: String): LinearLayout {
        val h = TextView(this)
        h.text = t(title)
        h.textSize = 15f
        h.setTextColor(Colors.accent(this))
        h.setPadding(dp(6), dp(10), dp(6), dp(6))
        root.addView(h)
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(12), dp(18), dp(12))
        c.background = Colors.rounded(Colors.surfaceHigh(this), dp(24).toFloat())
        root.addView(c, LinearLayout.LayoutParams(-1, -2))
        return c
    }

    private fun build() {
        val title = TextView(this)
        title.text = t("Edit widget")
        title.textSize = 28f
        title.setTextColor(Colors.accent(this))
        title.setPadding(dp(4), dp(8), dp(4), dp(16))
        root.addView(title)

        val pv = preview()
        root.addView(pv, LinearLayout.LayoutParams(pv.layoutParams.width, pv.layoutParams.height).also { it.gravity = Gravity.CENTER_HORIZONTAL })

        val save = TextView(this)
        save.text = t("Save")
        save.textSize = 17f
        save.gravity = Gravity.CENTER
        save.setTextColor(if (Colors.night(this)) 0xFF1B1B1F.toInt() else Color.WHITE)
        save.background = Colors.rounded(Colors.accent(this), dp(40).toFloat())
        save.setPadding(dp(36), dp(14), dp(36), dp(14))
        save.isClickable = true
        save.setOnClickListener {
            ClockCfg.save(this, wid, cfg)
            finish()
        }
        root.addView(save, LinearLayout.LayoutParams(-2, -2).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(14); it.bottomMargin = dp(6) })

        // --- Size: font weight
        val size = card("Size")
        val sb = SeekBar(this)
        sb.max = 8
        sb.progress = (cfg.weight / 100 - 1).coerceIn(0, 8)
        sb.setOnSeekBarChangeListener(simple { p -> cfg.weight = (p + 1) * 100; apply() })
        size.addView(sb)
        val ends = LinearLayout(this)
        ends.orientation = LinearLayout.HORIZONTAL
        val thin = TextView(this); thin.text = t("Thin"); thin.setTextColor(Colors.text(this))
        val thick = TextView(this); thick.text = t("Thick"); thick.setTextColor(Colors.text(this))
        ends.addView(thin, LinearLayout.LayoutParams(0, -2, 1f))
        thick.gravity = Gravity.END
        ends.addView(thick, LinearLayout.LayoutParams(0, -2, 1f))
        size.addView(ends)

        // --- Style
        val style = card("Style")
        radios.clear()
        val names = listOf("Material You", "Transparent", "Glass", "Colour")
        for ((i, n) in names.withIndex()) {
            val r = RadioButton(this)
            r.text = t(n)
            r.textSize = 16f
            r.setTextColor(Colors.text(this))
            r.setPadding(dp(4), dp(8), dp(4), dp(8))
            r.setOnClickListener { cfg.style = i; syncStyle(); apply() }
            radios += r
            style.addView(r)
            if (i == 1) {
                // transparency line, belongs to "Transparent"
                val box = LinearLayout(this)
                box.orientation = LinearLayout.VERTICAL
                val lbl = TextView(this)
                lbl.setTextColor(Colors.text(this)); lbl.alpha = 0.8f; lbl.textSize = 13f
                fun setLbl() { lbl.text = t("Transparency") + ": " + cfg.alpha + "%" }
                setLbl()
                val s = SeekBar(this)
                s.max = 95
                s.progress = cfg.alpha.coerceIn(5, 100) - 5
                s.setOnSeekBarChangeListener(simple { p -> cfg.alpha = p + 5; setLbl(); apply() })
                box.addView(lbl)
                box.addView(s)
                style.addView(box)
                alphaBox = box
            }
            if (i == 3) {
                val hs = android.widget.HorizontalScrollView(this)
                hs.isHorizontalScrollBarEnabled = false
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(dp(4), dp(6), dp(4), dp(6))
                val palette = intArrayOf(
                    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(),
                    0xFF43A047.toInt(), 0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFEC407A.toInt()
                )
                for (col in palette) {
                    val dot = View(this)
                    dot.tag = col
                    dot.setOnClickListener { cfg.color = col; syncStyle(); apply() }
                    row.addView(dot, LinearLayout.LayoutParams(dp(40), dp(40)).also { it.marginEnd = dp(10) })
                }
                hs.addView(row)
                style.addView(hs)
                swatches = row
            }
        }
        syncStyle()

        // --- Seconds
        val sec = card("Seconds")
        val sw = Switch(this)
        sw.text = t("Show seconds")
        sw.textSize = 16f
        sw.setTextColor(Colors.text(this))
        sw.isChecked = cfg.seconds
        sw.minHeight = dp(48)
        sw.setOnCheckedChangeListener { _, on -> cfg.seconds = on; apply() }
        sec.addView(sw)
    }

    private fun apply() { clock.apply(cfg) }

    private fun syncStyle() {
        for ((i, r) in radios.withIndex()) r.isChecked = i == cfg.style
        alphaBox?.visibility = if (cfg.style == 1) View.VISIBLE else View.GONE
        swatches?.let { row ->
            (row.parent as View).visibility = if (cfg.style == 3) View.VISIBLE else View.GONE
            for (i in 0 until row.childCount) {
                val dot = row.getChildAt(i)
                val col = dot.tag as Int
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setColor(col)
                g.setStroke(if (col == cfg.color) dp(3) else dp(1), if (col == cfg.color) Colors.accent(this) else 0x66808080)
                dot.background = g
            }
        }
    }

    private fun simple(f: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { f(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    /** Small picture of the desktop: wallpaper, the icons as they are now, and the clock at its real place and relative size. */
    private fun preview(): View {
        val st = WidgetEditState
        val dm = resources.displayMetrics
        val pw = (dm.widthPixels * 0.5f).toInt().coerceAtLeast(dp(160))
        val ph = (pw * st.dh.toFloat() / st.dw.toFloat()).toInt().coerceAtMost((dm.heightPixels * 0.62f).toInt())
        val w2 = (ph * st.dw.toFloat() / st.dh.toFloat()).toInt()
        val frame = FrameLayout(this)
        val wall = ImageView(this)
        wall.scaleType = ImageView.ScaleType.CENTER_CROP
        val wp = try { android.app.WallpaperManager.getInstance(this).drawable } catch (_: Throwable) { null }
        if (wp != null) wall.setImageDrawable(wp)
        else wall.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Colors.iconBg(this), Colors.accent(this))))
        frame.addView(wall, FrameLayout.LayoutParams(-1, -1))
        st.snapshot?.let {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.FIT_XY
            iv.setImageBitmap(it)
            frame.addView(iv, FrameLayout.LayoutParams(-1, -1))
        }
        clock = SpecialClockView(this, cfg)
        frame.addView(clock, FrameLayout.LayoutParams((st.fw * w2).toInt().coerceAtLeast(8), (st.fh * ph).toInt().coerceAtLeast(8)).also {
            it.leftMargin = (st.fx * w2).toInt(); it.topMargin = (st.fy * ph).toInt()
        })
        frame.clipToOutline = true
        frame.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) }
        }
        frame.layoutParams = LinearLayout.LayoutParams(w2, ph)
        return frame
    }
}
