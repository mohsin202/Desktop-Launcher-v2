package com.android.desktopfiles

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Installing apps made of several parts (a base APK and split APKs): a folder called "*.apks", or a zip-like file ending in
 * .apks / .apkm / .apk+ / .xapk, or a single .apk. The system's package installer does the install; this only hands it all the parts in one session.
 * A Windows-style window lets the person tick the parts: the ones this phone needs are ticked already.
 */
object Apks {
    val ZIPS = setOf("apks", "apkm", "apk+", "xapk")
    private const val BASE = 0
    private const val ARCH = 1
    private const val DPI = 2
    private const val LANG = 3
    private const val FEATURE = 4

    fun installable(f: File): Boolean =
        if (f.isDirectory) f.name.lowercase().endsWith(".apks") else f.extension.lowercase().let { it == "apk" || it in ZIPS }

    /** Files that only this installer can open (a plain .apk still opens with the system). */
    fun isBundle(f: File): Boolean = !f.isDirectory && f.extension.lowercase() in ZIPS

    class Part(val name: String, val size: Long, val group: Int, val title: String, val open: () -> InputStream) { var on = false }
    class Pack(val parts: List<Part>, val pkg: String, val label: String, val version: String, val icon: Bitmap?, val abis: List<String>, val dpi: Int)

    private class Raw(val name: String, val size: Long, val path: File?, val open: () -> InputStream)

