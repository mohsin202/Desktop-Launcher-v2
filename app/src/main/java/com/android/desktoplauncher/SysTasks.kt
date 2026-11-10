package com.android.desktoplauncher

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/**
 * What the launcher can do when it is installed as a system (privileged) app, e.g. in BassOS: ask the system itself which
 * tasks exist (the same list as the Recents screen), remove tasks, and put a task into a freeform window. As an ordinary
 * app none of this is allowed; every call here then quietly does nothing (or returns null) and the older
 * accessibility-based behaviour is used instead.
 */
object SysTasks {
    class T(val id: Int, val pkg: String, val mode: Int)

    /** True when the app is installed in the system partition (system / priv-app, or an updated copy of one). */
    fun isSystem(c: Context): Boolean =
        (c.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    fun hasRealTasks(c: Context): Boolean =
        c.checkSelfPermission("android.permission.REAL_GET_TASKS") == PackageManager.PERMISSION_GRANTED

    /** Set once the system has shown us another app's task: from then on the list can be trusted even when it is empty. */
    @Volatile private var sawForeign = false

    /** Whether the task list is the system's own (true) or only our own tasks (ordinary app, false). */
    fun trusted(c: Context): Boolean = sawForeign || hasRealTasks(c)

    /** The tasks of the system's Recents list, most recent first; null when the system does not show other apps' tasks to us. */
    @Suppress("DEPRECATION")
    fun tasks(c: Context): List<T>? {
        if (!isSystem(c) && !hasRealTasks(c)) return null
        return try {
            val am = c.getSystemService(ActivityManager::class.java) ?: return null
            val out = ArrayList<T>()
            for (ti in am.getRunningTasks(60)) {
                val comp = ti.baseActivity ?: ti.topActivity ?: continue
                val pkg = comp.packageName
                if (pkg != c.packageName) sawForeign = true
                out.add(T(ti.id, pkg, windowingMode(ti)))
            }
            if (trusted(c)) out else null
        } catch (_: Throwable) { null }
    }

    private fun windowingMode(ti: Any): Int = try {
        (ti.javaClass.getMethod("getWindowingMode").invoke(ti) as Int)
    } catch (_: Throwable) {
        try { ti.javaClass.getField("windowingMode").getInt(ti) } catch (_: Throwable) { 0 }
    }

    private fun atms(): Any? = try {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
    } catch (_: Throwable) { null }

    /** Removes a task (what swiping an app away in Recents does). */
    fun remove(id: Int): Boolean = try {
        val s = atms() ?: return false
        s.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(s, id); true
    } catch (_: Throwable) { false }

    fun removeApp(c: Context, pkg: String): Boolean {
        val l = tasks(c) ?: return false
        var any = false
        for (t in l) if (t.pkg == pkg) any = remove(t.id) || any
        return any
    }

    fun setMode(id: Int, mode: Int): Boolean = try {
        val s = atms() ?: return false
        s.javaClass.getMethod("setTaskWindowingMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .invoke(s, id, mode, false); true
    } catch (_: Throwable) { false }

    /**
     * After an app was started: if its task did not come up as a freeform window (windowing mode 5), switches it to one.
     * Checked twice because the task appears a moment after the start.
     */
    fun ensureFreeform(c: Context, pkg: String) {
        if (!isSystem(c)) return
        val app = c.applicationContext
        val h = Handler(Looper.getMainLooper())
        for (delay in longArrayOf(700, 1800)) h.postDelayed({
            Thread {
                val l = tasks(app) ?: return@Thread
                for (t in l) if (t.pkg == pkg && t.mode != 5 && t.mode != 0) setMode(t.id, 5)
            }.start()
        }, delay)
    }
}

/** A root shell, used (only when the hidden window-mode call is refused) to start an app in a freeform window. */
object RootSh {
    @Volatile private var ok: Boolean? = null

    /** The answer of an earlier check; false while it is still unknown (never blocks). */
    fun known(): Boolean = ok == true

    /** Checks in the background once, so that [known] has an answer by the time an app is opened. */
    fun probe() { if (ok == null) Thread { available() }.start() }

    /** May block (a root prompt can appear); use [known] on the main thread. */
    fun available(): Boolean {
        ok?.let { return it }
        val r = try { run("id") ?.contains("uid=0") == true } catch (_: Throwable) { false }
        ok = r; return r
    }

    fun run(cmd: String): String? = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val s = p.inputStream.bufferedReader().readText()
        p.waitFor(); s
    } catch (_: Throwable) { null }

    /** `am start` in the freeform windowing mode (5). Runs in the background. */
    fun startFreeform(comp: ComponentName) {
        Thread { run("am start --windowingMode 5 -n " + comp.flattenToShortString()) }.start()
    }
}
