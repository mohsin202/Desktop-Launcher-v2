package com.android.desktopfiles

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.SurfaceHolder
import java.io.File

/** One entry of the play list. [path] is set when the file is local, so it can be played directly and found again for its saved position. */
class PlayItem(val path: String?, val uri: Uri, val title: String) {
    val key: String get() = path ?: uri.toString()
}

/** The videos / songs the file manager was showing, in the order it showed them (by name, date ...), so the player's play list is in the same order. */
object PlayerOrder {
    var paths: List<String> = emptyList()
    var at = 0L
}

/** A subtitle line read from a .srt file. */
class SrtCue(val startMs: Long, val endMs: Long, val text: String)

/** Reads a .srt (SubRip) subtitle file. Plain text parsing, no library needed. */
object Srt {
    private fun time(s: String): Long {
        val m = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)").find(s.trim()) ?: return 0L
        val (h, mi, se, ms) = m.destructured
        return h.toLong() * 3600000 + mi.toLong() * 60000 + se.toLong() * 1000 + ms.padEnd(3, '0').take(3).toLong()
    }

    fun parse(text: String): List<SrtCue> {
        val out = ArrayList<SrtCue>()
        val blocks = text.removePrefix("﻿").replace("\r\n", "\n").replace("\r", "\n").split(Regex("\n\\s*\n"))
        for (b in blocks) {
            val lines = b.trim().split("\n")
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val parts = lines[ti].split("-->")
            if (parts.size != 2) continue
            val body = lines.drop(ti + 1).joinToString("\n").replace(Regex("<[^>]+>"), "").trim()
            if (body.isNotEmpty()) out.add(SrtCue(time(parts[0]), time(parts[1]), body))
        }
        return out.sortedBy { it.startMs }
    }
}

/** The subtitle that goes with the video now playing: its lines, a name for the menu, and the two adjustments (delay in ms, speed factor). */
object SubState {
    var cues: List<SrtCue> = emptyList()
    var name = ""
    var delayMs = 0L
    var speed = 1.0
    fun clear() { cues = emptyList(); name = ""; delayMs = 0; speed = 1.0 }
    fun at(posMs: Long): String {
        if (cues.isEmpty()) return ""
        for (c in cues) {
            val s = (c.startMs * speed).toLong() + delayMs; val e = (c.endMs * speed).toLong() + delayMs
            if (posMs in s..e) return c.text
            if (s > posMs) break
        }
        return ""
    }
}

/**
 * The one player behind the window. It lives in the app (not the window) so a video set to "headphone" mode keeps playing with the window
 * closed, the screen locked or another app in front. It holds the play list, shuffle / repeat, speed, and remembers where each file stopped.
 */
object PlayerEngine {
    interface Listener {
        fun onTrack() {}
        fun onPrepared() {}
        fun onState() {}
        fun onVideoSize(w: Int, h: Int) {}
        fun onError(msg: String) {}
        fun onList() {}
    }

    val items = ArrayList<PlayItem>()
    var index = -1; private set
    var shuffle = false
    var repeat = 0                 // 0 off, 1 this one, 2 all
    var speed = 1f; private set
    var audioOnly = false
    var prepared = false; private set
    var videoW = 0; private set
    var videoH = 0; private set
    var mp: MediaPlayer? = null; private set
    var audioOff = false; private set
    private var display: SurfaceHolder? = null
    private val history = ArrayList<Int>()
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    val listeners = ArrayList<Listener>()
    val durations = HashMap<String, Long>()
    private var pendingSeek = 0

    val current: PlayItem? get() = items.getOrNull(index)
    val playing: Boolean get() = try { mp?.isPlaying == true } catch (_: Exception) { false }
    val position: Int get() = try { if (prepared) mp?.currentPosition ?: 0 else 0 } catch (_: Exception) { 0 }
    val duration: Int get() = try { if (prepared) mp?.duration ?: 0 else 0 } catch (_: Exception) { 0 }
    val active: Boolean get() = mp != null

    private fun each(f: Listener.() -> Unit) { for (l in ArrayList(listeners)) try { l.f() } catch (_: Exception) {} }

    /** Replace the play list and start item [start]. */
    fun load(c: Context, list: List<PlayItem>, start: Int) {
        app = c.applicationContext
        items.clear(); items.addAll(list); history.clear()
        each { onList() }
        play(start)
    }

