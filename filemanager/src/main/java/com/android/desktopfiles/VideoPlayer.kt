package com.android.desktopfiles

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One subtitle line read from a .srt file. */
class SrtCue(val startMs: Long, val endMs: Long, val text: String)

/** Reads a .srt (SubRip) subtitle file. Plain text parsing, no library needed. */
object Srt {
    private fun time(s: String): Long {
        // 00:01:02,345
        val m = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)").find(s.trim()) ?: return 0L
        val (h, mi, se, ms) = m.destructured
        return h.toLong() * 3600000 + mi.toLong() * 60000 + se.toLong() * 1000 + ms.toLong()
    }

    fun parse(text: String): List<SrtCue> {
        val out = ArrayList<SrtCue>()
        val blocks = text.replace("\r\n", "\n").replace("\r", "\n").split(Regex("\n\\s*\n"))
        for (b in blocks) {
            val lines = b.trim().split("\n")
            if (lines.isEmpty()) continue
            val timeLineIdx = lines.indexOfFirst { it.contains("-->") }
            if (timeLineIdx < 0) continue
            val parts = lines[timeLineIdx].split("-->")
            if (parts.size != 2) continue
            val start = time(parts[0]); val end = time(parts[1])
            val body = lines.drop(timeLineIdx + 1).joinToString("\n").replace(Regex("<[^>]+>"), "").trim()
            if (body.isNotEmpty()) out.add(SrtCue(start, end, body))
        }
        return out.sortedBy { it.startMs }
    }
}

/** Centers and scales its one child (the video SurfaceView) according to a fit [mode], given the video's own pixel size. */
class AspectBox(c: Context) : FrameLayout(c) {
    enum class Mode { BEST, STRETCH, CROP, ORIGINAL }
    var videoW = 0; var videoH = 0
    var mode = Mode.BEST
        set(v) { field = v; requestLayout() }

    fun setVideoSize(w: Int, h: Int) { videoW = w; videoH = h; requestLayout() }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        super.onMeasure(wSpec, hSpec)
        val cw = MeasureSpec.getSize(wSpec); val ch = MeasureSpec.getSize(hSpec)
        val ch1 = getChildAt(0) ?: return
        if (videoW <= 0 || videoH <= 0) { ch1.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)); return }
        var w: Int; var h: Int
        when (mode) {
            Mode.STRETCH -> { w = cw; h = ch }
            Mode.ORIGINAL -> { w = videoW; h = videoH }
            Mode.CROP -> {
                val scale = max(cw.toFloat() / videoW, ch.toFloat() / videoH)
                w = (videoW * scale).toInt(); h = (videoH * scale).toInt()
            }
            Mode.BEST -> {
                val scale = min(cw.toFloat() / videoW, ch.toFloat() / videoH)
                w = (videoW * scale).toInt(); h = (videoH * scale).toInt()
            }
        }
        ch1.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val ch1 = getChildAt(0) ?: return
        val cw = r - l; val ch = b - t
        val w = ch1.measuredWidth; val h = ch1.measuredHeight
        val x = (cw - w) / 2; val y = (ch - h) / 2
        ch1.layout(x, y, x + w, y + h)
    }
}

/**
 * The in-app video / audio player, opened for files from this app or from any other app's "Open with".
 * Uses plain android.media.MediaPlayer (no AndroidX / Media3), to keep this module free of that dependency.
 */
class VideoPlayerActivity : Activity(), SurfaceHolder.Callback {

    companion object { const val EXTRA_PATH = "path" }

    private lateinit var p: Pal
    private var mp: MediaPlayer? = null
    private var mediaUri: Uri? = null
    private var mediaPath: String? = null
    private var title = ""
    private var isAudioFile = false

    private var holder: SurfaceHolder? = null
    private var prepared = false
    private var wasPlaying = false
    private var pendingSeek = -1
    private var videoW = 0
    private var videoH = 0
    private var locked = false
    private var audioOnly = false
    private var repeatOn = false
    private var speed = 1f
    private var lastSavedOrientationLock = false
    private var subCues: List<SrtCue> = emptyList()
    private var subDelayMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var menuOpen = false

