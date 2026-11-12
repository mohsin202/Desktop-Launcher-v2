package com.android.desktopfiles

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.max

/**
 * The video / audio player window: opened for a file from this app (with the other files of its folder as the play list) or from any other
 * app's "Open with". It draws the controls; the playing itself is done by [PlayerEngine], which keeps going when this window is not showing.
 */
class VideoPlayerActivity : Activity(), SurfaceHolder.Callback, PlayerEngine.Listener {

    companion object { const val EXTRA_PATH = "path" }

    private lateinit var p: Pal
    private val e = PlayerEngine
    private val handler = Handler(Looper.getMainLooper())
    private val rtl get() = T.rtl

    private lateinit var root: FrameLayout
    private lateinit var aspect: AspectBox
    private lateinit var cover: FrameLayout
    private lateinit var coverText: TextView
    private lateinit var subView: TextView
    private lateinit var catcher: View
    private lateinit var controlsLayer: FrameLayout
    private lateinit var controls: FrameLayout
    private lateinit var sliderLayer: FrameLayout
    private lateinit var lockLayer: FrameLayout
    private lateinit var panel: PlayerPanel
    private lateinit var flash: TextView
    private lateinit var titleView: TextView
    private lateinit var seek: ThinSeek
    private lateinit var timeView: TextView
    private lateinit var playBtn: ImageView
    private lateinit var lockBtn: ImageView
    private lateinit var fitBtn: ImageView
    private lateinit var repeatBtn: ImageView
    private lateinit var shuffleBtn: ImageView
    private lateinit var headBtn: ImageView
    private lateinit var rotBtn: ImageView
    private lateinit var brightBar: VSlider
    private lateinit var volBar: VSlider
    private val padded = ArrayList<View>()
    private var inset = intArrayOf(0, 0, 0, 0)

    private var locked = false
    private var inPip = false
    private var resumeOnShow = false
    private var brightness = -1f
    private var dragAxis = 0
    private var volAcc = 0f
    private var lastSave = 0L
    private var tickRunning = false
    private var zoneSide = 0; private var zoneTime = 0L
    private var armedSide = 0; private var armedUntil = 0L; private var armedTotal = 0
    private var scrollCool = 0L
    private var hscroll = 0f
    private var forcedOrientation = false; private var physFamily = -1; private var lastDeg = -1
    private var orient: OrientationEventListener? = null