    fun play(i: Int) {
        val c = app ?: return
        if (i !in items.indices) return
        saveNow()
        release(false)
        index = i
        SubState.clear()
        val it = items[i]
        it.path?.let { p -> findSubtitle(p) }
        each { onTrack() }
        try {
            val m = MediaPlayer()
            mp = m; prepared = false; videoW = 0; videoH = 0
            if (it.path != null) m.setDataSource(it.path) else m.setDataSource(c, it.uri)
            if (!audioOnly && display?.surface?.isValid == true) m.setDisplay(display)
            m.setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; each { onVideoSize(w, h) } }
            m.setOnPreparedListener { pm ->
                prepared = true
                val saved = Store.str(c, "pos:" + it.key, "0").toIntOrNull() ?: 0
                val dur = pm.duration
                pendingSeek = if (saved > 2000 && saved < dur - 5000) saved else 0
                if (pendingSeek > 0) pm.seekTo(pendingSeek)
                pm.isLooping = repeat == 1
                applySpeed(pm)
                if (audioOff) pm.setVolume(0f, 0f)
                pm.start()
                durations[it.key] = dur.toLong()
                each { onPrepared() }; each { onState() }
            }
            m.setOnCompletionListener { done() }
            m.setOnErrorListener { _, _, _ -> each { onError("This format or file could not be played") }; true }
            m.prepareAsync()
        } catch (e: Exception) { each { onError("Could not open this file") } }
    }

    private fun done() {
        val c = app
        current?.let { if (c != null) Store.setStr(c, "pos:" + it.key, "0") }
        if (repeat == 1) return
        if (hasNext() || repeat == 2) next(true) else each { onState() }
    }

    fun hasNext() = shuffle && items.size > 1 || index < items.size - 1
    fun hasPrev() = history.isNotEmpty() || index > 0

    fun next(auto: Boolean = false) {
        if (items.isEmpty()) return
        history.add(index)
        if (history.size > 200) history.removeAt(0)
        val n = if (shuffle && items.size > 1) { var r: Int; do { r = (Math.random() * items.size).toInt() } while (r == index); r }
                else if (index < items.size - 1) index + 1 else 0
        play(n)
    }

    fun prev() {
        if (items.isEmpty()) return
        if (position > 3000) { seekTo(0); return }
        val n = if (history.isNotEmpty()) history.removeAt(history.size - 1) else if (index > 0) index - 1 else items.size - 1
        play(n)
    }

    fun toggle() {
        val m = mp ?: return
        if (!prepared) return
        try { if (m.isPlaying) { m.pause(); saveNow() } else m.start() } catch (_: Exception) {}
        each { onState() }
    }

    fun pause() { try { if (prepared && mp?.isPlaying == true) { mp?.pause(); saveNow() } } catch (_: Exception) {}; each { onState() } }
    fun start() { try { if (prepared) mp?.start() } catch (_: Exception) {}; each { onState() } }

    fun seekTo(ms: Int) { try { if (prepared) mp?.seekTo(ms.coerceIn(0, duration)) } catch (_: Exception) {} }
    fun skip(sec: Int) { if (prepared) seekTo(position + sec * 1000) }

    fun setSpeed(v: Float) { speed = v.coerceIn(0.2f, 4f); mp?.let { if (prepared) applySpeed(it) } }
    private fun applySpeed(m: MediaPlayer) {
        if (android.os.Build.VERSION.SDK_INT < 23) return
        try {
            val wasPlaying = m.isPlaying
            m.playbackParams = m.playbackParams.setSpeed(speed)
            if (!wasPlaying && prepared && m.isPlaying) m.pause()   // setting the speed must not start a paused video
        } catch (_: Exception) { each { onError("This device cannot change speed") } }
    }

    fun changeRepeat(r: Int) { repeat = r; try { mp?.isLooping = r == 1 } catch (_: Exception) {} }

    fun setDisplay(h: SurfaceHolder?) {
        display = h
        try { mp?.setDisplay(if (audioOnly) null else h) } catch (_: Exception) {}
    }

    fun changeAudioOnly(on: Boolean) {
        audioOnly = on
        try { mp?.setDisplay(if (on) null else display) } catch (_: Exception) {}
    }

    // ----- audio tracks (as far as the device's MediaPlayer allows) -----
    class Track(val index: Int, val label: String)

    fun audioTracks(): List<Track> {
        val m = mp ?: return emptyList()
        if (!prepared) return emptyList()
        return try {
            val out = ArrayList<Track>(); var n = 0
            m.trackInfo.forEachIndexed { i, t ->
                if (t.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    n++
                    val lang = t.language
                    out.add(Track(i, (if (lang.isNullOrEmpty() || lang == "und") "" else java.util.Locale(lang).displayLanguage + " - ") + "Audio Track #" + n))
                }
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    fun selectedAudio(): Int = try { if (audioOff) -1 else mp?.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) ?: -1 } catch (_: Exception) { -1 }

    fun selectAudio(index: Int) {
        val m = mp ?: return
        audioOff = false
        try { m.setVolume(1f, 1f); m.selectTrack(index) } catch (_: Exception) { each { onError("This device cannot switch the audio track") } }
    }

    /** "Disable": the track stays but is silent (the system player cannot really remove one). */
    fun muteAudio() { audioOff = true; try { mp?.setVolume(0f, 0f) } catch (_: Exception) {} }

    // ----- play list edits -----
    fun removeAt(i: Int) {
        if (i !in items.indices) return
        val wasCurrent = i == index
        items.removeAt(i)
        history.clear()
        if (items.isEmpty()) { release(true); each { onList() }; return }
        if (wasCurrent) { index = i.coerceAtMost(items.size - 1); each { onList() }; play(index); return }
        if (i < index) index--
        each { onList() }
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        val it = items.removeAt(from); items.add(to, it)
        index = when { index == from -> to; from < index && to >= index -> index - 1; from > index && to <= index -> index + 1; else -> index }
        history.clear()
    }

    /** Length of a local file, found in the background and kept; [then] runs on the main thread. */
    fun duration(item: PlayItem, then: (Long) -> Unit) {
        durations[item.key]?.let { then(it); return }
        val p = item.path ?: return
        Thread {
            var d = 0L
            try { val r = MediaMetadataRetriever(); r.setDataSource(p); d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L; r.release() } catch (_: Throwable) {}
            durations[item.key] = d
            main.post { then(d) }
        }.start()
    }

    // ----- where each file stopped -----
    fun saveNow() {
        val c = app ?: return; val it = current ?: return
        if (!prepared) return
        val pos = position; val dur = duration
        Store.setStr(c, "pos:" + it.key, if (pos > 2000 && pos < dur - 5000) pos.toString() else "0")
    }

    private fun findSubtitle(path: String) {
        try {
            val f = File(path); val base = f.nameWithoutExtension
            val s = File(f.parentFile, "$base.srt")
            if (s.isFile) { SubState.cues = Srt.parse(s.readText()); SubState.name = s.name }
        } catch (_: Exception) {}
    }

    fun release(clearList: Boolean) {
        saveNow()
        try { mp?.setOnCompletionListener(null); mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        mp = null; prepared = false
        if (clearList) { items.clear(); index = -1; history.clear(); audioOnly = false; audioOff = false }
        each { onState() }
    }
}

/** Keeps the sound going (and the app alive) while a video plays in "headphone" mode with its window out of sight. */
class PlayerService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val listener = object : PlayerEngine.Listener {
        override fun onState() { refresh() }
        override fun onTrack() { refresh() }
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun build(): Notification {
        val e = PlayerEngine
        val open = PendingIntent.getActivity(this, 0, Intent(this, VideoPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun act(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, PlayerService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val b = if (android.os.Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "player") else Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(e.current?.title ?: "Desktop Files").setContentText(T.t("Playing in the background"))
            .setOngoing(e.playing).setOnlyAlertOnce(true).setContentIntent(open)
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            b.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, if (e.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), T.t(if (e.playing) "Pause" else "Play"), act("toggle", 1)).build())
            b.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), T.t("Close"), act("stop", 2)).build())
        }
        return b.build()
    }

    private fun refresh() {
        if (!PlayerEngine.active) { stopForeground(true); stopSelf(); return }
        try { getSystemService(NotificationManager::class.java).notify(7, build()) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "toggle" -> PlayerEngine.toggle()
            "stop" -> { PlayerEngine.release(true); stopForeground(true); stopSelf(); return START_NOT_STICKY }
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("player", "Media playback", NotificationManager.IMPORTANCE_LOW))
        try { if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(7, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(7, build()) } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (!PlayerEngine.listeners.contains(listener)) PlayerEngine.listeners.add(listener)
        return START_NOT_STICKY
    }

    /** The app was swiped away from the recent apps: stop, as the sound is meant to last only until then. */
    override fun onTaskRemoved(rootIntent: Intent?) { PlayerEngine.release(true); stopForeground(true); stopSelf() }

    override fun onDestroy() { PlayerEngine.listeners.remove(listener); h.removeCallbacksAndMessages(null); super.onDestroy() }
}