    // views
    private var root: FrameLayout? = null
    private var aspect: AspectBox? = null
    private var surfaceView: SurfaceView? = null
    private var controls: FrameLayout? = null
    private var topBar: LinearLayout? = null
    private var titleView: TextView? = null
    private var centerRow: LinearLayout? = null
    private var playBtn: View? = null
    private var bottomBar: LinearLayout? = null
    private var seek: SeekBar? = null
    private var curT: TextView? = null
    private var durT: TextView? = null
    private var subtitleView: TextView? = null
    private var unlockBtn: View? = null
    private var audioCover: FrameLayout? = null
    private var flash: TextView? = null
    private var gestureCatcher: View? = null
    private var pop: Pop? = null

    private var gd: GestureDetector? = null
    private var brightness = -1f
    private var dragSide = 0 // -1 brightness (left), 1 volume (right), 0 none
    private var dragging = false

    // double-tap seek with accumulation
    private var zoneTapSide = 0; private var zoneTapTime = 0L
    private var armedSide = 0; private var armedUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.init(Store.str(this, "lang", "system"))
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        p = Ui.pal(this)

        mediaPath = intent.getStringExtra(EXTRA_PATH)
        if (mediaPath != null) {
            val f = java.io.File(mediaPath!!)
            title = Fs.shown(f); isAudioFile = Fs.isAudio(f)
            mediaUri = Fs.uri(f)
        } else {
            mediaUri = intent.data
            title = mediaUri?.lastPathSegment?.substringAfterLast('/') ?: "Media"
            val mt = intent.type ?: ""
            isAudioFile = mt.startsWith("audio/") || Fs.isAudio(java.io.File(title))
        }

