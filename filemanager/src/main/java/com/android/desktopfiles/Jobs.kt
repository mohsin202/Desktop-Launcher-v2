package com.android.desktopfiles

import android.content.Context
import android.content.Intent
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
    /** Set once by the window; lets a job start the notification service. */
    @Volatile var appCtx: Context? = null

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
        appCtx?.let { try { val si = Intent(it, JobsService::class.java); if (android.os.Build.VERSION.SDK_INT >= 26) it.startForegroundService(si) else it.startService(si) } catch (_: Exception) {} }
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

    private fun rootUnique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!RootSu.exists(f)) return f
        val base = name.substringBeforeLast('.', name)
        val e = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 2
        while (RootSu.exists(f)) { f = File(dir, "$base ($i)$e"); i++ }
        return f
    }

    /** Copies or moves [files] into [dest]. A name that already exists gets "(2)" (Keep both). */
    fun transfer(files: List<File>, dest: File, move: Boolean, job: Job) {
        job.total = files.sumOf { sizeOf(it) }
        val countMode = job.total == 0L
        if (countMode) job.total = files.size.toLong()
        for (src in files) {
            if (job.cancelled) return
            // Items the app cannot reach itself (system folders) go through su
            if (RootSu.ensure() && (src is RootFile || !src.canRead() || !dest.canWrite())) {
                job.detail = src.name
                val t = rootUnique(dest, src.name)
                val ok = RootSu.sh((if (move) "mv " else "cp -a ") + RootSu.q(src) + " " + RootSu.q(t))
                if (!ok) job.failed++ else if (move) Tags.move(src.absolutePath, t.absolutePath)
                job.done += if (countMode) 1L else maxOf(1L, sizeOf(src))
                continue
            }
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
                    if (!job.cancelled) Tags.move(src.absolutePath, target.absolutePath)
                } else copyTree(src, target, job)
            } catch (_: Exception) { job.failed++ }
        }
    }

    /** Packs [files] into the zip file [out]. */
    fun zip(files: List<File>, out: File, job: Job) {
        job.total = files.sumOf { sizeOf(it) }
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zo ->
            val buf = ByteArray(256 * 1024)
            fun add(f: File, name: String) {
                if (job.cancelled) return
                if (f.isDirectory) {
                    val kids = f.listFiles()
                    if (kids.isNullOrEmpty()) { zo.putNextEntry(java.util.zip.ZipEntry("$name/")); zo.closeEntry() }
                    else for (k in kids) add(k, name + "/" + k.name)
                } else {
                    job.detail = f.name
                    val e = java.util.zip.ZipEntry(name); e.time = f.lastModified()
                    zo.putNextEntry(e)
                    f.inputStream().use { i -> while (true) { if (job.cancelled) break; val n = i.read(buf); if (n < 0) break; zo.write(buf, 0, n); job.done += n } }
                    zo.closeEntry()
                }
            }
            for (f in files) add(f, f.name)
        }
        if (job.cancelled) out.delete()
    }

    /** Unpacks [zip] into [dest] (entries that would leave the folder are skipped). */
    fun unzip(zip: File, dest: File, job: Job) {
        dest.mkdirs()
        val base = dest.canonicalPath
        java.util.zip.ZipFile(zip).use { zf ->
            job.total = zf.entries().asSequence().sumOf { maxOf(0L, it.size) }
            val buf = ByteArray(256 * 1024)
            for (e in zf.entries()) {
                if (job.cancelled) return
                val t = File(dest, e.name)
                if (!t.canonicalPath.startsWith(base + File.separator)) { job.failed++; continue }
                job.detail = t.name
                if (e.isDirectory) { t.mkdirs(); continue }
                t.parentFile?.mkdirs()
                zf.getInputStream(e).use { i -> t.outputStream().use { o -> while (true) { if (job.cancelled) break; val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); job.done += n } } }
                if (e.time > 0) t.setLastModified(e.time)
            }
        }
    }

    fun deleteForever(files: List<File>, job: Job) {
        job.total = files.size.toLong()
        for (f in files) {
            if (job.cancelled) return
            job.detail = f.name
            if (!f.deleteRecursively()) {
                if (!(RootSu.ensure() && RootSu.sh("rm -rf " + RootSu.q(f)))) job.failed++
            }
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