    // ------------------------------------------------------------------ start

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.init(Store.str(this, "lang", "system"))
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= 28) { val lp = window.attributes; lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES; window.attributes = lp }
        p = Ui.pal(this)
        buildUi()
        if (!openFrom(intent)) { toast("Could not open this file"); finish(); return }
        e.listeners.add(this)
        refreshAll()
        startTicker()
        orient = object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                lastDeg = deg
                // a turned phone takes over again from the button, when the system lets the screen rotate
                if (forcedOrientation && physFamily >= 0 && family(deg) != physFamily && autoRotateOn()) {
                    forcedOrientation = false; requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); if (openFrom(i)) refreshAll() }

    private fun family(deg: Int) = if (deg in 0..44 || deg >= 315 || deg in 135..224) 0 else 1   // 0 upright, 1 sideways
    private fun autoRotateOn() = try { Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1 } catch (_: Exception) { false }

    // ---- what to play

    private fun natural(a: String, b: String): Int {
        val x = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList(); val y = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (k in 0 until minOf(x.size, y.size)) {
            val u = x[k]; val v = y[k]
            val c = if (u[0].isDigit() && v[0].isDigit()) (u.toBigInteger().compareTo(v.toBigInteger())).let { if (it == 0) u.length - v.length else it } else u.compareTo(v)
            if (c != 0) return c
        }
        return x.size - y.size
    }

    private fun pathOf(u: Uri): String? {
        try {
            if (u.scheme == "file") return u.path
            if (u.scheme == "content") {
                if (u.authority == FileProv.AUTH) return u.path
                contentResolver.query(u, arrayOf("_data"), null, null, null)?.use { c -> if (c.moveToFirst()) { val s = c.getString(0); if (s != null && File(s).isFile) return s } }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun nameOf(u: Uri): String {
        try { contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) { val s = c.getString(0); if (!s.isNullOrEmpty()) return s } } } catch (_: Exception) {}
        return u.lastPathSegment?.substringAfterLast('/') ?: "Media"
    }

    private fun openFrom(i: Intent?): Boolean {
        i ?: return false
        val path = i.getStringExtra(EXTRA_PATH) ?: i.data?.let { pathOf(it) }
        if (path != null && File(path).isFile) {
            val f = File(path)
            val sib = f.parentFile?.listFiles()?.filter { it.isFile && (Fs.isVideo(it) || Fs.isAudio(it)) }?.sortedWith { a, b -> natural(a.name, b.name) }?.toMutableList() ?: mutableListOf()
            if (sib.none { it.absolutePath == f.absolutePath }) sib.add(f)
            val start = sib.indexOfFirst { it.absolutePath == f.absolutePath }
            if (e.active && e.current?.key == f.absolutePath) return true      // the same file is already playing: just show it again
            e.load(this, sib.map { PlayItem(it.absolutePath, Fs.uri(it), it.name) }, start)
            return true
        }
        val u = i.data ?: return false
        if (e.active && e.current?.key == u.toString()) return true
        e.load(this, listOf(PlayItem(null, u, nameOf(u))), 0)
        return true
    }

    // ------------------------------------------------------------------ the screen

    private fun icon(kind: String, size: Int = 22, color: Int = Color.WHITE) = PlayerIcon(kind, color, dp(size))

    private fun btn(kind: String, box: Int = 46, size: Int = 24, click: () -> Unit): ImageView {
        val v = ImageView(this); v.setImageDrawable(icon(kind, size)); v.scaleType = ImageView.ScaleType.CENTER; v.isClickable = true
        v.background = Ui.ripple(this, p, null, dpf(box / 2f))
        v.layoutParams = LinearLayout.LayoutParams(dp(box), dp(box)); v.setOnClickListener { click(); poke() }
        return v
    }

    private fun gradient(top: Boolean): View {
        val v = View(this)
        v.background = GradientDrawable(if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
        return v
    }

    private fun buildUi() {
        root = FrameLayout(this); root.setBackgroundColor(Color.BLACK); root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        setContentView(root)

        aspect = AspectBox(this)
        val sv = SurfaceView(this); sv.holder.addCallback(this)
        aspect.addView(sv, FrameLayout.LayoutParams(dp(1), dp(1)))
        root.addView(aspect, FrameLayout.LayoutParams(MATCH, MATCH))

        cover = FrameLayout(this); cover.setBackgroundColor(Color.BLACK); cover.visibility = View.GONE
        val cv = LinearLayout(this); cv.orientation = LinearLayout.VERTICAL; cv.gravity = Gravity.CENTER
        cv.addView(ImageView(this).also { it.setImageDrawable(PlayerIcon("note", 0xFF8FA3B0.toInt(), dp(96))) }, LinearLayout.LayoutParams(dp(96), dp(96)))
        coverText = Ui.text(this, "", 15f, Color.WHITE, false, false); coverText.gravity = Gravity.CENTER; coverText.setPadding(dp(24), dp(12), dp(24), 0)
        cv.addView(coverText, LinearLayout.LayoutParams(MATCH, WRAP))
        cover.addView(cv, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))

        subView = TextView(this); subView.setTextColor(Color.WHITE); subView.textSize = 17f; subView.gravity = Gravity.CENTER
        subView.setShadowLayer(5f, 0f, 0f, Color.BLACK)
        root.addView(subView, FrameLayout.LayoutParams(MATCH, WRAP).also { it.gravity = Gravity.BOTTOM })
        padded.add(subView)

        catcher = View(this); root.addView(catcher, FrameLayout.LayoutParams(MATCH, MATCH)); setupGestures()

        buildControls()
        buildSliders()
        buildLock()

        flash = Ui.text(this, "", 22f, Color.WHITE, true, false); flash.setShadowLayer(6f, 0f, 0f, Color.BLACK); flash.visibility = View.GONE
        root.addView(flash, FrameLayout.LayoutParams(WRAP, WRAP).also { it.gravity = Gravity.CENTER })

        panel = PlayerPanel(this, root)
        panel.onClose = { poke() }

        root.setOnApplyWindowInsetsListener { _, ins -> applyInsets(ins); ins }
        root.requestApplyInsets()
        hideSystemBars()
    }

    private fun buildControls() {
        controlsLayer = FrameLayout(this)
        controlsLayer.addView(gradient(true), FrameLayout.LayoutParams(MATCH, dp(120)).also { it.gravity = Gravity.TOP })
        controlsLayer.addView(gradient(false), FrameLayout.LayoutParams(MATCH, dp(190)).also { it.gravity = Gravity.BOTTOM })
        controls = FrameLayout(this); controlsLayer.addView(controls, FrameLayout.LayoutParams(MATCH, MATCH)); padded.add(controls)
        root.addView(controlsLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        // top: back, name, then play list / speed / audio track / subtitle
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL; top.layoutDirection = Dir.value()
        top.setPadding(dp(6), dp(4), dp(6), 0)
        val back = btn("back") { finish() }; if (rtl) back.scaleX = -1f
        top.addView(back)
        titleView = Ui.text(this, "", 15f, Color.WHITE, false, false); titleView.maxLines = 2; titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        top.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f).also { it.setMargins(dp(6), 0, dp(6), 0) })
        top.addView(btn("playlist") { openPanel("Now playing") { m -> PlayerMenus.playlist(m) } })
        top.addView(btn("speed") { openPanel("Select playback speed") { m -> PlayerMenus.speed(m) } })
        top.addView(btn("note") { openPanel("Select audio track") { m -> PlayerMenus.audio(m) } })
        top.addView(btn("subtitle") { openSubtitle() })
        controls.addView(top, FrameLayout.LayoutParams(MATCH, WRAP).also { it.gravity = Gravity.TOP })

        // centre: ten seconds back, previous, play / pause, next, ten seconds on
        val mid = LinearLayout(this); mid.orientation = LinearLayout.HORIZONTAL; mid.gravity = Gravity.CENTER; mid.layoutDirection = View.LAYOUT_DIRECTION_LTR
        mid.addView(btn("rew10", 56, 32) { e.skip(-10) })
        mid.addView(btn("prev", 56, 28) { if (e.items.size > 1) e.prev() else e.seekTo(0) })
        playBtn = ImageView(this); playBtn.scaleType = ImageView.ScaleType.CENTER; playBtn.isClickable = true
        playBtn.background = Ui.rounded(0xFFEDEDED.toInt(), dpf(40f)); playBtn.setOnClickListener { e.toggle(); poke() }
        mid.addView(playBtn, LinearLayout.LayoutParams(dp(68), dp(68)).also { it.setMargins(dp(8), 0, dp(8), 0) })
        mid.addView(btn("next", 56, 28) { if (e.items.size > 1) e.next() })
        mid.addView(btn("fwd10", 56, 32) { e.skip(10) })
        controls.addView(mid, FrameLayout.LayoutParams(WRAP, WRAP).also { it.gravity = Gravity.CENTER })

        // bottom: time and rotate, the bar, the row of switches
        val bottom = LinearLayout(this); bottom.orientation = LinearLayout.VERTICAL
        val info = LinearLayout(this); info.orientation = LinearLayout.HORIZONTAL; info.gravity = Gravity.CENTER_VERTICAL; info.layoutDirection = Dir.value()
        info.setPadding(dp(8), 0, dp(12), 0)
        timeView = Ui.text(this, "00:00 / 00:00", 14f, Color.WHITE, false, false)
        info.addView(timeView, LinearLayout.LayoutParams(0, WRAP, 1f))
        rotBtn = btn("rotate") { rotate() }; info.addView(rotBtn)
        bottom.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))
        seek = ThinSeek(this, p.accent)
        seek.onStart = { handler.removeCallbacks(autoHide) }
        seek.onMove = { timeView.text = PlayerMenus.clock(it.toLong()) + " / " + PlayerMenus.clock(e.duration.toLong()) }
        seek.onEnd = { e.seekTo(it); poke() }
        bottom.addView(seek, LinearLayout.LayoutParams(MATCH, dp(34)))
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL; row.layoutDirection = Dir.value()
        row.setPadding(dp(6), 0, dp(6), dp(2))
        lockBtn = btn("unlock") { setLocked(true) }; row.addView(lockBtn)
        if (Build.VERSION.SDK_INT >= 26) row.addView(btn("pip") { enterPip() })
        fitBtn = btn("fit_best") { cycleFit() }; row.addView(fitBtn)
        headBtn = btn("headset") { toggleAudioOnly() }; row.addView(headBtn)
        repeatBtn = btn("repeat_off") { e.changeRepeat((e.repeat + 1) % 3); refreshAll() }; row.addView(repeatBtn)
        shuffleBtn = btn("shuffle") { e.shuffle = !e.shuffle; refreshAll() }; row.addView(shuffleBtn)
        bottom.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        controls.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP).also { it.gravity = Gravity.BOTTOM })
    }

    private fun buildSliders() {
        sliderLayer = FrameLayout(this); sliderLayer.visibility = View.GONE; sliderLayer.isClickable = false
        brightBar = VSlider(this, p.accent, "bright"); volBar = VSlider(this, p.accent, "volume")
        sliderLayer.addView(brightBar, FrameLayout.LayoutParams(dp(56), dp(250)).also { it.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL; it.leftMargin = dp(20) })
        sliderLayer.addView(volBar, FrameLayout.LayoutParams(dp(56), dp(250)).also { it.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL; it.rightMargin = dp(20) })
        brightBar.visibility = View.GONE; volBar.visibility = View.GONE
        root.addView(sliderLayer, FrameLayout.LayoutParams(MATCH, MATCH)); padded.add(sliderLayer)
    }

    private fun buildLock() {
        lockLayer = FrameLayout(this); lockLayer.visibility = View.GONE
        val b = ImageView(this); b.setImageDrawable(icon("lock", 26)); b.scaleType = ImageView.ScaleType.CENTER; b.isClickable = true
        b.background = Ui.rounded(0x99000000.toInt(), dpf(28f)); b.setOnClickListener { setLocked(false) }
        lockLayer.addView(b, FrameLayout.LayoutParams(dp(56), dp(56)).also { it.gravity = Gravity.BOTTOM or Gravity.RIGHT; it.setMargins(0, 0, dp(20), dp(20)) })
        root.addView(lockLayer, FrameLayout.LayoutParams(MATCH, MATCH)); padded.add(lockLayer)
    }

    /** Keep controls out of the camera cut-out and the navigation bar, even while those are hidden. */
    @Suppress("DEPRECATION")
    private fun applyInsets(ins: WindowInsets) {
        var l = max(ins.systemWindowInsetLeft, ins.stableInsetLeft); var t = max(ins.systemWindowInsetTop, ins.stableInsetTop)
        var r = max(ins.systemWindowInsetRight, ins.stableInsetRight); var b = max(ins.systemWindowInsetBottom, ins.stableInsetBottom)
        if (Build.VERSION.SDK_INT >= 28) ins.displayCutout?.let { l = max(l, it.safeInsetLeft); t = max(t, it.safeInsetTop); r = max(r, it.safeInsetRight); b = max(b, it.safeInsetBottom) }
        inset = intArrayOf(l, t, r, b)
        for (v in padded) v.setPadding(l, t, r, b)
        subView.setPadding(l + dp(16), 0, r + dp(16), b + dp(96))
    }

    // ------------------------------------------------------------------ menus

    private fun menuCtx() = MenuCtx(this, p, rtl, { panel.hide() }, { pickSubtitle() })

    private fun openPanel(title: String, body: (MenuCtx) -> View) {
        handler.removeCallbacks(autoHide)
        panel.show(T.t(title), body(menuCtx()), inset, rtl)
    }

    private fun openSubtitle() = openPanel("Select subtitle track") { m -> PlayerMenus.subtitle(m) }

    private fun pickSubtitle() {
        try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 711) }
        catch (_: Exception) { toast("No file picker available") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 711 && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            try {
                val text = contentResolver.openInputStream(u)?.use { s -> BufferedReader(InputStreamReader(s)).readText() } ?: ""
                val cues = Srt.parse(text)
                if (cues.isEmpty()) toast("Could not read this subtitle file") else { SubState.cues = cues; SubState.name = nameOf(u); SubState.delayMs = 0; SubState.speed = 1.0; toast("Subtitle loaded") }
            } catch (_: Exception) { toast("Could not read this subtitle file") }
            if (panel.isOpen) openSubtitle()
        }
    }

    // ------------------------------------------------------------------ switches

    private fun cycleFit() {
        aspect.mode = when (aspect.mode) { AspectBox.Mode.BEST -> AspectBox.Mode.STRETCH; AspectBox.Mode.STRETCH -> AspectBox.Mode.CROP; AspectBox.Mode.CROP -> AspectBox.Mode.ORIGINAL; AspectBox.Mode.ORIGINAL -> AspectBox.Mode.BEST }
        toast(when (aspect.mode) { AspectBox.Mode.BEST -> "Best fit"; AspectBox.Mode.STRETCH -> "Stretch"; AspectBox.Mode.CROP -> "Crop"; AspectBox.Mode.ORIGINAL -> "Original size (100%)" })
        refreshAll()
    }

    private fun toggleAudioOnly() {
        e.changeAudioOnly(!e.audioOnly)
        if (e.audioOnly && Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 712)
        toast(if (e.audioOnly) "Audio only" else "Video on")
        refreshAll()
    }

    private fun rotate() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        forcedOrientation = true; physFamily = if (lastDeg >= 0) family(lastDeg) else -1
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun setLocked(v: Boolean) {
        locked = v
        panel.hide()
        controlsLayer.visibility = if (v) View.GONE else View.VISIBLE
        lockLayer.visibility = View.GONE
        handler.removeCallbacks(hideLock)
        if (!v) poke() else showLockBtn()
    }

    private val hideLock = Runnable { lockLayer.visibility = View.GONE }
    private fun showLockBtn() { lockLayer.visibility = View.VISIBLE; handler.removeCallbacks(hideLock); handler.postDelayed(hideLock, 3000) }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        val w = if (e.videoW > 0) e.videoW else 16; val h = if (e.videoH > 0) e.videoH else 9
        try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(w.coerceIn(h * 100 / 239, h * 239 / 100), h)).build()) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ keeping the screen up to date

    private fun refreshAll() {
        titleView.text = e.current?.title ?: ""
        coverText.text = e.current?.title ?: ""
        playBtn.setImageDrawable(icon(if (e.playing) "pause_f" else "play_f", 32, 0xFF222B30.toInt()))
        fitBtn.setImageDrawable(icon(when (aspect.mode) { AspectBox.Mode.BEST -> "fit_best"; AspectBox.Mode.STRETCH -> "fit_stretch"; AspectBox.Mode.CROP -> "fit_crop"; AspectBox.Mode.ORIGINAL -> "fit_100" }))
        val on = lighten(p.accent, 0.5f)
        repeatBtn.setImageDrawable(PlayerIcon(when (e.repeat) { 1 -> "repeat_one"; 2 -> "repeat_all"; else -> "repeat_off" }, if (e.repeat == 0) Color.WHITE else on, dp(24)))
        shuffleBtn.setImageDrawable(PlayerIcon("shuffle", if (e.shuffle) on else Color.WHITE, dp(24)))
        headBtn.setImageDrawable(PlayerIcon("headset", if (e.audioOnly) on else Color.WHITE, dp(24)))
        lockBtn.setImageDrawable(icon("unlock"))
        cover.visibility = if (e.audioOnly || (e.prepared && e.videoW == 0)) View.VISIBLE else View.GONE
        if (e.playing && !e.audioOnly) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onTrack() { aspect.setVideoSize(0, 0); seek.progress = 0; seek.max = 1; timeView.text = "00:00 / 00:00"; subView.text = ""; refreshAll() }
    override fun onPrepared() { seek.max = e.duration; refreshAll() }
    override fun onState() { if (e.items.isEmpty()) { finish(); return }; refreshAll() }
    override fun onVideoSize(w: Int, h: Int) { aspect.setVideoSize(w, h); refreshAll() }
    override fun onError(msg: String) { toast(msg) }
    override fun onList() { if (e.items.isEmpty()) finish() }

    private fun startTicker() {
        if (tickRunning) return
        tickRunning = true
        handler.post(object : Runnable {
            override fun run() {
                if (e.prepared) {
                    val pos = e.position; val dur = e.duration
                    seek.max = dur; seek.progress = pos
                    timeView.text = PlayerMenus.clock(pos.toLong()) + " / " + PlayerMenus.clock(dur.toLong())
                    subView.text = SubState.at(pos.toLong())
                    val now = System.currentTimeMillis()
                    if (e.playing && now - lastSave > 5000) { lastSave = now; e.saveNow() }
                }
                handler.postDelayed(this, 250)
            }
        })
    }

    // ------------------------------------------------------------------ touch

    private fun setupGestures() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(ev: MotionEvent): Boolean { dragAxis = 0; return true }

            override fun onSingleTapUp(ev: MotionEvent): Boolean {
                if (locked) { showLockBtn(); return true }
                val side = if (ev.x < root.width / 2f) -1 else 1
                val now = System.currentTimeMillis()
                if (armedSide == side && now < armedUntil) { seekTap(side, now); return true }
                if (zoneSide == side && now - zoneTime < 350) { armedTotal = 0; seekTap(side, now); zoneSide = 0 }
                else {
                    zoneSide = side; zoneTime = now
                    handler.postDelayed({ if (zoneSide == side && System.currentTimeMillis() - zoneTime >= 330) { zoneSide = 0; toggleControls() } }, 350)
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || e1 == null || inPip) return false
                if (dragAxis == 0) {
                    if (abs(dy) < abs(dx) || abs(dy) < dp(6)) { if (abs(dx) > dp(10)) dragAxis = 2; return false }
                    dragAxis = if (e1.x < root.width / 2f) -1 else 1
                    if (dragAxis == -1 && brightness < 0) brightness = currentBrightness()
                    volAcc = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                }
                if (dragAxis == 2) return false
                val frac = dy / root.height.toFloat() * 1.3f
                if (dragAxis == -1) {
                    brightness = (brightness + frac).coerceIn(0.02f, 1f)
                    val lp = window.attributes; lp.screenBrightness = brightness; window.attributes = lp
                    showSlider(false)
                } else {
                    val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    volAcc = (volAcc + frac * mx).coerceIn(0f, mx.toFloat())
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(volAcc), 0)
                    showSlider(true)
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (locked || e1 == null || dragAxis == -1 || dragAxis == 1 || e.items.size < 2) return false
                if (abs(vx) > 1400 && abs(vx) > 2.5f * abs(vy) && abs(e2.x - e1.x) > root.width * 0.25f) {
                    if (e2.x < e1.x) e.next() else e.prev()
                    return true
                }
                return false
            }
        })
        catcher.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }
    }

    private fun seekTap(side: Int, now: Long) {
        armedSide = side; armedUntil = now + 700; armedTotal += 10
        e.skip(side * 10)
        flash.translationX = side * root.width * 0.28f
        showFlash((if (side < 0) "−" else "+") + armedTotal + "s")
    }

    private fun showFlash(s: String) {
        flash.text = s; flash.visibility = View.VISIBLE
        handler.removeCallbacks(hideFlash); handler.postDelayed(hideFlash, 700)
    }
    private val hideFlash = Runnable { flash.visibility = View.GONE; flash.translationX = 0f }

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0) return b
        return try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (_: Exception) { 0.5f }
    }

    /** Show the brightness or the volume bar for a moment. */
    private fun showSlider(volume: Boolean) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (volume) volBar.value = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        else { if (brightness < 0) brightness = currentBrightness(); brightBar.value = (brightness * 100).toInt() }
        sliderLayer.visibility = View.VISIBLE
        (if (volume) volBar else brightBar).visibility = View.VISIBLE
        handler.removeCallbacks(hideSliders); handler.postDelayed(hideSliders, 1400)
    }
    private val hideSliders = Runnable { sliderLayer.visibility = View.GONE; brightBar.visibility = View.GONE; volBar.visibility = View.GONE }

    // ------------------------------------------------------------------ showing / hiding the controls

    private val autoHide = Runnable { if (!panel.isOpen && !locked) controlsLayer.visibility = View.GONE }

    private fun poke() {
        handler.removeCallbacks(autoHide)
        if (controlsLayer.visibility == View.VISIBLE && !panel.isOpen) handler.postDelayed(autoHide, 3500)
    }

    private fun toggleControls() {
        if (locked) return
        if (controlsLayer.visibility == View.VISIBLE) { controlsLayer.visibility = View.GONE; handler.removeCallbacks(autoHide) } else showControls()
    }

    private fun showControls() {
        if (locked || inPip) return
        controlsLayer.visibility = View.VISIBLE; poke()
    }

    // mouse and trackpad
    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE && !panel.isOpen) showControls()
        if (ev.actionMasked == MotionEvent.ACTION_SCROLL && !locked && e.items.size > 1) {
            hscroll += ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val now = System.currentTimeMillis()
            if (abs(hscroll) >= 2f && now - scrollCool > 700) { scrollCool = now; if (hscroll < 0) e.next() else e.prev(); hscroll = 0f; return true }
            if (now - scrollCool > 700 && abs(hscroll) < 2f && ev.getAxisValue(MotionEvent.AXIS_HSCROLL) == 0f) hscroll = 0f
        }
        return super.onGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.action == KeyEvent.ACTION_DOWN && !locked && !panel.isOpen) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            when (ev.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { e.skip(-10); showFlash("−10s"); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { e.skip(10); showFlash("+10s"); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); showSlider(true); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); showSlider(true); return true }
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { e.toggle(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { if (e.items.size > 1) e.next(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { e.prev(); return true }
            }
        }
        val r = super.dispatchKeyEvent(ev)
        if (ev.action == KeyEvent.ACTION_DOWN && (ev.keyCode == KeyEvent.KEYCODE_VOLUME_UP || ev.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) handler.post { showSlider(true) }
        return r
    }

    override fun onBackPressed() { if (panel.isOpen) panel.hide() else finish() }

    // ------------------------------------------------------------------ system bars, rotation, picture in picture

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemBars() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel.hide(); hideSystemBars(); root.requestApplyInsets()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        controlsLayer.visibility = if (inPip || locked) View.GONE else View.VISIBLE
        catcher.visibility = if (inPip) View.GONE else View.VISIBLE
        if (inPip) panel.hide()
    }

    // ------------------------------------------------------------------ video surface

    override fun surfaceCreated(h: SurfaceHolder) { e.setDisplay(h) }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { e.setDisplay(null) }

    // ------------------------------------------------------------------ lifecycle

    override fun onPause() {
        super.onPause()
        e.saveNow()
        resumeOnShow = false
        if (!isInPip()) {
            if (e.audioOnly) { if (e.playing) startPlayerService() }
            else if (e.playing) { resumeOnShow = true; e.pause() }
        }
    }

    private fun isInPip() = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    private fun startPlayerService() {
        try { val i = Intent(this, PlayerService::class.java); if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        try { stopService(Intent(this, PlayerService::class.java)) } catch (_: Exception) {}
        if (resumeOnShow) { resumeOnShow = false; e.start() }
        refreshAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        orient?.disable()
        e.listeners.remove(this)
        handler.removeCallbacksAndMessages(null)
        tickRunning = false
        // closing the window ends the playing too; only the "headphone" mode with the window merely out of sight goes on
        if (isFinishing) { e.release(true); try { stopService(Intent(this, PlayerService::class.java)) } catch (_: Exception) {} }
    }
}