        buildUi()
        if (mediaUri == null) { toast("Could not open this file"); finish(); return }
    }

    // ---------- UI ----------

    private fun buildUi() {
        root = FrameLayout(this).also { it.setBackgroundColor(Color.BLACK) }
        setContentView(root)

        aspect = AspectBox(this)
        surfaceView = SurfaceView(this).also { sv -> sv.holder.addCallback(this) }
        aspect!!.addView(surfaceView, FrameLayout.LayoutParams(dp(1), dp(1)))
        root!!.addView(aspect, FrameLayout.LayoutParams(MATCH, MATCH).also { it.gravity = android.view.Gravity.CENTER })

        audioCover = FrameLayout(this).also { it.setBackgroundColor(Color.BLACK); it.visibility = View.GONE }
        val note = Ui.text(this, "♪ " + title, 16f, Color.WHITE, true, tr = false)
        note.gravity = android.view.Gravity.CENTER
        audioCover!!.addView(note, FrameLayout.LayoutParams(MATCH, WRAP).also { it.gravity = android.view.Gravity.CENTER })
        root!!.addView(audioCover, FrameLayout.LayoutParams(MATCH, MATCH))

        subtitleView = TextView(this).also {
            it.setTextColor(Color.WHITE); it.textSize = 16f; it.gravity = android.view.Gravity.CENTER
            it.setShadowLayer(4f, 0f, 0f, Color.BLACK); it.setPaddingRelative(dp(16), 0, dp(16), dp(72))
        }
        root!!.addView(subtitleView, FrameLayout.LayoutParams(MATCH, WRAP).also { it.gravity = android.view.Gravity.BOTTOM })

        gestureCatcher = View(this)
        root!!.addView(gestureCatcher, FrameLayout.LayoutParams(MATCH, MATCH))
        setupGestures()

        buildControls()

        unlockBtn = Ui.iconButton(this, p.also { }, "unlock", 52, 52) { setLocked(false) }
        unlockBtn!!.setBackgroundColor(0x66000000)
        unlockBtn!!.visibility = View.GONE
        root!!.addView(unlockBtn, FrameLayout.LayoutParams(dp(52), dp(52)).also { it.gravity = android.view.Gravity.BOTTOM or Gravity1Start(); it.setMargins(dp(16), dp(16), dp(16), dp(16)) })

        flash = Ui.text(this, "", 20f, Color.WHITE, true, tr = false)
        flash!!.setShadowLayer(4f, 0f, 0f, Color.BLACK)
        flash!!.visibility = View.GONE
        root!!.addView(flash, FrameLayout.LayoutParams(WRAP, WRAP).also { it.gravity = android.view.Gravity.CENTER })

        root!!.layoutDirection = View.LAYOUT_DIRECTION_LTR  // playback controls keep one fixed layout in every language, like every other video player
        applyOrientationLayout()
    }

    private fun Gravity1Start() = android.view.Gravity.START

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildControls() {
        controls = FrameLayout(this)
        root!!.addView(controls, FrameLayout.LayoutParams(MATCH, MATCH))

        // top bar: back, title, lock, more
        topBar = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL; it.gravity = android.view.Gravity.CENTER_VERTICAL }
        topBar!!.setBackgroundColor(0x99000000.toInt())
        topBar!!.setPadding(dp(4), dp(4), dp(4), dp(4))
        topBar!!.addView(iconBtn("close") { finish() })
        titleView = Ui.text(this, title, 15f, Color.WHITE, true, tr = false)
        titleView!!.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE; titleView!!.isSingleLine = true
        topBar!!.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f).also { it.marginStart = dp(8) })
        topBar!!.addView(iconBtn("lock") { setLocked(true) })
        topBar!!.addView(iconBtn("more") { showMore(it) })
        controls!!.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP))

        // center: prev, play/pause, next
        centerRow = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL; it.gravity = android.view.Gravity.CENTER }
        centerRow!!.addView(iconBtn("prev", 56, 56) { skip(-10) })
        playBtn = iconBtn("pause", 72, 72) { togglePlay() }
        centerRow!!.addView(playBtn, LinearLayout.LayoutParams(dp(72), dp(72)).also { it.marginStart = dp(20); it.marginEnd = dp(20) })
        centerRow!!.addView(iconBtn("next", 56, 56) { skip(10) })
        controls!!.addView(centerRow, FrameLayout.LayoutParams(WRAP, WRAP).also { it.gravity = android.view.Gravity.CENTER })

        // bottom: seek row + icon row
        bottomBar = LinearLayout(this).also { it.orientation = LinearLayout.VERTICAL }
        bottomBar!!.setBackgroundColor(0x99000000.toInt())
        bottomBar!!.setPadding(dp(8), dp(6), dp(8), dp(2))

        val seekRow = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL; it.gravity = android.view.Gravity.CENTER_VERTICAL }
        curT = Ui.text(this, "0:00", 12f, Color.WHITE, false, tr = false)
        seek = SeekBar(this)
        durT = Ui.text(this, "0:00", 12f, Color.WHITE, false, tr = false)
        seekRow.addView(curT, LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginEnd = dp(6) })
        seekRow.addView(seek, LinearLayout.LayoutParams(0, WRAP, 1f))
        seekRow.addView(durT, LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginStart = dp(6) })
        bottomBar!!.addView(seekRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val iconRow = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL; it.gravity = android.view.Gravity.CENTER }
        iconRow.addView(iconBtn("fit") { cycleFit() })
        iconRow.addView(iconBtn("speed") { speedDialog() })
        iconRow.addView(iconBtn("loop") { repeatOn = !repeatOn; mp?.isLooping = repeatOn; toast(if (repeatOn) "Repeat on" else "Repeat off") })
        iconRow.addView(iconBtn("headset") { toggleAudioOnly() })
        iconRow.addView(iconBtn("subtitle") { pickSubtitle() })
        iconRow.addView(iconBtn("rotate") { toggleRotationLock() })
        if (Build.VERSION.SDK_INT >= 26) iconRow.addView(iconBtn("pip") { enterPip() })
        bottomBar!!.addView(iconRow, LinearLayout.LayoutParams(MATCH, WRAP))
        controls!!.addView(bottomBar, FrameLayout.LayoutParams(MATCH, WRAP).also { it.gravity = android.view.Gravity.BOTTOM })

        seek!!.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, v: Int, u: Boolean) { if (u) curT?.text = fmt(v.toLong()) }
            override fun onStartTrackingTouch(b: SeekBar?) { keepAwake() }
            override fun onStopTrackingTouch(b: SeekBar?) { mp?.seekTo(b?.progress ?: 0); keepAwake() }
        })
    }

    private fun iconBtn(icon: String, w: Int = 44, h: Int = 44, click: (View) -> Unit): View =
        Ui.iconButton(this, p, icon, w, h, click).also { it.setColorFilter(Color.WHITE) }

    // ---------- lifecycle / media ----------

    override fun surfaceCreated(h: SurfaceHolder) { holder = h; if (!isAudioFile) openMedia() else { holder = h; openMedia() } }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {}

    private fun openMedia() {
        if (mp != null) return
        try {
            val player = MediaPlayer()
            mp = player
            player.setDataSource(this, mediaUri!!)
            if (!audioOnly) player.setDisplay(holder)
            player.setOnVideoSizeChangedListener { _, w, hh -> videoW = w; videoH = hh; aspect?.setVideoSize(w, hh) }
            player.setOnPreparedListener {
                prepared = true
                seek?.max = player.duration
                durT?.text = fmt(player.duration.toLong())
                if (pendingSeek >= 0) { player.seekTo(pendingSeek); pendingSeek = -1 }
                player.isLooping = repeatOn
                try { player.playbackParams = player.playbackParams.setSpeed(speed) } catch (_: Exception) {}
                player.start()
                playBtn?.let { (it as? android.widget.ImageView)?.setImageDrawable(MenuIcon("pause", Color.WHITE, dp(28))) }
                startTicker()
                scheduleAutoHide()
            }
            player.setOnCompletionListener { playBtn?.let { (it as? android.widget.ImageView)?.setImageDrawable(MenuIcon("play", Color.WHITE, dp(28))) } }
            player.setOnErrorListener { _, _, _ -> toast("This format or file could not be played"); true }
            player.prepareAsync()
        } catch (e: Exception) { toast("Could not open this file: " + e.message) }
    }

    private fun togglePlay() {
        val m = mp ?: return
        if (!prepared) return
        if (m.isPlaying) { m.pause(); (playBtn as? android.widget.ImageView)?.setImageDrawable(MenuIcon("play", Color.WHITE, dp(28))) }
        else { m.start(); (playBtn as? android.widget.ImageView)?.setImageDrawable(MenuIcon("pause", Color.WHITE, dp(28))) }
        scheduleAutoHide()
    }

    private fun skip(sec: Int) {
        val m = mp ?: return
        if (!prepared) return
        val t = (m.currentPosition + sec * 1000).coerceIn(0, m.duration)
        m.seekTo(t); seek?.progress = t; curT?.text = fmt(t.toLong())
    }

    private fun startTicker() {
        handler.post(object : Runnable {
            override fun run() {
                val m = mp
                if (m != null && prepared) {
                    try {
                        val pos = m.currentPosition
                        seek?.progress = pos; curT?.text = fmt(pos.toLong())
                        updateSubtitle(pos.toLong())
                    } catch (_: Exception) {}
                }
                handler.postDelayed(this, 300)
            }
        })
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    // ---------- fit / speed / repeat / audio-only / subtitles ----------

    private fun cycleFit() {
        val box = aspect ?: return
        box.mode = when (box.mode) {
            AspectBox.Mode.BEST -> AspectBox.Mode.STRETCH
            AspectBox.Mode.STRETCH -> AspectBox.Mode.CROP
            AspectBox.Mode.CROP -> AspectBox.Mode.ORIGINAL
            AspectBox.Mode.ORIGINAL -> AspectBox.Mode.BEST
        }
        toast(when (box.mode) { AspectBox.Mode.BEST -> "Best fit"; AspectBox.Mode.STRETCH -> "Stretch"; AspectBox.Mode.CROP -> "Crop"; AspectBox.Mode.ORIGINAL -> "Original size (100%)" })
    }

    private fun speedDialog() {
        menuOpen = true
        val row = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL; it.gravity = android.view.Gravity.CENTER_VERTICAL }
        row.setPaddingRelative(dp(20), dp(8), dp(20), 0)
        val et = EditText(this); et.setText(String.format(Locale.US, "%.2f", speed)); et.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        row.addView(Ui.glyphButton(this, p, "−", 20f) { val v = (et.text.toString().toFloatOrNull() ?: speed) - 0.1f; et.setText(String.format(Locale.US, "%.2f", v.coerceIn(0.25f, 4f))) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(et, LinearLayout.LayoutParams(0, WRAP, 1f).also { it.marginStart = dp(8); it.marginEnd = dp(8) })
        row.addView(Ui.glyphButton(this, p, "+", 20f) { val v = (et.text.toString().toFloatOrNull() ?: speed) + 0.1f; et.setText(String.format(Locale.US, "%.2f", v.coerceIn(0.25f, 4f))) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        AlertDialog.Builder(this).setTitle(T.t("Playback speed")).setView(row)
            .setPositiveButton(T.t("OK")) { _, _ ->
                val v = (et.text.toString().toFloatOrNull() ?: 1f).coerceIn(0.25f, 4f)
                speed = v
                try { mp?.playbackParams = mp!!.playbackParams.setSpeed(v) } catch (_: Exception) { toast("This device cannot change speed") }
                menuOpen = false; scheduleAutoHide()
            }
            .setNegativeButton(T.t("Cancel")) { _, _ -> menuOpen = false; scheduleAutoHide() }
            .setOnCancelListener { menuOpen = false; scheduleAutoHide() }
            .showDir()
    }

    private fun toggleAudioOnly() {
        audioOnly = !audioOnly
        audioCover?.visibility = if (audioOnly) View.VISIBLE else View.GONE
        try { mp?.setDisplay(if (audioOnly) null else holder) } catch (_: Exception) {}
        toast(if (audioOnly) "Audio only" else "Video on")
    }

    private fun toggleRotationLock() {
        lastSavedOrientationLock = !lastSavedOrientationLock
        requestedOrientation = if (lastSavedOrientationLock) {
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        toast(if (lastSavedOrientationLock) "Rotation locked" else "Rotation unlocked")
    }

    private fun setLocked(v: Boolean) {
        locked = v
        gestureCatcher?.isEnabled = !v
        controls?.visibility = if (v) View.GONE else View.VISIBLE
        unlockBtn?.visibility = if (v) View.VISIBLE else View.GONE
        if (!v) scheduleAutoHide()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        val w = if (videoW > 0) videoW else 16; val h = if (videoH > 0) videoH else 9
        val ratio = Rational(w.coerceAtMost(h * 10), h.coerceAtMost(w * 10))
        try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) } catch (_: Exception) {}
    }

    private fun pickSubtitle() {
        menuOpen = true
        try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 711) }
        catch (_: Exception) { toast("No file picker available"); menuOpen = false }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        menuOpen = false
        if (requestCode == 711 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val text = contentResolver.openInputStream(uri)?.use { s -> BufferedReader(InputStreamReader(s)).readText() } ?: ""
                subCues = Srt.parse(text)
                toast(if (subCues.isNotEmpty()) "Subtitle loaded" else "Could not read this subtitle file")
            } catch (e: Exception) { toast("Could not read this subtitle file") }
        }
        scheduleAutoHide()
    }

    private fun updateSubtitle(posMs: Long) {
        if (subCues.isEmpty()) { if (subtitleView?.text?.isNotEmpty() == true) subtitleView?.text = ""; return }
        val t = posMs - subDelayMs
        val cue = subCues.firstOrNull { t in it.startMs..it.endMs }
        subtitleView?.text = cue?.text ?: ""
    }

    // ---------- more menu ----------

    private fun showMore(anchor: View) {
        menuOpen = true
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val items = listOf(
            MenuItem("Repeat", checked = repeatOn) { repeatOn = !repeatOn; mp?.isLooping = repeatOn },
            MenuItem("Audio only", checked = audioOnly) { toggleAudioOnly() },
            MenuItem("Subtitles…") { pickSubtitle() },
            MenuItem("Playback speed…") { speedDialog() },
            if (Build.VERSION.SDK_INT >= 26) MenuItem("Picture in picture") { enterPip() } else null
        )
        if (pop == null) pop = Pop(this)
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        if (landscape) pop!!.showAt(anchor, dp(8), dp(60), items) else pop!!.showAt(anchor, loc[0], loc[1] + anchor.height, items)
        // Pop has no close callback; clear menuOpen after a short delay so auto-hide resumes once it is dismissed by an outside tap
        handler.postDelayed({ menuOpen = false }, 6000)
    }

    // ---------- gestures ----------

    private fun setupGestures() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { dragging = false; dragSide = 0; return true }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val w = root?.width ?: return true
                val side = if (e.x < w / 2f) -1 else 1
                val now = System.currentTimeMillis()
                if (armedSide == side && now < armedUntil) { doSeekFlash(side); armedUntil = now + 700; return true }
                if (zoneTapSide == side && now - zoneTapTime < 350) {
                    armedSide = side; armedUntil = now + 700
                    doSeekFlash(side); zoneTapSide = 0
                } else {
                    zoneTapSide = side; zoneTapTime = now
                    handler.postDelayed({ if (zoneTapSide == side && System.currentTimeMillis() - zoneTapTime >= 330) { zoneTapSide = 0; toggleControls() } }, 350)
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || e1 == null) return false
                val w = root?.width ?: return false; val h = root?.height ?: return false
                if (!dragging) {
                    if (abs(dy) < abs(dx) || abs(dy) < dp(8)) return false
                    dragging = true; dragSide = if (e1.x < w / 2f) -1 else 1
                    if (dragSide == 1) {
                        // start from current system volume
                    } else {
                        if (brightness < 0) brightness = window.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
                    }
                }
                val frac = dy / h
                if (dragSide == -1) {
                    brightness = (brightness - frac).coerceIn(0.02f, 1f)
                    val lp = window.attributes; lp.screenBrightness = brightness; window.attributes = lp
                    showFlash("☀ " + (brightness * 100).toInt() + "%")
                } else {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val delta = (-frac * max * 2.2f).toInt()
                    if (delta != 0) {
                        val nv = (cur + delta).coerceIn(0, max)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                        showFlash("🔊 " + (nv * 100 / max) + "%")
                    }
                }
                return true
            }
        })
        gestureCatcher!!.setOnTouchListener { _, e -> gd?.onTouchEvent(e); if (e.actionMasked == MotionEvent.ACTION_UP) dragging = false; true }
    }

    private fun doSeekFlash(side: Int) {
        skip(side * 10)
        showFlash(if (side < 0) "⏪ 10s" else "10s ⏩")
    }

    private fun showFlash(s: String) {
        flash?.text = s; flash?.visibility = View.VISIBLE
        handler.removeCallbacks(hideFlash); handler.postDelayed(hideFlash, 600)
    }
    private val hideFlash = Runnable { flash?.visibility = View.GONE }

    // ---------- auto-hide controls ----------

    private val hideControls = Runnable { if (!menuOpen) controls?.visibility = View.GONE }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(hideControls)
        if (!locked) handler.postDelayed(hideControls, 3500)
    }

    private fun toggleControls() {
        if (locked) return
        val c = controls ?: return
        if (c.visibility == View.VISIBLE) { c.visibility = View.GONE; handler.removeCallbacks(hideControls) }
        else { c.visibility = View.VISIBLE; scheduleAutoHide() }
    }

    private fun keepAwake() { handler.removeCallbacks(hideControls) }

    // ---------- orientation ----------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout()
    }

    private fun applyOrientationLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        bottomBar?.orientation = LinearLayout.VERTICAL
        topBar?.setPadding(dp(if (landscape) 10 else 4), dp(4), dp(if (landscape) 10 else 4), dp(4))
        hideSystemBars()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemBars() }

    // ---------- keyboard ----------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!locked && e.action == KeyEvent.ACTION_DOWN) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { skip(-10); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { skip(10); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return true }
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------- PiP mode changes the controls away ----------

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        controls?.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        gestureCatcher?.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    // ---------- lifecycle ----------

    override fun onPause() {
        super.onPause()
        wasPlaying = mp?.isPlaying == true
        if (!isInPictureInPictureMode) mp?.pause()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (wasPlaying) mp?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        mp = null
    }
}
