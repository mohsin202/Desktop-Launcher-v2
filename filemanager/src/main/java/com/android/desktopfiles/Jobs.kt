package com.android.desktopfiles

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** One running copy / move / delete. Progress is counted in bytes. */
class Job(val title: String) {
    @Volatile var total = 0L
    @Volatile var done = 0L
    @Volatile var detail = ""
    @Volatile var cancelled = false
    @Volatile var finished = false
    @Volatile var failed = 0
    @Volatile var startedAt = System.currentTimeMillis()
    val fraction: Float get() = if (total <= 0) (if (finished) 1f else 0f) else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** The list of jobs (the Status Center shows it). All callbacks arrive on the main thread. */
object Jobs {
    val list = CopyOnWriteArrayList<Job>()
    private val h = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    fun changed() { h.post { for (l in listeners) l() } }

    fun active() = list.any { !it.finished }
    fun fraction(): Float {
        val act = list.filter { !it.finished }
        if (act.isEmpty()) return 1f
        val t = act.sumOf { it.total }
        return if (t <= 0) 0f else (act.sumOf { it.done }.toFloat() / t).coerceIn(0f, 1f)
    }

    /** Runs [work] on a worker thread; [then] runs on the main thread when it ends. */
    fun start(job: Job, work: (Job) -> Unit, then: () -> Unit = {}) {
        list.add(job)
        changed()
        Thread {
            try { work(job) } catch (_: Throwable) { job.failed++ }
            job.finished = true
            changed()
            h.post {
                then()
                // finished jobs stay in the list for a few seconds, then disappear
                h.postDelayed({ list.remove(job); changed() }, 6000)
            }
        }.start()
    }

    fun cancelAll() { for (j in list) if (!j.finished) j.cancelled = true; changed() }
}

object Ops {
    fun sizeOf(f: File): Long = try { if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length() } catch (_: Exception) { 0L }

    private fun copyFile(src: File, dst: File, job: Job) {
        src.inputStream().use { i -> dst.outputStream().use { o ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                if (job.cancelled) return
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                job.done += n
            }
        } }
        dst.setLastModified(src.lastModified())
    }

    private fun copyTree(src: File, dst: File, job: Job) {
        if (job.cancelled) return
        job.detail = src.name
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyTree(it, File(dst, it.name), job) }
        } else copyFile(src, dst, job)
    }

    /** Copies or moves [files] into [dest]. A name that already exists gets "(2)" (Keep both). */
    fun transfer(files: List<File>, dest: File, move: Boolean, job: Job) {
        job.total = files.sumOf { sizeOf(it) }
        for (src in files) {
            if (job.cancelled) return
            if (!src.exists()) continue
            if (src.isDirectory && (dest.absolutePath == src.absolutePath || dest.absolutePath.startsWith(src.absolutePath + "/"))) { job.failed++; continue }
            if (move && src.parentFile?.absolutePath == dest.absolutePath) continue
            val target = Fs.unique(dest, src.name)
            job.detail = src.name
            try {
                if (move) {
                    val sz = sizeOf(src)
                    if (src.renameTo(target)) job.done += sz
                    else { copyTree(src, target, job); if (!job.cancelled) src.deleteRecursively() }
                } else copyTree(src, target, job)
            } catch (_: Exception) { job.failed++ }
        }
    }

    fun deleteForever(files: List<File>, job: Job) {
        job.total = files.size.toLong()
        for (f in files) {
            if (job.cancelled) return
            job.detail = f.name
            if (!f.deleteRecursively()) job.failed++
            job.done++
        }
    }

    /** Pastes what the clipboard holds into [dest]. Returns the job (already running). */
    fun paste(c: Context, dest: File, then: () -> Unit): Boolean {
        val cl = Clip.read(c) ?: return false
        val n = cl.files.size
        val job = Job((if (cl.cut) "Moving " else "Copying ") + n + (if (n == 1) " item to " else " items to ") + dest.name)
        Jobs.start(job, { transfer(cl.files, dest, cl.cut, it) }) {
            if (cl.cut && job.failed == 0 && !job.cancelled) Clip.clear(c)
            then()
        }
        return true
    }
}