    private fun raws(f: File): List<Raw> = when {
        f.isDirectory -> (f.listFiles() ?: emptyArray()).filter { it.isFile && it.name.lowercase().endsWith(".apk") }.sortedBy { it.name }
            .map { x -> Raw(x.name, x.length(), x) { FileInputStream(x) } }
        f.extension.lowercase() == "apk" -> listOf(Raw(f.name, f.length(), f) { FileInputStream(f) })
        else -> ZipFile(f).use { z ->
            z.entries().toList().filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") && !it.name.startsWith("Android/") }.map { e ->
                val en = e.name
                Raw(en.substringAfterLast('/'), e.size, null) {
                    val zz = ZipFile(f)
                    object : FilterInputStream(zz.getInputStream(zz.getEntry(en))) { override fun close() { try { super.close() } finally { zz.close() } } }
                }
            }
        }
    }

    private val ABIS = setOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips", "mips64")
    private val DPIS = mapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640, "nodpi" to 0, "anydpi" to 0)
    private val LANGRE = Regex("^[a-z]{2,3}(_r?[a-z]{2,4})?$")

    private fun norm(n: String) = n.lowercase().removeSuffix(".apk").replace('-', '_')
    private fun isSplitName(s: String) = s.startsWith("split_") || s.startsWith("config.") || s.contains("config.")
    private fun tokenOf(s: String) = if (s.contains("config.")) s.substringAfter("config.") else s.removePrefix("split_")

    private fun bitmapOf(d: android.graphics.drawable.Drawable, px: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px); d.draw(Canvas(b)); return b
    }

    /** Reads the parts and the app's name and icon (slow: run it away from the main thread). */
    fun read(c: Context, f: File): Pack? {
        val rs = raws(f)
        if (rs.isEmpty()) return null
        val nonSplit = rs.filter { !isSplitName(norm(it.name)) }
        val base = nonSplit.firstOrNull { norm(it.name) == "base" } ?: nonSplit.maxByOrNull { it.size } ?: rs.maxByOrNull { it.size }!!
        // the base APK is needed as a real file to read its name and icon
        val probe = base.path ?: File(c.cacheDir, "probe.apk").also { out -> base.open().use { i -> out.outputStream().use { o -> i.copyTo(o) } } }
        val pm = c.packageManager
        val info = pm.getPackageArchiveInfo(probe.absolutePath, 0)
        val ai = info?.applicationInfo?.also { it.sourceDir = probe.absolutePath; it.publicSourceDir = probe.absolutePath }
        val pkg = info?.packageName ?: base.name
        val label = try { ai?.loadLabel(pm)?.toString() } catch (_: Exception) { null } ?: pkg
        val icon = try { ai?.let { bitmapOf(it.loadIcon(pm), c.dp(72)) } } catch (_: Throwable) { null }
        val parts = ArrayList<Part>()
        for (r in rs) {
            val s = norm(r.name)
            val tok = tokenOf(s)
            val p = when {
                r === base -> Part(r.name, r.size, BASE, pkg, r.open)
                !isSplitName(s) -> Part(r.name, r.size, FEATURE, T.t("Another APK") + ": " + r.name, r.open)
                tok in ABIS -> Part(r.name, r.size, ARCH, tok + " " + T.t("code for base APK"), r.open)
                tok in DPIS -> Part(r.name, r.size, DPI, tok + (if (DPIS[tok]!! > 0) " (" + DPIS[tok] + " DPI)" else "") + " " + T.t("resources for base APK"), r.open)
                LANGRE.matches(tok) -> Part(r.name, r.size, LANG, Locale(tok.substringBefore('_')).getDisplayLanguage(Locale.ENGLISH).ifEmpty { tok } + " (" + tok + ")", r.open)
                else -> Part(r.name, r.size, FEATURE, T.t("Feature") + ": " + tok, r.open)
            }
            parts.add(p)
        }
        // what is ticked: everything the phone needs, best match only for code and screen density
        val abis = Build.SUPPORTED_ABIS.map { it.lowercase().replace('-', '_') }
        val dpi = c.resources.displayMetrics.densityDpi
        for (p in parts) if (p.group == BASE || p.group == FEATURE) p.on = true
        val archParts = parts.filter { it.group == ARCH }
        val bestAbi = abis.firstOrNull { a -> archParts.any { tokenOf(norm(it.name)) == a } }
        for (p in archParts) p.on = tokenOf(norm(p.name)) == bestAbi
        val dpiParts = parts.filter { it.group == DPI }
        for (p in dpiParts) if (DPIS[tokenOf(norm(p.name))] == 0) p.on = true
        val sized = dpiParts.filter { (DPIS[tokenOf(norm(it.name))] ?: 0) > 0 }
        val best = sized.filter { DPIS[tokenOf(norm(it.name))]!! >= dpi }.minByOrNull { DPIS[tokenOf(norm(it.name))]!! } ?: sized.maxByOrNull { DPIS[tokenOf(norm(it.name))]!! }
        best?.on = true
        val langs = ArrayList<String>()
        val loc = c.resources.configuration.locales
        for (i in 0 until loc.size()) langs.add(loc[i].language.lowercase())
        for (p in parts.filter { it.group == LANG }) p.on = tokenOf(norm(p.name)).substringBefore('_') in langs
        return Pack(parts.sortedWith(compareBy({ it.group }, { it.name })), pkg, label, info?.versionName ?: "", icon, abis, dpi)
    }

    // ---------- the windows ----------
    fun install(act: Activity, f: File) {
        c(act).toast("Reading the app…")
        Thread {
            val pack = try { read(act, f) } catch (_: Throwable) { null }
            act.runOnUiThread {
                if (pack == null) act.toast("This file has no app to install") else choose(act, pack)
            }
        }.start()
    }

    private fun c(a: Activity): Context = a

    private fun header(act: Activity, p: Pal, title: String, pack: Pack, root: LinearLayout): TextView {
        val t = Ui.text(act, title, 20f, p.accent, true); t.gravity = Gravity.CENTER
        root.addView(t, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = act.dp(12) })
        if (pack.icon != null) {
            val iv = ImageView(act); iv.setImageBitmap(pack.icon)
            root.addView(iv, LinearLayout.LayoutParams(act.dp(60), act.dp(60)).also { it.gravity = Gravity.CENTER_HORIZONTAL })
        }
        val n = Ui.text(act, pack.label, 16f, p.text, true, false); n.gravity = Gravity.CENTER
        root.addView(n, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = act.dp(6) })
        if (pack.version.isNotEmpty()) root.addView(Ui.text(act, pack.version, 13f, p.sub, false, false).also { it.gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        return t
    }

    private fun choose(act: Activity, pack: Pack) {
        val p = Ui.pal(act)
        val sv = ScrollView(act)
        val root = LinearLayout(act); root.orientation = LinearLayout.VERTICAL; root.setPadding(act.dp(20), act.dp(18), act.dp(20), act.dp(4))
        sv.addView(root)
        header(act, p, "Customize installation", pack, root)
        val note = Ui.text(act, "You can select what split APK parts to install, best matching parts were selected automatically", 13f, p.text); note.gravity = Gravity.CENTER
        root.addView(note, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = act.dp(12); it.bottomMargin = act.dp(6) })
        fun section(group: Int, title: String, desc: String?) {
            val ps = pack.parts.filter { it.group == group }
            if (ps.isEmpty()) return
            root.addView(Ui.text(act, title, 15f, p.text, true).atStart(), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = act.dp(12) })
            if (desc != null) root.addView(Ui.text(act, desc, 13f, p.sub, false, false).atStart(), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = act.dp(2) })
            for (part in ps) {
                val row = LinearLayout(act); row.gravity = Gravity.CENTER_VERTICAL; row.setPadding(0, act.dp(6), 0, act.dp(6))
                val col = LinearLayout(act); col.orientation = LinearLayout.VERTICAL
                col.addView(Ui.text(act, part.title, 14f, p.text, false, false).atStart())
                col.addView(Ui.text(act, Fs.size(act, part.size), 12f, p.sub, false, false).atStart())
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                val cb = CheckBox(act); cb.isChecked = part.on; cb.buttonTintList = android.content.res.ColorStateList.valueOf(p.accent)
                if (group == BASE) { cb.isChecked = true; cb.isEnabled = false } else cb.setOnCheckedChangeListener { _, v -> part.on = v }
                row.addView(cb)
                if (group != BASE) { row.isClickable = true; row.setOnClickListener { cb.isChecked = !cb.isChecked } }
                root.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        section(BASE, "Base APK", null)
        section(ARCH, "Configuration: Architecture", T.t("Your device supports (from most to least preferred):") + " " + pack.abis.joinToString(", "))
        section(DPI, "Configuration: DPI Resources", T.t("Your device DPI is") + " " + pack.dpi)
        section(LANG, "Configuration: Language", null)
        section(FEATURE, "Optional features", null)
        AlertDialog.Builder(act).setView(DeskScroll.wrap(sv, p))
            .setPositiveButton(T.t("Install")) { _, _ -> run(act, pack, pack.parts.filter { it.on || it.group == BASE }) }
            .setNegativeButton(T.t("Cancel"), null).showDir()
    }

    private fun run(act: Activity, pack: Pack, chosen: List<Part>) {
        val pmgr = act.packageManager
        if (android.os.Build.VERSION.SDK_INT >= 26 && !pmgr.canRequestPackageInstalls()) {
            act.toast("Allow this app to install apps, then try again")
            try { act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName))) } catch (_: Exception) {}
            return
        }
        val p = Ui.pal(act)
        val sv = ScrollView(act)
        val root = LinearLayout(act); root.orientation = LinearLayout.VERTICAL; root.setPadding(act.dp(20), act.dp(18), act.dp(20), act.dp(4))
        sv.addView(root)
        val titleView = header(act, p, "Installing", pack, root)
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal); bar.max = 1000
        bar.progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
        root.addView(bar, LinearLayout.LayoutParams(-1, act.dp(10)).also { it.topMargin = act.dp(16); it.bottomMargin = act.dp(14) })
        val box = LinearLayout(act); box.orientation = LinearLayout.VERTICAL; box.setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(10))
        box.background = Ui.rounded(0, act.dpf(12f), p.accent, act.dp(2))
        box.addView(Ui.text(act, "Details", 17f, p.accent, true).atStart())
        val pcts = ArrayList<TextView>()
        for (part in chosen) {
            val r = LinearLayout(act); r.setPadding(0, act.dp(5), 0, 0)
            r.addView(Ui.text(act, part.title, 13f, p.text, false, false).atStart(), LinearLayout.LayoutParams(0, -2, 1f))
            val t = Ui.text(act, "0%", 13f, p.accent, false, false); pcts.add(t); r.addView(t)
            box.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
        val result = Ui.text(act, "", 13f, p.warn, false, false).atStart(); result.visibility = View.GONE
        box.addView(result, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = act.dp(8) })
        root.addView(box, LinearLayout.LayoutParams(-1, -2))

        val pi = act.packageManager.packageInstaller
        var session: PackageInstaller.Session? = null
        var cancelled = false
        val dlg = AlertDialog.Builder(act).setView(DeskScroll.wrap(sv, p)).setCancelable(false)
            .setPositiveButton(T.t("Installing"), null)
            .setNegativeButton(T.t("Cancel")) { _, _ -> cancelled = true; try { session?.abandon() } catch (_: Exception) {} }.showDir()
        val ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE); ok.isEnabled = false; ok.alpha = 0.6f

        fun finish(good: Boolean, msg: String) {
            act.runOnUiThread {
                if (good) { bar.progress = 1000; titleView.text = T.t("Installed") } else { result.text = msg; result.visibility = View.VISIBLE }
                ok.text = T.t(if (good) "Done" else "Close"); ok.isEnabled = true; ok.alpha = 1f
                ok.setOnClickListener { dlg.dismiss() }
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).visibility = View.GONE
            }
        }

        Thread {
            var rcv: BroadcastReceiver? = null
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (pack.pkg.contains('.')) params.setAppPackageName(pack.pkg)
                if (android.os.Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = pi.createSession(params)
                val s = pi.openSession(id); session = s
                val total = chosen.sumOf { maxOf(it.size, 0L) }.coerceAtLeast(1L)
                var written = 0L
                for ((idx, part) in chosen.withIndex()) {
                    if (cancelled) return@Thread
                    s.openWrite(part.name, 0, part.size).use { out ->
                        part.open().use { inp ->
                            val buf = ByteArray(256 * 1024); var mine = 0L; var lastPct = -1
                            while (true) {
                                if (cancelled) return@Thread
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); mine += n; written += n
                                val pct = if (part.size > 0) (mine * 100 / part.size).toInt().coerceAtMost(100) else 0
                                if (pct != lastPct) {
                                    lastPct = pct
                                    val ov = (written * 1000 / total).toInt()
                                    act.runOnUiThread { pcts[idx].text = "$pct%"; bar.progress = ov }
                                }
                            }
                        }
                        s.fsync(out)
                    }
                    act.runOnUiThread { pcts[idx].text = "100%" }
                }
                val action = "com.android.desktopfiles.INSTALL_" + id
                val r = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, i: Intent) {
                        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                @Suppress("DEPRECATION") val x = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                try { x?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); if (x != null) ctx.startActivity(x) } catch (_: Exception) {}
                            }
                            PackageInstaller.STATUS_SUCCESS -> { try { act.unregisterReceiver(this) } catch (_: Exception) {}; finish(true, "") }
                            else -> { try { act.unregisterReceiver(this) } catch (_: Exception) {}; finish(false, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation failed") }
                        }
                    }
                }
                rcv = r
                Compat.registerPrivate(act, r, IntentFilter(action))
                val pend = PendingIntent.getBroadcast(act, id, Intent(action).setPackage(act.packageName), Compat.MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(pend.intentSender)
            } catch (e: Throwable) {
                try { rcv?.let { act.unregisterReceiver(it) } } catch (_: Exception) {}
                try { session?.abandon() } catch (_: Exception) {}
                finish(false, e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    // ---------- packing an installed app (Apps page: copy APK) ----------
    /** All the parts of an installed app into one zip: base.apk, the split APKs, icon.png and info.json. */
    fun pack(c: Context, ai: ApplicationInfo, label: String, version: String, out: File, j: Job) {
        val files = listOf(File(ai.sourceDir)) + (ai.splitSourceDirs ?: emptyArray()).map { File(it) }
        j.total = files.sumOf { it.length() }
        ZipOutputStream(out.outputStream().buffered()).use { zo ->
            zo.setLevel(1)
            for (f in files) {
                if (j.cancelled) break
                j.detail = f.name
                zo.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { i ->
                    val buf = ByteArray(256 * 1024)
                    while (true) { if (j.cancelled) break; val n = i.read(buf); if (n < 0) break; zo.write(buf, 0, n); j.done += n }
                }
                zo.closeEntry()
            }
            try {
                val bmp = bitmapOf(ai.loadIcon(c.packageManager), 192)
                zo.putNextEntry(ZipEntry("icon.png")); bmp.compress(Bitmap.CompressFormat.PNG, 100, zo); zo.closeEntry()
            } catch (_: Throwable) {}
            zo.putNextEntry(ZipEntry("info.json"))
            fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            zo.write(("{\"package\":" + q(ai.packageName) + ",\"label\":" + q(label) + ",\"versionName\":" + q(version) + "}").toByteArray())
            zo.closeEntry()
        }
        if (j.cancelled) out.delete()
    }

    // ---------- the file's icon ----------
    /** Icon of a .apks / .apkm / .apk+ / .xapk file: the app's own logo. */
    fun bundleIcon(c: Context, f: File, px: Int): Bitmap? {
        ZipFile(f).use { z ->
            z.getEntry("icon.png")?.let { e ->
                val b = z.getInputStream(e).use { BitmapFactory.decodeStream(it) } ?: return@let
                return Bitmap.createScaledBitmap(b, px, px, true)
            }
            val apks = z.entries().toList().filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") }
            val base = apks.firstOrNull { norm(it.name.substringAfterLast('/')) == "base" }
                ?: apks.filter { !isSplitName(norm(it.name.substringAfterLast('/'))) }.maxByOrNull { it.size } ?: return null
            val tmp = File(c.cacheDir, "icon_" + (f.absolutePath.hashCode().toLong() and 0xffffffffL) + ".apk")
            z.getInputStream(base).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            try {
                val pm = c.packageManager
                val info = pm.getPackageArchiveInfo(tmp.absolutePath, 0) ?: return null
                val ai = info.applicationInfo ?: return null
                ai.sourceDir = tmp.absolutePath; ai.publicSourceDir = tmp.absolutePath
                return bitmapOf(ai.loadIcon(pm), px)
            } finally { tmp.delete() }
        }
    }
}
