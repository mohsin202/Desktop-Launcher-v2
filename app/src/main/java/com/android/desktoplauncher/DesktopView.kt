package com.android.desktoplauncher

import android.app.AlertDialog
import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

sealed class Entry {
    abstract val key: String
    class AppE(val info: AppInfo) : Entry() { override val key = "app:" + info.comp.flattenToString() }
    class FileE(val file: File) : Entry() { override val key = "file:" + file.absolutePath }
    class FolderE(val id: String, var name: String, val members: MutableList<String>, var sx: Int, var sy: Int) : Entry() {
        override val key = "folder:$id"
        var resolved: List<Entry> = emptyList()
    }
}

/** The "Desktop": shows the contents of the Desktop folder, app shortcuts and widgets. */
class DesktopView(private val act: LauncherActivity) : FrameLayout(act) {
    private val items = ArrayList<Pair<Entry, View>>()
    private val widgets = ArrayList<WidgetBox>()
    private val frees = ArrayList<FreeBox>()
    private var selectedKey: String? = null
    private var editFolder: String? = null
    // icon emphasis: 1 = pointer over it, 2 = keyboard focus, 4 = its menu is open
    private val emph = HashMap<View, Int>()
    private var trashIv: BadgedIcon? = null
    private var trashBase: android.graphics.drawable.Drawable? = null
    private var trashSig = ""
    private var trashBmp: android.graphics.Bitmap? = null
    private var trashAt = 0L
    private var openFolderId: String? = null
    private var folderPanel: View? = null
    private var lastX = 0
    private var lastY = 0

    // screens: every category has pages; only the current page has views
    private val allEntries = ArrayList<Entry>()
    private var knownKeys: HashSet<String>? = null
    private val allWidgets = ArrayList<WidgetBox>()
    private val allFrees = ArrayList<FreeBox>()
    var catId: String = Cats.home(act)
        private set
    var pageIdx = 0
        private set
    private class CatLayout(val count: Int, val pos: HashMap<String, IntArray>)
    private val layouts = HashMap<String, CatLayout>()
    private var order: List<String> = listOf(Cats.ALL)
    private val wPack = HashMap<View, IntArray>()   // widgets screen: view -> page,col,row,sx,sy
    private var builtSig = ""
    private var discoverView: View? = null
    private var swX = 0f
    private var swY = 0f
    private var swiping = false
    private var dragLock = false
    private val swSlop = android.view.ViewConfiguration.get(act).scaledTouchSlop

    init {
        UiText.applyDirection(this, act)
        isClickable = true
        isLongClickable = true
        setOnClickListener { emptyTap() }
        setOnLongClickListener { desktopMenu(lastX, lastY); true }
        setSecondaryClick { x, y -> desktopMenu(x, y) }
        setOnDragListener { _, event ->
            if (DeskDrag.ours(event.clipDescription) || (event.action == android.view.DragEvent.ACTION_DRAG_ENDED && extDrag)) {
                return@setOnDragListener when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED -> { extDrag = true; true }
                    android.view.DragEvent.ACTION_DRAG_LOCATION -> { edgeHover(event.x); showHover(event.x, event.y, null); true }
                    android.view.DragEvent.ACTION_DRAG_EXITED -> { edgeStop(); showHover(-1f, -1f, null); true }
                    android.view.DragEvent.ACTION_DROP -> {
                        edgeStop(); extDrag = false; showHover(-1f, -1f, null)
                        val p = DeskDrag.parse(event.clipData)
                        p != null && dropExternal(p, event.x, event.y)
                    }
                    android.view.DragEvent.ACTION_DRAG_ENDED -> { extDrag = false; edgeStop(); showHover(-1f, -1f, null); true }
                    else -> true
                }
            }
            val ls = event.localState
            if (ls is WidgetDrag) {
                return@setOnDragListener when (event.action) {
                    android.view.DragEvent.ACTION_DROP -> {
                        val loc = IntArray(2); getLocationOnScreen(loc)
                        act.onWidgetDragEnded(true)
                        ls.item.add((loc[0] + event.x).toInt(), (loc[1] + event.y).toInt())
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_ENDED -> { if (!event.result) act.onWidgetDragEnded(false); true }
                    else -> true
                }
            }
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> event.clipDescription != null
                android.view.DragEvent.ACTION_DROP -> {
                    val clipData = event.clipData
                    var copied = 0
                    val permissions = try { act.requestDragAndDropPermissions(event) } catch (_: Exception) { null }
                    if (clipData != null) Thread {
                        try {
                            for (i in 0 until clipData.itemCount) {
                                val item = clipData.getItemAt(i)
                                val uri = item.uri ?: item.intent?.data ?: continue
                                try {
                                    var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Dropped file"
                                    try {
                                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                            if (it.moveToFirst()) name = it.getString(0)?.takeIf { n -> n.isNotBlank() } ?: name
                                        }
                                    } catch (_: Exception) {}
                                    val out = Files.unique(Files.root(context), name)
                                    context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                                    if (out.exists()) copied++
                                } catch (_: Exception) {}
                            }
                        } finally { try { permissions?.release() } catch (_: Exception) {} }
                        post { reload(); context.toast(if (copied > 0) "Dropped ${copied} item(s)" else "Cannot drop these items here") }
                    }.start() else try { permissions?.release() } catch (_: Exception) {}
                    true
                }
                else -> true
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (carry != null) {
            val loc = IntArray(2); getLocationOnScreen(loc)
            val x = ev.rawX - loc[0]; val y = ev.rawY - loc[1]
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> { moveGhost(x, y); edgeHover(x) }
                MotionEvent.ACTION_UP -> finishCarry(x, y)
                MotionEvent.ACTION_CANCEL -> finishCarry(-1f, -1f)
            }
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { lastX = ev.rawX.toInt(); lastY = ev.rawY.toInt() }
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------ dragging to / between screens

    private var extDrag = false          // an app / file is being dragged in from the drawer
    private var dragEntry: Entry? = null // a home icon is being dragged
    private var dragView: View? = null
    private var carry: Entry? = null     // the dragged home icon is now carried over the screens
    private var ghost: android.graphics.drawable.BitmapDrawable? = null
    private var edgeDir = 0
    private val edgeFlip = Runnable { edgeFired() }

    private fun logicalDir(dir: Int) = if (layoutDirection == LAYOUT_DIRECTION_RTL) -dir else dir

    /** Finger near the left (-1) / right (+1) edge for a moment: go to the next screen. */
    private fun edgeHover(x: Float) {
        val z = d(28)
        val dir = if (x < z) -1 else if (x > width - z) 1 else 0
        if (dir == edgeDir) return
        edgeDir = dir
        removeCallbacks(edgeFlip)
        if (dir != 0) postDelayed(edgeFlip, 550)
    }

    private fun edgeStop() { edgeDir = 0; removeCallbacks(edgeFlip) }

    private fun edgeFired() {
        if (edgeDir == 0) return
        val ld = logicalDir(edgeDir)
        if (stepTarget(ld) == null) return
        val de = dragEntry; val dv = dragView
        if (carry == null && de != null && dv != null) beginCarry(de, dv)
        if (carry != null || extDrag) {
            step(ld)
            postDelayed(edgeFlip, 750)
        }
    }

    /** Called by the icon touch handler while a home icon is being dragged. */
    private fun trackEdge(e: Entry, v: View, rawX: Float) {
        dragEntry = e; dragView = v
        val loc = IntArray(2); getLocationOnScreen(loc)
        edgeHover(rawX - loc[0])
    }

    private fun endTrack() { dragEntry = null; dragView = null; if (carry == null) edgeStop() }

    private fun beginCarry(e: Entry, v: View) {
        if (v.width <= 0 || v.height <= 0) return
        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        val g = android.graphics.drawable.BitmapDrawable(resources, bmp)
        ghost = g
        val cx = v.left + v.width / 2f + v.translationX
        val cy = v.top + v.height / 2f + v.translationY
        g.setBounds((cx - v.width / 2f).toInt(), (cy - v.height / 2f).toInt(), (cx + v.width / 2f).toInt(), (cy + v.height / 2f).toInt())
        overlay.add(g)
        carry = e
        dragEntry = null; dragView = null
        dragLock = true
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun moveGhost(x: Float, y: Float) {
        val g = ghost ?: return
        val w = g.bounds.width(); val h = g.bounds.height()
        g.setBounds((x - w / 2f).toInt(), (y - h / 2f).toInt(), (x + w / 2f).toInt(), (y + h / 2f).toInt())
        showHover(x, y, carry)
        invalidate()
    }

    private fun finishCarry(x: Float, y: Float) {
        val e = carry ?: return
        carry = null
        edgeStop()
        dragLock = false
        ghost?.let { overlay.remove(it) }
        ghost = null
        showHover(-1f, -1f, null)
        if (x >= 0f && y >= 0f && placeAt(e.key, x, y, e)) {
            if (e is Entry.FolderE) FolderStore.update(context, e.id) { it.sx = 1; it.sy = 1 }
            reload()
        } else {
            if (x >= 0f) context.toast(UiText.text(context, "Open All or your own screen to place icons"))
            rebuild()
        }
    }

    private fun manualScreen() = catId == Cats.ALL || Cats.isCustom(catId)

    private fun cellTaken(col: Int, row: Int, skip: Entry?): Boolean {
        for ((en, _) in items) {
            if (en === skip) continue
            val c = cells[en.key] ?: continue
            val sp = spanOf(en)
            if (col >= c[0] && col < c[0] + sp[0] && row >= c[1] && row < c[1] + sp[1]) return true
        }
        return false
    }

    /**
     * Puts the item at the screen point (x, y) of this view on the screen that is showing: the cell under the finger,
     * or the nearest free cell. False when this screen cannot take manual places.
     */
    private fun placeAt(key: String, x: Float, y: Float, skip: Entry?): Boolean {
        val spot = spotFor(x, y, skip) ?: return false
        Cats.assign(context, key, if (Cats.isCustom(catId)) catId else null)
        savePosHere(mapOf(key to spot))
        return true
    }

    private var hover: IntArray? = null
    private val hoverPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    /** Outline of the cell where the dragged icon would land. */
    private fun showHover(x: Float, y: Float, skip: Entry?) {
        val n = if (x < 0f) null else spotFor(x, y, skip)
        if (n?.toList() == hover?.toList()) return
        hover = n
        invalidate()
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        val h = hover ?: return
        val l = paddingLeft + d(8) + h[0] * gCw + d(4)
        val t = paddingTop + h[1] * gCh + d(2)
        val r = RectF(l.toFloat(), t.toFloat(), (l + gCw - d(8)).toFloat(), (t + gCh - d(4)).toFloat())
        hoverPaint.style = android.graphics.Paint.Style.FILL
        hoverPaint.color = (Colors.accent(context) and 0x00FFFFFF) or 0x26000000
        canvas.drawRoundRect(r, d(14).toFloat(), d(14).toFloat(), hoverPaint)
        hoverPaint.style = android.graphics.Paint.Style.STROKE
        hoverPaint.strokeWidth = d(2).toFloat()
        hoverPaint.color = Colors.accent(context)
        canvas.drawRoundRect(r, d(14).toFloat(), d(14).toFloat(), hoverPaint)
    }

    private fun spotFor(x: Float, y: Float, skip: Entry?): IntArray? {
        if (!manualScreen() || gCw <= 0 || gCh <= 0) return null
        val c0 = ((x - paddingLeft - d(8)) / gCw).toInt().coerceIn(0, gCols - 1)
        val r0 = ((y - paddingTop) / gCh).toInt().coerceIn(0, gRows - 1)
        var best: IntArray? = null
        var bestD = Int.MAX_VALUE
        for (cc in 0 until gCols) for (rr in 0 until gRows) {
            if (blockedByWidget(cc, rr) || cellTaken(cc, rr, skip)) continue
            val dd = (cc - c0) * (cc - c0) + (rr - r0) * (rr - r0)
            if (dd < bestD) { bestD = dd; best = intArrayOf(cc, rr) }
        }
        return best
    }

    /** A file / folder dropped from the drawer: copy it here, move it here or only make a shortcut. */
    private fun askFileDrop(f: File, x: Float, y: Float) {
        val c = context
        val loc = IntArray(2); getLocationOnScreen(loc)
        fun finish(dest: File?) {
            if (dest == null) { c.toast(UiText.text(c, "Could not create shortcut")); return }
            placeAt("file:" + dest.absolutePath, x, y, null)
            Bus.fire()
            reload()
        }
        fun transfer(move: Boolean) {
            Thread {
                val dest = try {
                    val d0 = Files.unique(Files.root(c), f.name)
                    if (f.isDirectory && d0.absolutePath.startsWith(f.absolutePath + "/")) null
                    else { if (move) Files.move(f, d0) else Files.copyRec(f, d0); d0.takeIf { it.exists() } }
                } catch (_: Exception) { null }
                post { finish(dest) }
            }.start()
        }
        PopupMenuX(c, this).show(loc[0] + x.toInt(), loc[1] + y.toInt(), listOf(
            MenuItem("Copy here") { transfer(false) },
            MenuItem("Move here") { transfer(true) },
            MenuItem("Create shortcut") { finish(DeskDrag.makeShortcut(c, f)) }
        ))
    }

    /** An app or file dropped from the drawer / search. */
    private fun dropExternal(p: DeskDrag.Payload, x: Float, y: Float): Boolean {
        val c = context
        val key: String
        when (p.kind) {
            DeskDrag.APP -> {
                val cn = ComponentName.unflattenFromString(p.value) ?: return false
                if (AppRepo.find(c, cn) == null) return false
                val l = Prefs.list(c, Prefs.K_DESKAPPS)
                if (p.value !in l) Prefs.setList(c, Prefs.K_DESKAPPS, l + p.value, fire = false)
                key = "app:" + p.value
            }
            DeskDrag.FILE -> {
                val f = File(p.value)
                if (!f.exists()) return false
                askFileDrop(f, x, y)
                return true
            }
            else -> return false
        }
        // an app that sat inside a folder leaves it
        for (fd in FolderStore.load(c)) if (key in fd.members) FolderStore.update(c, fd.id) { it.members.remove(key) }
        if (!placeAt(key, x, y, null)) {
            c.toast(UiText.text(c, "Added to the desktop. Open All or your own screen to choose its place"))
        }
        reload()
        return true
    }

    private fun d(v: Int) = context.dp(v)

    fun clearSelection() { selectedKey = null; refreshSelection() }

    /** Tap on empty desktop (also used by the free clock, which sits behind the icons). */
    fun emptyTap() { clearSelection(); endWidgetEdit(); act.closeDrawer() }

    private fun refreshSelection() {
        for ((e, v) in items) {
            if (e is Entry.FolderE) continue
            v.background = if (e.key == selectedKey) Colors.rounded(0x55FFFFFF, d(8).toFloat()) else null
        }
    }

    private fun selectedFile(): File? =
        items.firstOrNull { it.first.key == selectedKey }?.first.let { (it as? Entry.FileE)?.file }

    fun reload() {
        val c = context
        val hidden = Prefs.list(c, Prefs.K_HIDDEN).toSet()
        val folderData = FolderStore.load(c)
        val inFolder = folderData.flatMap { it.members }.toHashSet()
        val list = ArrayList<Entry>()
        for (s in Prefs.list(c, Prefs.K_DESKAPPS)) {
            if ("app:$s" in inFolder) continue
            val cn = ComponentName.unflattenFromString(s) ?: continue
            AppRepo.find(c, cn)?.let { list += Entry.AppE(it) }
        }
        try {
            Files.root(c).listFiles()
                ?.filter { !it.name.startsWith(".") && it.absolutePath !in hidden && "file:${it.absolutePath}" !in inFolder }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?.forEach { list += Entry.FileE(it) }
        } catch (_: Exception) {}
        // The Trash app icon is placed on the desktop once.
        if (!Prefs.bool(c, "trashSeeded", false)) {
            val tr = AppRepo.apps(c).firstOrNull { it.comp.className.endsWith("TrashActivity") }
            if (tr != null) {
                Prefs.setBool(c, "trashSeeded", true)
                val flat = tr.comp.flattenToString()
                if (flat !in Prefs.list(c, Prefs.K_DESKAPPS)) {
                    Prefs.setList(c, Prefs.K_DESKAPPS, Prefs.list(c, Prefs.K_DESKAPPS) + flat, fire = false)
                    list.add(0, Entry.AppE(tr))
                }
            }
        }
        // Folders: members that no longer exist are dropped, empty folders disappear.
        var folderChanged = false
        for (fd in folderData) {
            val res = ArrayList<Entry>()
            for (m in fd.members.toList()) {
                val ent: Entry? = if (m.startsWith("app:")) {
                    ComponentName.unflattenFromString(m.removePrefix("app:"))?.let { cn -> AppRepo.find(c, cn)?.let { Entry.AppE(it) } }
                } else if (m.startsWith("file:")) File(m.removePrefix("file:")).takeIf { it.exists() }?.let { Entry.FileE(it) } else null
                if (ent != null) res += ent else { fd.members.remove(m); folderChanged = true }
            }
            if (res.isEmpty()) { folderChanged = true; continue }
            list += Entry.FolderE(fd.id, fd.name, fd.members, fd.sx, fd.sy).also { it.resolved = res }
        }
        if (folderChanged) FolderStore.save(c, folderData.filter { it.members.isNotEmpty() })
        // an icon / file / folder that appears while one of the user's own screens is showing stays on that screen
        val keys = list.map { it.key }.toHashSet()
        val prev = knownKeys
        if (prev != null && Cats.isCustom(catId) && Cats.customs(c).any { it.id == catId }) {
            for (k in keys) if (k !in prev) Cats.assign(c, k, catId)
        }
        knownKeys = keys
        allEntries.clear(); allEntries += list
        allWidgets.clear(); allFrees.clear()
        loadWPages()
        loadWidgets()
        loadFree()
        builtSig = ""
        rebuild()
    }

    // ------------------------------------------------------------ screens (categories and pages)

    fun refresh() { rebuild() }
    fun catOrder(): List<String> = order.filter { Cats.shownInMode(context, it) }
    /** The screens that are reachable by swiping / the page buttons: hidden ones are left out. */
    private fun navOrder(): List<String> = order.filter { !Cats.isHidden(context, it) && Cats.shownInMode(context, it) }

    /** One screen mode on / off. Leaving a hidden screen goes to All. */
    fun setOneScreen(on: Boolean) {
        Prefs.setBool(context, "oneScreen", on)
        if (on && !Cats.shownInMode(context, catId)) goTo(Cats.ALL, 0) else rebuild()
    }
    fun pageCount(cat: String): Int = layouts[cat]?.count ?: 1
    fun widgetsLocked() = catId != Cats.ALL && catId != Cats.WIDGETS

    // widgets belong either to the All screen (no entry) or to a page of the Widgets screen
    private val wPageOf = HashMap<Int, Int>()
    private fun pageOfW(wid: Int) = wPageOf[wid] ?: -1
    private fun loadWPages() {
        wPageOf.clear()
        for (s in Prefs.list(context, "wpage")) {
            val w = s.substringBefore('=').toIntOrNull() ?: continue
            val p = s.substringAfter('=').toIntOrNull() ?: continue
            wPageOf[w] = p
        }
    }
    private fun saveWPages(prune: Boolean = false) {
        val ids = HashSet<Int>(); for (b in allWidgets) ids += b.wid; for (f in allFrees) ids += f.wid
        Prefs.setList(context, "wpage", wPageOf.filter { !prune || it.key in ids }.map { "${it.key}=${it.value}" }, fire = false)
    }
    private fun widgetPageCount(): Int {
        val stored = Prefs.getStr(context, "wpages")?.toIntOrNull() ?: 1
        val used = (allWidgets.map { pageOfW(it.wid) } + allFrees.map { pageOfW(it.wid) }).maxOrNull() ?: -1
        return maxOf(1, stored, used + 1)
    }
    /** Deletes the user's own layout page of All together with every icon, folder and widget that is on it. */
    fun deleteFreePage() {
        val c = context
        val onPage = ArrayList<Entry>()
        val pos = layouts[Cats.ALL]?.pos
        for (e in allEntries) if (pos?.get(e.key)?.get(0) == 0) onPage += e
        val hidden = Prefs.list(c, Prefs.K_HIDDEN).toMutableList()
        val apps = Prefs.list(c, Prefs.K_DESKAPPS).toMutableList()
        val folders = FolderStore.load(c)
        for (e in onPage) when (e) {
            is Entry.AppE -> apps.remove(e.info.comp.flattenToString())
            is Entry.FileE -> {
                if (e.file.extension == "desktoplink") try { e.file.delete() } catch (_: Exception) {}
                else hidden += e.file.absolutePath
            }
            is Entry.FolderE -> folders.removeAll { it.id == e.id }
        }
        Prefs.setList(c, Prefs.K_HIDDEN, hidden, fire = false)
        Prefs.setList(c, Prefs.K_DESKAPPS, apps, fire = false)
        FolderStore.save(c, folders)
        for (b in allWidgets.filter { pageOfW(it.wid) < 0 }) removeWidget(b)
        for (f in allFrees.filter { pageOfW(it.wid) < 0 }) removeFree(f)
        Cats.deleteFree(c)
        reload()
    }

    /** Deletes one page of the Widgets screen with its widgets; the pages after it move up. */
    fun deleteWidgetPage(page: Int) {
        val n = widgetPageCount()
        if (n <= 1) return
        for (b in allWidgets.filter { pageOfW(it.wid) == page }) removeWidget(b)
        for (f in allFrees.filter { pageOfW(it.wid) == page }) removeFree(f)
        for (k in wPageOf.keys.toList()) { val v = wPageOf[k] ?: continue; if (v > page) wPageOf[k] = v - 1 }
        saveWPages(true)
        Prefs.setStr(context, "wpages", (n - 1).toString(), fire = false)
        pageIdx = pageIdx.coerceAtMost(n - 2)
        rebuild()
    }

    /** The "+" of the Widgets screen: one more (empty) page. */
    fun addWidgetPage() {
        Prefs.setStr(context, "wpages", (widgetPageCount() + 1).toString(), fire = false)
        rebuild()
    }
    /** Where a new widget goes: on the Widgets page that is showing, otherwise on All. */
    private fun widgetHome(): Int {
        if (catId == Cats.WIDGETS) return pageIdx
        goTo(Cats.ALL, 0)
        return -1
    }
    private fun homeFor(wid: Int, page: Int) { if (page >= 0) wPageOf[wid] = page else wPageOf.remove(wid); saveWPages() }

    private fun sig() = "$gCols,$gRows,$gCw,$gCh,$width,$height,$paddingLeft,$paddingTop,$paddingRight,$paddingBottom,${Modes.tablet(context)}"


    /** Builds the views of the current page (items, widgets) from the stored data. */
    private fun rebuild() {
        val c = context
        act.wallBlur(catId == Cats.DISCOVER)
        removeAllViews(); items.clear(); widgets.clear(); frees.clear(); cells.clear(); wPack.clear()
        folderPanel = null; trashIv = null; emph.clear(); discoverView = null
        if (width <= 0 || height <= 0) { builtSig = ""; requestLayout(); return }
        computeGrid(width, height)
        placeWidgets()
        computeLayouts()
        pageIdx = pageIdx.coerceIn(0, pageCount(catId) - 1)
        val lay = layouts[catId]
        fun reset(v: View) { v.translationX = 0f; v.alpha = 1f }
        if (catId == Cats.DISCOVER) {
            val dv = makeDiscover(); discoverView = dv; addView(dv)
        } else if (catId == Cats.WIDGETS) {
            for (b in allWidgets) if (pageOfW(b.wid) == pageIdx) { reset(b); addView(b); widgets += b }
            for (f in allFrees) if (pageOfW(f.wid) == pageIdx) { reset(f); addView(f, 0); frees += f }
        } else {
            for (e in allEntries) {
                val p = lay?.pos?.get(e.key) ?: continue
                if (p[0] != pageIdx) continue
                val v = makeItem(e)
                addView(v)
                items += e to v
                cells[e.key] = intArrayOf(p[1], p[2])
            }
            if (catId == Cats.ALL && pageIdx == 0) {
                for (b in allWidgets) if (pageOfW(b.wid) < 0) { reset(b); addView(b); widgets += b }
                for (f in allFrees) if (pageOfW(f.wid) < 0) { reset(f); addView(f, 0); frees += f }
            }
        }
        refreshFolderEdit()
        refreshTrashIcon()
        refreshSelection()
        openFolderId?.let { id ->
            if (items.any { (it.first as? Entry.FolderE)?.id == id }) showFolder(id) else openFolderId = null
        }
        builtSig = sig()
        Screens.show(Cats.name(c, catId), pageCount(catId), pageIdx)
        requestLayout()
    }

    private fun computeLayouts() {
        val c = context
        val assign = Cats.assignments(c)
        val customs = Cats.customs(c)
        val valid = customs.map { it.id }.toSet()
        layouts.clear()
        val ord = ArrayList<String>()
        ord += Cats.DISCOVER; ord += Cats.WIDGETS
        ord += Cats.ALL
        for (cat in listOf(Cats.APPS, Cats.MEDIA, Cats.SHORTCUTS, Cats.FOLDERS, Cats.FILES))
            if (allEntries.any { Cats.matches(cat, it, assign[it.key], valid) }) ord += cat
        for (cu in customs) ord += cu.id
        order = ord
        if (catId !in ord) { catId = if (Cats.home(c) in ord) Cats.home(c) else Cats.ALL; pageIdx = 0 }
        // the current screen is computed last: folders share their size between screens
        for (cat in ord.filter { it != catId } + ord.filter { it == catId }) {
            layouts[cat] = when (cat) {
                Cats.DISCOVER -> CatLayout(1, HashMap())
                Cats.WIDGETS -> CatLayout(widgetPageCount(), HashMap())
                else -> layoutEntries(cat, allEntries.filter { Cats.matches(cat, it, assign[it.key], valid) })
            }
        }
    }

    private fun blockedAt(cat: String, page: Int, col: Int, row: Int): Boolean =
        cat == Cats.ALL && page == 0 && allWidgets.any { o -> pageOfW(o.wid) < 0 && o.col >= 0 && col >= o.col && col < o.col + o.sx && row >= o.row && row < o.row + o.sy }

    /** Gives every item of the screen a page, column and row. Stored places are kept, the rest flows into the first free cell. */
    private fun layoutEntries(cat: String, list: List<Entry>): CatLayout {
        val cap = maxOf(1, gCols * gRows)
        val tablet = Modes.tablet(context)
        val stored = storedPos(cat)
        val occ = ArrayList<HashSet<Int>>()
        fun occOf(p: Int): HashSet<Int> { while (occ.size <= p) occ.add(HashSet()); return occ[p] }
        val pos = HashMap<String, IntArray>()
        // folders keep their cells (1x1 up to 3x3)
        for (e in list) {
            if (e !is Entry.FolderE) continue
            val p = stored[e.key] ?: run { e.sx = 1; e.sy = 1; null } ?: continue
            fun fits(sx: Int, sy: Int): Boolean {
                if (p[1] < 0 || p[2] < 0 || p[1] + sx > gCols || p[2] + sy > gRows) return false
                for (cc in p[1] until p[1] + sx) for (rr in p[2] until p[2] + sy) if (blockedAt(cat, p[0], cc, rr) || (cc * 10000 + rr) in occOf(p[0])) return false
                return true
            }
            if (!fits(e.sx, e.sy)) { e.sx = 1; e.sy = 1 }
            if (fits(e.sx, e.sy)) {
                for (cc in p[1] until p[1] + e.sx) for (rr in p[2] until p[2] + e.sy) occOf(p[0]).add(cc * 10000 + rr)
                pos[e.key] = p.copyOf()
            }
        }
        for (e in list) {
            if (pos.containsKey(e.key)) continue
            val p = stored[e.key] ?: continue
            if (p[0] >= 0 && p[1] in 0 until gCols && p[2] in 0 until gRows && !blockedAt(cat, p[0], p[1], p[2]) && occOf(p[0]).add(p[1] * 10000 + p[2])) pos[e.key] = p.copyOf()
        }
        // everything else flows: desktop = columns (top to bottom), phone = rows (left to right)
        val fresh = ArrayList<String>()
        // with the user's own layout page in front of All, new icons go there only while that page is showing
        val startPage = if (cat == Cats.ALL && Cats.hasFree(context) && !(catId == Cats.ALL && pageIdx == 0)) 1 else 0
        var i = startPage * cap
        for (e in list) {
            if (pos.containsKey(e.key)) continue
            var placed: IntArray? = null
            while (placed == null && i < cap * 500) {
                val pg = i / cap; val k = i % cap; i++
                val col = if (tablet) k / gRows else k % gCols
                val row = if (tablet) k % gRows else k / gCols
                if (!blockedAt(cat, pg, col, row) && occOf(pg).add(col * 10000 + row)) placed = intArrayOf(pg, col, row)
            }
            pos[e.key] = placed ?: intArrayOf(0, 0, 0)
            if (placed != null && stored[e.key] == null) fresh += e.key
        }
        // empty pages disappear
        val used = java.util.TreeSet<Int>()
        for (p in pos.values) used.add(p[0])
        if (cat == Cats.ALL) used.add(0)
        val remap = HashMap<Int, Int>()
        var n = 0
        for (u in used) remap[u] = n++
        val toSave = HashMap<String, IntArray>()
        for ((k, p) in pos) {
            val np = remap[p[0]] ?: 0
            if (np != p[0]) { p[0] = np; if (stored[k] != null) toSave[k] = p }
        }
        for (k in fresh) pos[k]?.let { toSave[k] = it }
        if (toSave.isNotEmpty()) savePos(cat, toSave)
        return CatLayout(maxOf(1, used.size), pos)
    }

    fun goTo(cat: String, page: Int, dir: Int = 0) {
        val crossed = cat != catId
        closeFolder(); endWidgetEdit()
        catId = cat; pageIdx = page
        rebuild()
        if (dir != 0) animateIn(dir)
        Screens.moved(crossed)
    }

    private fun animateIn(dir: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val from = dir * (if (rtl) -1 else 1) * width * 0.18f
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            ch.translationX = from; ch.alpha = 0f
            ch.animate().translationX(0f).alpha(1f).setDuration(200).start()
        }
    }

    /** One page further (+1) or back (-1) through all screens; false at the very first / last page. */
    fun step(dir: Int): Boolean {
        val t = stepTarget(dir) ?: return false
        goTo(t.first, t.second, dir)
        return true
    }

    private fun stepTarget(dir: Int): Pair<String, Int>? {
        val nav = navOrder().let { if (catId in it) it else order }
        var ci = nav.indexOf(catId); if (ci < 0) ci = 0
        var p = pageIdx + dir
        if (p < 0) { if (ci == 0) return null; ci--; p = pageCount(nav[ci]) - 1 }
        else if (p >= pageCount(catId)) { if (ci >= nav.lastIndex) return null; ci++; p = 0 }
        return nav[ci] to p
    }

    fun nextCategory() {
        val nav = navOrder().let { if (catId in it) it else order }
        val ci = nav.indexOf(catId)
        goTo(nav[(ci + 1) % nav.size], 0, 1)
    }

    fun goHome() {
        val h = Cats.home(context).let { if (it in order && Cats.shownInMode(context, it)) it else Cats.ALL }
        if (catId != h || pageIdx != 0) goTo(h, 0)
    }

    /** Home screen is showing and nothing else is going on (no folder, no widget being resized, no drag). */
    /** True if a visible widget is under this screen point: swipes that start on a widget scroll it, they don't open the drawer. */
    fun widgetAtRaw(x: Float, y: Float): Boolean {
        val r = android.graphics.Rect()
        for (w in allWidgets) {
            if (!w.isShown || !w.getGlobalVisibleRect(r)) continue
            if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true
        }
        return false
    }

    fun homeIdle() = carry == null && catId != Cats.DISCOVER && folderPanel == null && editFolder == null && !dragLock && allWidgets.none { it.editing() } && allFrees.none { it.editing() }

    private fun canSwipe() = folderPanel == null && editFolder == null && !dragLock && allWidgets.none { it.editing() } && allFrees.none { it.editing() }

    private fun startsSwipe(ev: MotionEvent): Boolean {
        val dx = ev.rawX - swX; val dy = ev.rawY - swY
        return Math.abs(dx) > swSlop * 2 && Math.abs(dx) > Math.abs(dy) * 1.6f
    }

    private fun beginSwipe() { swiping = true; cancelLongPress(); isPressed = false }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { swX = ev.rawX; swY = ev.rawY; swiping = false }
            MotionEvent.ACTION_MOVE -> if (!swiping && canSwipe() && startsSwipe(ev)) { beginSwipe(); return true }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { swX = ev.rawX; swY = ev.rawY; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                if (!swiping && canSwipe() && startsSwipe(ev)) beginSwipe()
                // a moving finger is a swipe, not a long press: the Copy / Cut / Wallpaper menu must not pop up
                if (Math.hypot((ev.rawX - swX).toDouble(), (ev.rawY - swY).toDouble()) > swSlop) cancelLongPress()
            }
        }
        if (swiping) {
            val dx = ev.rawX - swX
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> for (i in 0 until childCount) getChildAt(i).translationX = dx * 0.35f
                MotionEvent.ACTION_UP -> { swiping = false; endSwipe(dx) }
                MotionEvent.ACTION_CANCEL -> { swiping = false; for (i in 0 until childCount) getChildAt(i).animate().translationX(0f).setDuration(150).start() }
            }
            return true
        }
        return super.onTouchEvent(ev)
    }

    private fun endSwipe(dx: Float) {
        if (Math.abs(dx) >= maxOf(d(56), (width * 0.14f).toInt())) {
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val next = if (rtl) dx > 0 else dx < 0
            if (step(if (next) 1 else -1)) return
        }
        for (i in 0 until childCount) getChildAt(i).animate().translationX(0f).setDuration(150).start()
    }

    private fun makeDiscover(): View = NewsView(act)

    /** Picture of one page for the category page. The page on screen is the real view, the others are drawn from the stored places. */
    fun thumbnail(cat: String, page: Int, tw: Int, th: Int, wall: android.graphics.drawable.Drawable?): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(tw, th, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val iw = wall?.intrinsicWidth ?: 0
        val ih = wall?.intrinsicHeight ?: 0
        if (wall != null && iw > 0 && ih > 0) {
            val sc = maxOf(tw / iw.toFloat(), th / ih.toFloat())
            val dw = iw * sc; val dh = ih * sc
            wall.setBounds(((tw - dw) / 2).toInt(), ((th - dh) / 2).toInt(), ((tw + dw) / 2).toInt(), ((th + dh) / 2).toInt())
            wall.draw(cv)
        } else cv.drawColor(0xFF3C5A4A.toInt())
        val w = width.coerceAtLeast(1)
        val sc = tw.toFloat() / w
        if (cat == catId && page == pageIdx) {
            try { cv.save(); cv.scale(sc, sc); draw(cv); cv.restore() } catch (_: Throwable) {}
            return bmp
        }
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        if (cat == Cats.DISCOVER) {
            p.color = Color.WHITE; p.textAlign = android.graphics.Paint.Align.CENTER; p.textSize = tw * 0.16f
            cv.drawText(UiText.text(context, "Feed"), tw / 2f, th / 2f, p)
            return bmp
        }
        fun box(l: Int, t: Int, r: Int, b: Int) {
            p.color = 0x66FFFFFF
            cv.drawRoundRect(l * sc, t * sc, r * sc, b * sc, d(12) * sc, d(12) * sc, p)
        }
        if (cat == Cats.WIDGETS) {
            for (b in allWidgets) if (pageOfW(b.wid) == page && b.col >= 0) box(originX() + b.col * gCw, originY() + b.row * gCh, originX() + (b.col + b.sx) * gCw, originY() + (b.row + b.sy) * gCh)
            for (f in allFrees) if (pageOfW(f.wid) == page) box((f.fx * width).toInt(), (f.fy * height).toInt(), ((f.fx + f.fw) * width).toInt(), ((f.fy + f.fh) * height).toInt())
            return bmp
        }
        if (cat == Cats.ALL && page == 0) {
            for (b in allWidgets) if (b.col >= 0 && pageOfW(b.wid) < 0) box(originX() + b.col * gCw, originY() + b.row * gCh, originX() + (b.col + b.sx) * gCw, originY() + (b.row + b.sy) * gCh)
            for (f in allFrees) if (pageOfW(f.wid) < 0) box((f.fx * width).toInt(), (f.fy * height).toInt(), ((f.fx + f.fw) * width).toInt(), ((f.fy + f.fh) * height).toInt())
        }
        val lay = layouts[cat] ?: return bmp
        val isz = d(Prefs.iconDp(context))
        for (e in allEntries) {
            val q = lay.pos[e.key] ?: continue
            if (q[0] != page) continue
            val left = paddingLeft + d(8) + q[1] * gCw
            val top = paddingTop + q[2] * gCh
            val dr: android.graphics.drawable.Drawable = when (e) {
                is Entry.AppE -> Icons.app(context, e.info.comp, false)
                is Entry.FileE -> Icons.file(e.file)
                is Entry.FolderE -> FolderIcon()
            }
            val x = left + (gCw - isz) / 2; val y = top + d(6)
            dr.setBounds((x * sc).toInt(), (y * sc).toInt(), ((x + isz) * sc).toInt().coerceAtLeast((x * sc).toInt() + 1), ((y + isz) * sc).toInt().coerceAtLeast((y * sc).toInt() + 1))
            dr.draw(cv)
        }
        return bmp
    }

    /** Puts the item on the first free place of the user's own layout page (page 1 of All). */
    private fun moveToFree(e: Entry) {
        val c = context
        Cats.assign(c, e.key, null)
        val occ = HashSet<Int>()
        for ((k, p) in layouts[Cats.ALL]?.pos ?: HashMap()) if (k != e.key && p[0] == 0) occ.add(p[1] * 10000 + p[2])
        val tablet = Modes.tablet(c)
        val cap = maxOf(1, gCols * gRows)
        var spot: IntArray? = null
        for (k in 0 until cap) {
            val col = if (tablet) k / gRows else k % gCols
            val row = if (tablet) k % gRows else k / gCols
            if (!blockedAt(Cats.ALL, 0, col, row) && (col * 10000 + row) !in occ) { spot = intArrayOf(0, col, row); break }
        }
        if (spot == null) { c.toast("No free space on the page"); return }
        savePos(Cats.ALL, mapOf(e.key to spot))
        goTo(Cats.ALL, 0)
    }

    private fun moveItem(e: Entry): MenuItem {
        val c = context
        val cur = Cats.assignments(c)[e.key]
        val kids = ArrayList<MenuItem>()
        kids += MenuItem("Automatic", checked = cur == null) { Cats.assign(c, e.key, null); rebuild() }
        for (cu in Cats.customs(c)) kids += MenuItem(cu.name, checked = cur == cu.id) { Cats.assign(c, e.key, cu.id); rebuild() }
        if (Cats.hasFree(c)) kids += MenuItem("Custom layout page") { moveToFree(e) }
        kids += MenuItem("New screen") { val id = Cats.add(c); Cats.assign(c, e.key, id); rebuild() }
        return MenuItem("Move to screen", children = kids)
    }

    // ------------------------------------------------------------ widgets (grid based)

    private fun tagOf(s: String) = s.substringBefore(",")

    private fun loadWidgets() {
        val c = context
        val tag = "g" + posTag()
        val cur = LinkedHashMap<Int, IntArray>()      // id -> col,row,sx,sy for this layout
        val spanOnly = HashMap<Int, IntArray>()       // sizes saved by the other layout
        val legacy = HashMap<Int, IntArray>()         // old pixel based entries: px,py,w,h
        val order = LinkedHashSet<Int>()
        for (s in Prefs.list(c, Prefs.K_WIDGETS)) {
            val p = s.split(",")
            if (p.size == 6 && p[0].startsWith("g")) {
                val id = p[1].toIntOrNull() ?: continue
                val v = intArrayOf(p[2].toIntOrNull() ?: continue, p[3].toIntOrNull() ?: continue,
                    p[4].toIntOrNull() ?: continue, p[5].toIntOrNull() ?: continue)
                order += id
                if (p[0] == tag) cur[id] = v else spanOnly[id] = v
            } else if (p.size == 5) {
                val id = p[0].toIntOrNull() ?: continue
                legacy[id] = intArrayOf(p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0, p[4].toIntOrNull() ?: 0)
                order += id
            }
        }
        val internal = LinkedHashMap<Int, String>()
        for (s in Prefs.list(c, Prefs.K_LWIDGETS)) {
            val id = s.substringBefore(":").toIntOrNull() ?: continue
            internal[id] = s.substringAfter(":")
            order += id
        }
        for (id in order) {
            val box: WidgetBox
            if (id < 0) {
                val type = internal[id] ?: continue
                val def = InternalWidgets.def(type) ?: continue
                val content = InternalWidgets.create(act, type) { act.openSearchFromWidget() }
                box = WidgetBox(act, id, content, null, def.minWdp, def.minHdp, this)
            } else {
                val info = act.awm.getAppWidgetInfo(id)
                if (info == null) { act.host.deleteAppWidgetId(id); continue }
                val hv: AppWidgetHostView = act.host.createView(act, id, info)
                box = WidgetBox(act, id, hv, hv, maxOf(info.minWidth, 1).let { (it / resources.displayMetrics.density).toInt() },
                    maxOf(info.minHeight, 1).let { (it / resources.displayMetrics.density).toInt() }, this)
            }
            val v = cur[id]
            if (v != null) { box.col = v[0]; box.row = v[1]; box.sx = v[2]; box.sy = v[3] }
            else {
                spanOnly[id]?.let { box.sx = it[2]; box.sy = it[3] }
                legacy[id]?.let { box.legacy = it }
            }
            allWidgets += box
        }
    }

    /** Number of grid cells needed for a widget of the given minimum size (dp). */
    fun spansFor(wDp: Int, hDp: Int): IntArray {
        if (gCw <= 1) computeGrid(width, height)
        val sx = Math.ceil((d(wDp) + d(4)).toDouble() / gCw).toInt().coerceIn(1, maxOf(1, gCols))
        val sy = Math.ceil((d(hDp) + d(4)).toDouble() / gCh).toInt().coerceIn(1, maxOf(1, gRows))
        return intArrayOf(sx, sy)
    }

    internal fun originX() = paddingLeft + d(8)
    internal fun originY() = paddingTop
    internal fun cellW() = gCw
    internal fun cellH() = gCh
    internal fun gridCols() = gCols
    internal fun gridRows() = gRows

    private fun overlaps(a: WidgetBox, col: Int, row: Int, sx: Int, sy: Int, others: Collection<WidgetBox>): Boolean {
        for (o in others) {
            if (o === a || o.col < 0) continue
            if (col < o.col + o.sx && col + sx > o.col && row < o.row + o.sy && row + sy > o.row) return true
        }
        return false
    }

    /** True if the widget may occupy this rectangle of grid cells (inside the grid, not on another widget). */
    fun canPlace(box: WidgetBox, col: Int, row: Int, sx: Int, sy: Int): Boolean =
        col >= 0 && row >= 0 && sx >= 1 && sy >= 1 && col + sx <= gCols && row + sy <= gRows && !overlaps(box, col, row, sx, sy, widgets)

    private fun findFree(box: WidgetBox, placed: Collection<WidgetBox>, sx0: Int, sy0: Int): IntArray? {
        var sx = sx0.coerceIn(1, gCols); var sy = sy0.coerceIn(1, gRows)
        while (true) {
            for (r in 0..(gRows - sy)) for (c in 0..(gCols - sx)) {
                if (!overlaps(box, c, r, sx, sy, placed)) return intArrayOf(c, r, sx, sy)
            }
            if (sx > 1) sx-- else if (sy > 1) sy-- else return null
        }
    }

    /** Snaps every widget to the grid. Called from onLayout once the grid size is known. */
    private fun placeWidgets() {
        var changed = false
        val placed = ArrayList<WidgetBox>()
        for (b in allWidgets) {
            b.col = if (b.col < 0 && b.legacy == null) -1 else b.col
            if (b.sx <= 0 || b.sy <= 0) {
                val lg = b.legacy
                if (lg != null && lg[2] > 0 && lg[3] > 0) {
                    b.sx = Math.round(lg[2].toFloat() / gCw).coerceAtLeast(1)
                    b.sy = Math.round(lg[3].toFloat() / gCh).coerceAtLeast(1)
                    b.col = Math.round((lg[0] - originX()).toFloat() / gCw).coerceAtLeast(0)
                    b.row = Math.round((lg[1] - originY()).toFloat() / gCh).coerceAtLeast(0)
                } else {
                    val sp = spansFor(b.defWdp, b.defHdp); b.sx = sp[0]; b.sy = sp[1]
                }
                b.legacy = null
                changed = true
            }
            b.sx = b.sx.coerceIn(1, gCols); b.sy = b.sy.coerceIn(1, gRows)
            val same = placed.filter { pageOfW(it.wid) == pageOfW(b.wid) }
            val ok = b.col >= 0 && b.row >= 0 && b.col + b.sx <= gCols && b.row + b.sy <= gRows && !overlaps(b, b.col, b.row, b.sx, b.sy, same)
            if (!ok) {
                val f = findFree(b, same, b.sx, b.sy)
                if (f != null) { b.col = f[0]; b.row = f[1]; b.sx = f[2]; b.sy = f[3] } else { b.col = 0; b.row = 0 }
                changed = true
            }
            placed += b
        }
        if (changed) post { saveWidgets() }
    }

    fun addWidget(id: Int, info: android.appwidget.AppWidgetProviderInfo, screenX: Int, screenY: Int) {
        homeFor(id, widgetHome())
        val hv: AppWidgetHostView = act.host.createView(act, id, info)
        val dens = resources.displayMetrics.density
        val box = WidgetBox(act, id, hv, hv, (maxOf(info.minWidth, 1) / dens).toInt(), (maxOf(info.minHeight, 1) / dens).toInt(), this)
        insertNew(box, screenX, screenY)
    }

    fun addInternal(type: String, screenX: Int, screenY: Int) {
        val def = InternalWidgets.def(type) ?: return
        val id = InternalWidgets.nextId(allWidgetIds())
        homeFor(id, widgetHome())
        if (type == InternalWidgets.CLOCK_SPECIAL) { addFree(id, screenX, screenY); return }
        Prefs.setList(context, Prefs.K_LWIDGETS, Prefs.list(context, Prefs.K_LWIDGETS) + "$id:$type", fire = false)
        val box = WidgetBox(act, id, InternalWidgets.create(act, type) { act.openSearchFromWidget() }, null, def.minWdp, def.minHdp, this)
        insertNew(box, screenX, screenY)
    }

    private fun allWidgetIds(): List<Int> = allWidgets.map { it.wid } + allFrees.map { it.wid } +
        Prefs.list(context, Prefs.K_LWIDGETS).mapNotNull { it.substringBefore(":").toIntOrNull() } +
        Prefs.list(context, Prefs.K_FREE).mapNotNull { it.substringBefore(",").toIntOrNull() }

    // ------------------------------------------------------------ special (free-form) clock

    private operator fun IntArray.component4() = this[3]

    /** Area the special clock may use: inside the status bar, the dock / taskbar and the side bars. */
    fun freeBounds(): IntArray = intArrayOf(paddingLeft, paddingTop, maxOf(paddingLeft + 8, width - paddingRight), maxOf(paddingTop + 8, height - paddingBottom))

    private fun freeRect(f: FreeBox, w: Int, h: Int): IntArray {
        val bd = freeBounds()
        val bw = maxOf(8, minOf((f.fw * w).toInt(), bd[2] - bd[0])); val bh = maxOf(8, minOf((f.fh * h).toInt(), bd[3] - bd[1]))
        val l = (f.fx * w).toInt().coerceIn(bd[0], maxOf(bd[0], bd[2] - bw))
        val t = (f.fy * h).toInt().coerceIn(bd[1], maxOf(bd[1], bd[3] - bh))
        return intArrayOf(l, t, l + bw, t + bh)
    }

    private fun makeFree(id: Int): FreeBox = FreeBox(act, id, SpecialClockView(act, ClockCfg.load(act, id)), this)

    private fun loadFree() {
        for (s in Prefs.list(context, Prefs.K_FREE)) {
            val p = s.split(",")
            if (p.size != 5) continue
            val id = p[0].toIntOrNull() ?: continue
            val f = makeFree(id)
            f.fx = (p[1].toIntOrNull() ?: 0) / 10000f; f.fy = (p[2].toIntOrNull() ?: 0) / 10000f
            f.fw = (p[3].toIntOrNull() ?: 8000) / 10000f; f.fh = (p[4].toIntOrNull() ?: 3000) / 10000f
            allFrees += f
        }
    }

    fun freeSave() {
        Prefs.setList(context, Prefs.K_FREE, allFrees.map { "${it.wid},${(it.fx * 10000).toInt()},${(it.fy * 10000).toInt()},${(it.fw * 10000).toInt()},${(it.fh * 10000).toInt()}" }, fire = false)
    }

    private fun addFree(id: Int, screenX: Int, screenY: Int) {
        val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
        val f = makeFree(id)
        val bd = freeBounds()
        val bw = minOf((bd[2] - bd[0]) * 0.86f, d(340).toFloat())
        val bh = bw / 2.4f
        f.fw = bw / w; f.fh = bh / h
        if (screenX >= 0 && screenY >= 0) {
            val loc = IntArray(2); getLocationOnScreen(loc)
            f.fx = ((screenX - loc[0] - bw / 2f).coerceIn(bd[0].toFloat(), maxOf(bd[0].toFloat(), bd[2] - bw))) / w
            f.fy = ((screenY - loc[1] - bh / 2f).coerceIn(bd[1].toFloat(), maxOf(bd[1].toFloat(), bd[3] - bh))) / h
        } else { f.fx = (bd[0] + (bd[2] - bd[0] - bw) / 2f) / w; f.fy = (bd[1] + d(24)) / h.toFloat() }
        allFrees += f
        addView(f, 0)
        frees += f
        freeSave()
        endWidgetEdit(f)
        f.startEdit()          // show the resize frame right away
        requestLayout()
    }

    fun freeMenu(box: FreeBox, x: Int, y: Int) {
        val m = PopupMenuX(context, this)
        m.show(x, y, listOf(
            MenuItem("Edit") {
                if (widgetsLocked()) {
                    goTo(Cats.ALL, 0)
                    val vo = viewTreeObserver
                    vo.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() { viewTreeObserver.removeOnGlobalLayoutListener(this); editFree(box) }
                    })
                } else editFree(box)
            },
            MenuItem("Remove") { removeFree(box) }
        ))
    }

    fun removeFree(box: FreeBox) {
        removeView(box)
        frees.remove(box)
        allFrees.remove(box)
        Prefs.setStr(context, ClockCfg.key(box.wid), null, fire = false)
        freeSave(); saveWPages(true)
        if (widgetsLocked()) rebuild() else requestLayout()
    }

    private fun editFree(box: FreeBox) {
        val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
        // picture of the desktop without the clock (at most ~600 px wide)
        val sc = minOf(1f, 600f / w)
        var bmp: android.graphics.Bitmap? = null
        try {
            bmp = android.graphics.Bitmap.createBitmap((w * sc).toInt().coerceAtLeast(1), (h * sc).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(bmp)
            cv.scale(sc, sc)
            val saved = frees.map { it to it.visibility }
            for (f in frees) f.visibility = View.INVISIBLE
            val fp = folderPanel; fp?.visibility = View.INVISIBLE
            draw(cv)
            for ((f, vis) in saved) f.visibility = vis
            fp?.visibility = View.VISIBLE
        } catch (_: Throwable) { bmp = null }
        val st = WidgetEditState
        st.snapshot = bmp; st.dw = w; st.dh = h
        st.fx = box.fx; st.fy = box.fy; st.fw = box.fw; st.fh = box.fh
        endWidgetEdit()
        try { act.startActivity(android.content.Intent(act, WidgetEditActivity::class.java).putExtra("wid", box.wid)) }
        catch (e: Exception) { context.toast("Cannot open") }
    }

    private fun insertNew(box: WidgetBox, screenX: Int, screenY: Int) {
        if (gCw <= 1) computeGrid(width, height)
        val sp = spansFor(box.defWdp, box.defHdp)
        box.sx = sp[0]; box.sy = sp[1]
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val col = Math.floor(((screenX - loc[0] - originX()).toDouble() / gCw)).toInt().coerceIn(0, maxOf(0, gCols - box.sx))
        val row = Math.floor(((screenY - loc[1] - originY()).toDouble() / gCh)).toInt().coerceIn(0, maxOf(0, gRows - box.sy))
        if (screenX >= 0 && screenY >= 0 && canPlace(box, col, row, box.sx, box.sy)) { box.col = col; box.row = row }
        else {
            val f = findFree(box, widgets, box.sx, box.sy)
            if (f == null) {
                context.toast("No free space on the desktop")
                if (box.wid > 0) act.host.deleteAppWidgetId(box.wid)
                else Prefs.setList(context, Prefs.K_LWIDGETS, Prefs.list(context, Prefs.K_LWIDGETS).filter { it.substringBefore(":") != box.wid.toString() }, fire = false)
                return
            }
            box.col = f[0]; box.row = f[1]; box.sx = f[2]; box.sy = f[3]
        }
        allWidgets += box
        addView(box)
        widgets += box
        saveWidgets()
        requestLayout()
    }

    fun saveWidgets() {
        val tag = "g" + posTag()
        val ids = allWidgets.map { it.wid }.toSet()
        val keep = Prefs.list(context, Prefs.K_WIDGETS).filter { s ->
            val p = s.split(",")
            p.size == 6 && p[0].startsWith("g") && p[0] != tag && (p[1].toIntOrNull() ?: 0) in ids
        }
        Prefs.setList(context, Prefs.K_WIDGETS, keep + allWidgets.filter { it.col >= 0 }.map { "$tag,${it.wid},${it.col},${it.row},${it.sx},${it.sy}" }, fire = false)
    }

    fun removeWidget(box: WidgetBox) {
        if (box.wid > 0) act.host.deleteAppWidgetId(box.wid)
        else Prefs.setList(context, Prefs.K_LWIDGETS, Prefs.list(context, Prefs.K_LWIDGETS).filter { it.substringBefore(":") != box.wid.toString() }, fire = false)
        removeView(box)
        widgets.remove(box)
        allWidgets.remove(box)
        saveWidgets(); saveWPages(true)
        if (widgetsLocked()) rebuild() else requestLayout()
    }

    /** Leaves resize mode on every widget (tap on empty desktop). */
    fun endWidgetEdit(except: View? = null) {
        for (w in widgets) if (w !== except) w.endEdit()
        for (f in frees) if (f !== except) f.endEdit()
        if (editFolder != null) { editFolder = null; refreshFolderEdit() }
    }

    private fun cell(): Pair<Int, Int> {
        val s = d(Prefs.iconDp(context))
        return Pair(s + d(36), s + d(50))
    }

    private var gCols = 1
    private var gRows = 1
    private var gCw = 1
    private var gCh = 1
    private val cells = HashMap<String, IntArray>()

    private fun computeGrid(w: Int, h: Int) {
        val (cw0, ch) = cell()
        val availW = w - paddingLeft - paddingRight - d(8)
        val avail = h - paddingTop - paddingBottom
        gRows = maxOf(1, avail / ch)
        gCols = maxOf(1, availW / cw0)
        // The leftover strip at the right / bottom (less than one cell) is shared out, so every part of the screen can hold icons.
        gCw = maxOf(cw0, availW / gCols)
        gCh = maxOf(ch, avail / gRows)
    }

    private fun posTag() = if (Modes.tablet(context)) "t" else "p"

    /** Stored places of the screen: key -> page,col,row. Places saved before screens existed count as page 0 of "All". */
    private fun storedPos(cat: String): HashMap<String, IntArray> {
        val m = HashMap<String, IntArray>()
        val tag = posTag()
        if (cat == Cats.ALL) for (s in Prefs.list(context, Prefs.K_POS)) {
            if (s.length < 4 || s.substring(0, 2) != "$tag@") continue
            val i2 = s.lastIndexOf('@')
            val i1 = s.lastIndexOf('@', i2 - 1)
            if (i1 < 2) continue
            val col = s.substring(i1 + 1, i2).toIntOrNull() ?: continue
            val row = s.substring(i2 + 1).toIntOrNull() ?: continue
            m[s.substring(2, i1)] = intArrayOf(0, col, row)
        }
        for (s in Prefs.list(context, Cats.K_POS2)) {
            val p = s.split("@", limit = 6)
            if (p.size != 6 || p[0] != tag || p[1] != cat) continue
            val pg = p[2].toIntOrNull() ?: continue
            val col = p[3].toIntOrNull() ?: continue
            val row = p[4].toIntOrNull() ?: continue
            m[p[5]] = intArrayOf(pg, col, row)
        }
        return m
    }

    private fun savePos(cat: String, updates: Map<String, IntArray>) {
        if (updates.isEmpty()) return
        val tag = posTag()
        val keep = Prefs.list(context, Cats.K_POS2).filter { s ->
            val p = s.split("@", limit = 6)
            !(p.size == 6 && p[0] == tag && p[1] == cat && updates.containsKey(p[5]))
        }
        Prefs.setList(context, Cats.K_POS2, keep + updates.map { "$tag@$cat@${it.value[0]}@${it.value[1]}@${it.value[2]}@${it.key}" }, fire = false)
    }

    /** Remembers a place (col,row) on the page that is showing. */
    private fun savePosHere(m: Map<String, IntArray>) = savePos(catId, m.mapValues { intArrayOf(pageIdx, it.value[0], it.value[1]) })

    private fun wRect(b: WidgetBox): IntArray {
        if (catId == Cats.ALL || catId == Cats.WIDGETS) {
            val l = originX() + b.col * gCw; val t = originY() + b.row * gCh
            return intArrayOf(l, t, l + maxOf(1, b.sx) * gCw, t + maxOf(1, b.sy) * gCh)
        }
        val p = wPack[b] ?: return intArrayOf(0, 0, 1, 1)
        val l = originX() + p[1] * gCw; val t = originY() + p[2] * gCh
        return intArrayOf(l, t, l + p[3] * gCw, t + p[4] * gCh)
    }

    private fun fRect(f: FreeBox, w: Int, h: Int): IntArray {
        if (catId == Cats.ALL || catId == Cats.WIDGETS) return freeRect(f, w, h)
        val p = wPack[f] ?: return intArrayOf(0, 0, 8, 8)
        val l = originX() + p[1] * gCw + d(4); val t = originY() + p[2] * gCh + d(4)
        return intArrayOf(l, t, l + p[3] * gCw - d(8), t + p[4] * gCh - d(8))
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
        computeGrid(w, h)
        for ((e, v) in items) {
            val fx = if (e is Entry.FolderE) e.sx else 1
            val fy = if (e is Entry.FolderE) e.sy else 1
            v.measure(MeasureSpec.makeMeasureSpec(gCw * fx, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(gCh * fy, MeasureSpec.EXACTLY))
        }
        folderPanel?.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        discoverView?.measure(MeasureSpec.makeMeasureSpec(maxOf(1, w - paddingLeft - paddingRight), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxOf(1, h - paddingTop - paddingBottom), MeasureSpec.EXACTLY))
        for (b in widgets) {
            val q = wRect(b)
            b.measure(MeasureSpec.makeMeasureSpec(maxOf(1, q[2] - q[0]), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxOf(1, q[3] - q[1]), MeasureSpec.EXACTLY))
        }
        for (f in frees) {
            val (fl, ft, fr, fb) = fRect(f, w, h)
            f.measure(MeasureSpec.makeMeasureSpec(maxOf(1, fr - fl), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxOf(1, fb - ft), MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        computeGrid(r - l, b - t)
        placeWidgets()
        if (builtSig != sig()) post { rebuild() }
        for ((e, v) in items) {
            val c = cells[e.key] ?: continue
            val left = paddingLeft + d(8) + c[0] * gCw
            val top = paddingTop + c[1] * gCh
            val fx = if (e is Entry.FolderE) e.sx else 1
            val fy = if (e is Entry.FolderE) e.sy else 1
            v.layout(left, top, left + gCw * fx, top + gCh * fy)
        }
        folderPanel?.layout(0, 0, r - l, b - t)
        discoverView?.layout(paddingLeft, paddingTop, (r - l) - paddingRight, (b - t) - paddingBottom)
        for (f in frees) {
            val (fl, ft, fr, fb) = fRect(f, r - l, b - t)
            f.layout(fl, ft, fr, fb)
        }
        for (box in widgets) {
            val q = wRect(box)
            box.layout(q[0], q[1], q[2], q[3])
            box.pushSizeIfChanged()
        }
    }

    /**
     * Trash icon: empty bin when the trash is empty, otherwise the last 1-3 trashed items sit inside the bin.
     * Safe to call often; the scan runs in the background (at most every few seconds unless forced).
     */
    fun refreshTrashIcon(force: Boolean = false) {
        val iv = trashIv ?: return
        val now = System.currentTimeMillis()
        if (!force && now - trashAt < 3000) return
        trashAt = now
        val c = context
        val base = trashBase ?: return
        Thread {
            val last = try { TrashStore.list(c).sortedByDescending { it.expiry }.take(5) } catch (_: Exception) { emptyList() }
            val sig = last.joinToString("|") { it.file.absolutePath }
            if (sig == trashSig && trashBmp != null) return@Thread
            val bmp = try { composeTrash(last) } catch (_: Throwable) { null }
            post {
                trashSig = sig
                trashBmp = bmp
                if (trashIv === iv) iv.setImageDrawable(if (bmp != null) trashDrawable(bmp) else base)
            }
        }.start()
    }

    private fun trashDrawable(fg: android.graphics.Bitmap): android.graphics.drawable.Drawable =
        android.graphics.drawable.AdaptiveIconDrawable(
            android.graphics.drawable.ColorDrawable(0xFFC5D3E4.toInt()),
            android.graphics.drawable.BitmapDrawable(resources, fg)
        )

    /** Foreground of the trash icon: the code-drawn glass bin with the last trashed items inside (see TrashArt). */
    private fun composeTrash(last: List<TrashStore.Item>): android.graphics.Bitmap {
        val tiles = last.reversed().map { Thumbs.tile(it.file, it.name, TrashArt.TILE) } // oldest first
        return TrashArt.render(tiles, 432)
    }

    private fun setEmph(v: View?, flag: Int, on: Boolean) {
        if (v == null) return
        val f = (emph[v] ?: 0).let { if (on) it or flag else it and flag.inv() }
        emph[v] = f
        val active = f != 0
        v.animate().scaleX(if (active) 1.1f else 1f).scaleY(if (active) 1.1f else 1f).setDuration(120).start()
        if (v is FolderView) v.emphasized = active
        else v.foreground = if (active) Colors.rounded(0x00000000, d(14).toFloat()).also {
            (it as android.graphics.drawable.GradientDrawable).setStroke(d(2), Colors.accent(context))
        } else null
    }

    private fun viewOf(e: Entry): View? = items.firstOrNull { it.first === e }?.second

    /** Pointer hover, keyboard focus (Tab / arrow keys) and Enter / menu key for a desktop icon. */
    private fun wireEmphasis(v: View, e: Entry) {
        v.isFocusable = true
        v.defaultFocusHighlightEnabled = false
        v.setOnHoverListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) setEmph(v, 1, true)
            else if (ev.actionMasked == MotionEvent.ACTION_HOVER_EXIT) setEmph(v, 1, false)
            false
        }
        v.setOnFocusChangeListener { _, has -> setEmph(v, 2, has) }
        v.setOnKeyListener { _, code, ev ->
            if (ev.action != android.view.KeyEvent.ACTION_UP) return@setOnKeyListener code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_MENU
            when (code) {
                android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_DPAD_CENTER -> {
                    clearSelection()
                    if (e is Entry.FolderE) showFolder(e.id) else open(e)
                    true
                }
                android.view.KeyEvent.KEYCODE_MENU -> {
                    val loc = IntArray(2); v.getLocationOnScreen(loc)
                    itemMenu(e, loc[0] + v.width / 2, loc[1] + v.height / 2, true)
                    true
                }
                else -> false
            }
        }
    }

    private fun spanOf(e: Entry) = if (e is Entry.FolderE) intArrayOf(e.sx, e.sy) else intArrayOf(1, 1)

    /** The item that covers this grid cell (folders cover several cells). */
    private fun itemAtCell(col: Int, row: Int, exclude: Entry): Entry? {
        for ((e, _) in items) {
            if (e === exclude) continue
            val c = cells[e.key] ?: continue
            val sp = spanOf(e)
            if (col >= c[0] && col < c[0] + sp[0] && row >= c[1] && row < c[1] + sp[1]) return e
        }
        return null
    }

    private fun folderRectFree(f: Entry.FolderE, col: Int, row: Int, sx: Int, sy: Int): Boolean {
        if (col < 0 || row < 0 || col + sx > gCols || row + sy > gRows) return false
        for (cc in col until col + sx) for (rr in row until row + sy) {
            if (blockedByWidget(cc, rr)) return false
            val o = itemAtCell(cc, rr, f)
            if (o is Entry.FolderE) return false
        }
        return true
    }

    /** Called when an icon is dropped after dragging. */
    private fun dropItem(e: Entry, v: View, dx: Float, dy: Float) {
        v.translationX = 0f; v.translationY = 0f; v.elevation = 0f
        val old = cells[e.key] ?: return
        if (e is Entry.FolderE) {
            val col = Math.round((v.left + dx - paddingLeft - d(8)) / gCw).coerceIn(0, maxOf(0, gCols - e.sx))
            val row = Math.round((v.top + dy - paddingTop) / gCh).coerceIn(0, maxOf(0, gRows - e.sy))
            if (folderRectFree(e, col, row, e.sx, e.sy)) { savePosHere(mapOf(e.key to intArrayOf(col, row))); rebuild() } else requestLayout()
            return
        }
        val cx = v.left + v.width / 2f + dx
        val cy = v.top + v.height / 2f + dy
        val col = ((cx - paddingLeft - d(8)) / gCw).toInt().coerceIn(0, gCols - 1)
        val row = ((cy - paddingTop) / gCh).toInt().coerceIn(0, gRows - 1)
        if (blockedByWidget(col, row)) { requestLayout(); return }
        val target = itemAtCell(col, row, e)
        if (target != null) {
            // Dropping a file on the Trash icon moves it to the trash straight away.
            if (e is Entry.FileE && isTrashEntry(target)) { requestLayout(); trashDropped(e.file); return }
            if (target is Entry.FolderE) { addToFolder(target, e); return }
            createFolder(target, e); return
        }
        savePosHere(mapOf(e.key to intArrayOf(col, row)))
        rebuild()
    }

    private fun createFolder(target: Entry, dragged: Entry) {
        val c = context
        val id = System.currentTimeMillis().toString(36)
        val list = FolderStore.load(c)
        list += FolderData(id, "Folder", 1, 1, mutableListOf(target.key, dragged.key))
        FolderStore.save(c, list)
        cells[target.key]?.let { savePosHere(mapOf("folder:$id" to intArrayOf(it[0], it[1]))) }
        reload()
    }

    private fun addToFolder(f: Entry.FolderE, e: Entry) {
        FolderStore.update(context, f.id) { if (e.key !in it.members) it.members += e.key }
        reload()
    }

    private fun refreshFolderEdit() {
        for ((e, v) in items) if (e is Entry.FolderE && v is FolderView) v.editing = (e.id == editFolder)
    }

    // ------------------------------------------------------------ folder window

    fun closeFolder(): Boolean {
        val p = folderPanel ?: return false
        removeView(p)
        folderPanel = null
        openFolderId = null
        blurRest(null)
        return true
    }

    private fun showFolder(id: String) {
        val f = items.firstOrNull { (it.first as? Entry.FolderE)?.id == id }?.first as? Entry.FolderE ?: return
        folderPanel?.let { removeView(it) }
        openFolderId = id
        val c = context
        val ov = FrameLayout(c)
        ov.setBackgroundColor(0x40000000)
        ov.isClickable = true
        ov.setOnClickListener { closeFolder() }

        val card = LinearLayout(c)
        card.orientation = LinearLayout.VERTICAL
        card.background = Colors.panel(c, Colors.surface(c), d(32).toFloat())
        card.setPadding(d(14), d(14), d(14), d(18))
        card.isClickable = true
        UiText.applyDirection(card, c)

        val title = TextView(c)
        title.text = UiText.text(c, f.name)
        title.textSize = 28f
        title.gravity = Gravity.CENTER
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.setTextColor(Colors.text(c))
        title.setOnLongClickListener { renameFolder(f); true }
        card.addView(title, LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = d(10) })

        // a row is tall enough for the icon and two lines of name (also with a large system font)
        val lineH = android.text.TextPaint().also { it.textSize = 12f * resources.displayMetrics.scaledDensity }.fontSpacing
        val rowH = d(8) + d(56) + d(4) + (lineH * 2).toInt() + d(12)
        val grid = android.widget.GridView(c)
        grid.numColumns = 3
        grid.selector = android.graphics.drawable.ColorDrawable(0)
        grid.verticalSpacing = 0
        grid.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = f.resolved.size
            override fun getItem(p: Int) = f.resolved[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val en = f.resolved[p]
                val cell = LinearLayout(c)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.setPadding(d(4), d(8), d(4), d(4))
                cell.layoutParams = android.widget.AbsListView.LayoutParams(MATCH, rowH)
                val iv = android.widget.ImageView(c)
                val label: String
                if (en is Entry.AppE) { iv.setImageDrawable(Icons.app(c, en.info.comp, false)); label = en.info.label }
                else if (en is Entry.FileE) { Thumbs.bind(iv, en.file, d(56)); label = en.file.shown() } else label = ""
                cell.addView(iv, LinearLayout.LayoutParams(d(56), d(56)))
                val t = TextView(c)
                t.text = label
                t.textSize = 12f
                t.maxLines = 2
                t.gravity = Gravity.CENTER
                t.ellipsize = android.text.TextUtils.TruncateAt.END
                t.setTextColor(Colors.text(c))
                cell.addView(t, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = d(4) })
                return cell
            }
        }
        grid.setOnItemClickListener { _, _, p, _ -> val en = f.resolved[p]; closeFolder(); open(en) }
        grid.setOnItemLongClickListener { _, v, p, _ ->
            val en = f.resolved[p]
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            PopupMenuX(c, this).also { it.spotlight = v }.show(loc[0] + v.width / 2, loc[1] + v.height / 2, listOf(
                MenuItem("Open") { closeFolder(); open(en) },
                MenuItem("Remove from folder") { removeFromFolder(f, en) }
            ))
            true
        }
        val rows = ((f.resolved.size + 2) / 3).coerceIn(1, 3)
        card.addView(grid, LinearLayout.LayoutParams(MATCH, rows * rowH + d(6)))

        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        val cw = minOf(d(340), w - d(32))
        ov.addView(card, FrameLayout.LayoutParams(cw, WRAP, Gravity.CENTER))
        addView(ov, LayoutParams(MATCH, MATCH))
        folderPanel = ov
        blurRest(ov)
        requestLayout()
    }

    /** While a folder is open everything behind it is blurred (not the folder itself); null = clear. */
    private fun blurRest(except: View?) {
        for (i in 0 until childCount) { val ch = getChildAt(i); Blur.behind(context, ch, except != null && ch !== except) }
        act.blurChrome(except != null)
    }

    private fun renameFolder(f: Entry.FolderE) {
        val et = EditText(act)
        et.setText(f.name)
        et.selectAll()
        AlertDialog.Builder(act).setTitle("Rename").setView(et)
            .setPositiveButton("Rename") { _, _ ->
                val name = et.text.toString().trim().replace("|", " ")
                if (name.isNotBlank()) { FolderStore.update(context, f.id) { it.name = name }; reload() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun removeFromFolder(f: Entry.FolderE, en: Entry) {
        FolderStore.update(context, f.id) { it.members.remove(en.key) }
        reload()
    }

    private fun ungroup(f: Entry.FolderE) {
        val c = context
        FolderStore.save(c, FolderStore.load(c).filter { it.id != f.id })
        if (openFolderId == f.id) closeFolder()
        reload()
    }

    private inner class ItemTouch(val e: Entry, val v: View) : View.OnTouchListener {
        private var state = 0 // 0 idle, 1 pressed, 2 dragging
        private var x0 = 0f
        private var y0 = 0f
        private var moved = false
        private var secondary = false
        private var fResize = false
        private var blockLong = false
        private var sSx = 1
        private var sSy = 1
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private val longPress = Runnable {
            if (state == 1) {
                state = 2; dragLock = true
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                v.elevation = d(8).toFloat()
                v.bringToFront()
            }
        }

        override fun onTouch(view: View, ev: MotionEvent): Boolean {
            val mouse = ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    secondary = (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                    if (secondary) { itemMenu(e, ev.rawX.toInt(), ev.rawY.toInt(), true); return true }
                    if (e is Entry.FolderE && editFolder == e.id && v is FolderView && v.inHandle(ev.x, ev.y)) {
                        fResize = true; dragLock = true; x0 = ev.rawX; y0 = ev.rawY; sSx = e.sx; sSy = e.sy
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    // a folder with its resize frame: pressing near the edges is for resizing only, no drag / menu
                    blockLong = e is Entry.FolderE && editFolder == e.id && run {
                        val z = minOf(d(40).toFloat(), minOf(v.width, v.height) / 4f)
                        !(ev.x >= z && ev.x <= v.width - z && ev.y >= z && ev.y <= v.height - z)
                    }
                    if (blockLong) { dragLock = true; state = 0; parent?.requestDisallowInterceptTouchEvent(true); return true }
                    x0 = ev.rawX; y0 = ev.rawY; moved = false; state = 1
                    lastX = ev.rawX.toInt(); lastY = ev.rawY.toInt()
                    if (!mouse) v.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (fResize && e is Entry.FolderE) {
                        val cc = cells[e.key] ?: return true
                        val nsx = Math.round((sSx * gCw + (ev.rawX - x0)) / gCw.toFloat()).coerceIn(1, minOf(3, gCols - cc[0]))
                        val nsy = Math.round((sSy * gCh + (ev.rawY - y0)) / gCh.toFloat()).coerceIn(1, minOf(3, gRows - cc[1]))
                        if ((nsx != e.sx || nsy != e.sy) && folderRectFree(e, cc[0], cc[1], nsx, nsy)) {
                            e.sx = nsx; e.sy = nsy
                            requestLayout()
                        }
                        return true
                    }
                    if (secondary) return true
                    val dx = ev.rawX - x0
                    val dy = ev.rawY - y0
                    val far = Math.hypot(dx.toDouble(), dy.toDouble()) > slop
                    if (state == 1 && far) {
                        if (mouse) { state = 2; dragLock = true; v.elevation = d(8).toFloat(); v.bringToFront() }
                        else { v.removeCallbacks(longPress); state = 0 }
                    }
                    if (state == 2) {
                        if (far) moved = true
                        v.translationX = dx; v.translationY = dy
                        trackEdge(e, v, ev.rawX)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (carry == null) dragLock = false
                    endTrack()
                    v.removeCallbacks(longPress)
                    if (blockLong) { blockLong = false; return true }
                    if (fResize && e is Entry.FolderE) {
                        fResize = false
                        FolderStore.update(context, e.id) { it.sx = e.sx; it.sy = e.sy }
                        return true
                    }
                    if (secondary) { secondary = false; return true }
                    val dx = ev.rawX - x0
                    val dy = ev.rawY - y0
                    val st = state
                    state = 0
                    when (st) {
                        1 -> { clearSelection(); open(e) }
                        2 -> if (moved) dropItem(e, v, dx, dy) else {
                            v.translationX = 0f; v.translationY = 0f; v.elevation = 0f
                            itemMenu(e, ev.rawX.toInt(), ev.rawY.toInt())
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (carry == null) dragLock = false
                    endTrack()
                    v.removeCallbacks(longPress)
                    if (fResize && e is Entry.FolderE) { fResize = false; FolderStore.update(context, e.id) { it.sx = e.sx; it.sy = e.sy } }
                    state = 0; secondary = false
                    v.translationX = 0f; v.translationY = 0f; v.elevation = 0f
                    return true
                }
            }
            return true
        }
    }

    private fun makeItem(e: Entry): View {
        val c = context
        if (e is Entry.FolderE) {
            val fv = FolderView(c, e)
            fv.setOnTouchListener(ItemTouch(e, fv))
            wireEmphasis(fv, e)
            return fv
        }
        val v = LinearLayout(c)
        v.orientation = LinearLayout.VERTICAL
        v.gravity = Gravity.CENTER_HORIZONTAL
        v.setPadding(d(4), d(6), d(4), d(4))
        val sz = d(Prefs.iconDp(c))
        val ic = BadgedIcon(c)
        val label: String
        when (e) {
            is Entry.AppE -> {
                ic.setImageDrawable(Icons.app(c, e.info.comp, false))
                if (isTrashEntry(e)) { trashIv = ic; trashBase = ic.drawable; trashBmp?.let { ic.setImageDrawable(trashDrawable(it)) } }
                ic.dot = Prefs.bool(c, Prefs.K_DOTS, true) && NotifState.has(e.info.pkg)
                label = e.info.label
            }
            is Entry.FileE -> {
                Thumbs.bind(ic, e.file, sz)
                label = e.file.shown()
                if (Clip.cut && Clip.files.contains(e.file)) ic.alpha = 0.45f
            }
            is Entry.FolderE -> label = e.name
        }
        v.addView(ic, LinearLayout.LayoutParams(sz, sz))
        val t = TextView(c)
        t.text = label
        t.textSize = 12f
        t.setTextColor(Color.WHITE)
        t.setShadowLayer(4f, 0f, 1f, Color.BLACK)
        t.maxLines = 2
        t.gravity = Gravity.CENTER
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        v.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        v.setOnTouchListener(ItemTouch(e, v))
        wireEmphasis(v, e)
        return v
    }

    private fun open(e: Entry) {
        when (e) {
            is Entry.AppE -> Launch.app(context, e.info.comp)
            is Entry.FileE -> openFile(e.file)
            is Entry.FolderE -> {
                val v = items.firstOrNull { it.first === e }?.second as? FolderView ?: return
                if (editFolder != null) { editFolder = null; refreshFolderEdit() }
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                val key = v.hit(lastX - loc[0], lastY - loc[1])
                val member = key?.let { k -> e.resolved.firstOrNull { it.key == k } }
                if (member != null) open(member) else showFolder(e.id)
            }
        }
    }

    fun openFile(f: File) {
        if (f.extension == "desktoplink") {
            try {
                val targetPath = f.readLines().getOrNull(1)
                val target = targetPath?.let { File(it) }
                if (target != null && target.exists()) Files.open(context, target) else context.toast("Shortcut target not found")
            } catch (_: Exception) { context.toast("Invalid shortcut") }
        } else Files.open(context, f)
    }

    private fun blockedByWidget(col: Int, row: Int): Boolean = catId == Cats.ALL && pageIdx == 0 &&
        allWidgets.any { o -> pageOfW(o.wid) < 0 && o.col >= 0 && col >= o.col && col < o.col + o.sx && row >= o.row && row < o.row + o.sy }

    // ---------------------------------------------------------------- menus

    private fun desktopMenu(x: Int, y: Int) {
        val c = context
        val sel = selectedFile()
        val hasClip = Clip.hasAny(c)
        val mode = Prefs.iconMode(c)
        val m = PopupMenuX(c, this)
        m.show(x, y, listOf(
            MenuItem("View", children = listOf(
                MenuItem("Small", checked = mode == 0) { Prefs.setIconMode(c, 0) },
                MenuItem("Normal", checked = mode == 1) { Prefs.setIconMode(c, 1) },
                MenuItem("Large", checked = mode == 2) { Prefs.setIconMode(c, 2) }
            )),
            MenuItem("Cut", enabled = sel != null, bold = true) { sel?.let { Clip.set(c, listOf(it), true); reload() } },
            MenuItem("Copy", enabled = sel != null, bold = true) { sel?.let { Clip.set(c, listOf(it), false); c.toast("Copied") } },
            MenuItem("Paste", enabled = hasClip, bold = true) { paste() },
            MenuItem("New", children = listOf(
                MenuItem("File") { newItem(false) },
                MenuItem("Folder") { newItem(true) }
            )),
            MenuItem("Wallpapers") { Launch.wallpapers(c) },
            MenuItem("Widgets") { act.pickWidget(x, y) },
            MenuItem("Settings") { Launch.start(c, android.content.Intent(c, SettingsActivity::class.java)) }
        ))
    }

    private fun itemMenu(e: Entry, x: Int, y: Int, forceMenu: Boolean = false) {
        val c = context
        if (e is Entry.FolderE) {
            // Long press: outline with the resize handle; long press again (or right click): menu.
            if (editFolder != e.id && !forceMenu) { editFolder = e.id; refreshFolderEdit(); return }
            val fview = viewOf(e)
            setEmph(fview, 4, true)
            PopupMenuX(c, this).also { it.spotlight = fview; it.onClosed = { setEmph(fview, 4, false) } }.show(x, y, listOf(
                MenuItem("Open") { showFolder(e.id) },
                MenuItem("Rename") { renameFolder(e) },
                moveItem(e),
                MenuItem("Ungroup") { ungroup(e) }
            ))
            return
        }
        selectedKey = e.key
        refreshSelection()
        // the icon the menu belongs to stays raised and outlined while the menu is open
        val iview = viewOf(e)
        setEmph(iview, 4, true)
        val m = PopupMenuX(c, this)
        m.spotlight = iview
        m.onClosed = { setEmph(iview, 4, false) }
        when (e) {
            is Entry.AppE -> m.show(x, y, listOf(
                MenuItem("Open") { open(e) },
                MenuItem("App Info") { Launch.info(c, e.info.pkg) },
                MenuItem("Share") { Launch.shareApp(c, e.info.pkg) },
                Launch.sizeMenu(c, e.info.comp),
                moveItem(e),
                MenuItem("Remove") {
                    Prefs.setList(c, Prefs.K_DESKAPPS, Prefs.list(c, Prefs.K_DESKAPPS).filter { it != e.info.comp.flattenToString() })
                },
                MenuItem("Uninstall") { Launch.uninstall(c, e.info.pkg) }
            ))
            is Entry.FileE -> m.show(x, y, listOf(
                MenuItem("Open") { open(e) },
                MenuItem("Rename") { renameItem(e.file) },
                MenuItem("Cut", bold = true) { Clip.set(c, listOf(e.file), true); reload() },
                MenuItem("Copy", bold = true) { Clip.set(c, listOf(e.file), false); c.toast("Copied") },
                MenuItem("Share", enabled = e.file.isFile) { Launch.shareFile(c, e.file) },
                moveItem(e),
                MenuItem("Remove") {
                    Prefs.setList(c, Prefs.K_HIDDEN, Prefs.list(c, Prefs.K_HIDDEN) + e.file.absolutePath)
                    c.toast("Removed from desktop (file kept)")
                },
                MenuItem("Delete") { confirmDelete(e.file) }
            ))
            is Entry.FolderE -> {}
        }
    }

    private fun renameItem(f: File) {
        val et = EditText(act)
        et.setText(f.name)
        et.selectAll()
        AlertDialog.Builder(act).setTitle("Rename").setView(et)
            .setPositiveButton("Rename") { _, _ ->
                val name = et.text.toString().trim().replace('/', '_')
                if (name.isNotBlank() && name != f.name) {
                    val target = File(f.parentFile, name)
                    if (target.exists()) context.toast("A file with that name already exists")
                    else if (!f.renameTo(target)) context.toast("Rename failed")
                    reload()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun isTrashEntry(e: Entry) = e is Entry.AppE && e.info.comp.className.endsWith("TrashActivity")

    /** Delete dialog: file name, "Move to trash" tick (on by default) and a Delete button. */
    private fun confirmDelete(f: File) {
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(d(24), d(22), d(24), d(20))
        box.background = Colors.panel(act, Colors.surfaceSolid(act), d(28).toFloat())
        UiText.applyDirection(box, act)
        val name = TextView(act)
        name.text = f.name
        name.textSize = 20f
        name.gravity = Gravity.CENTER
        name.maxLines = 2
        name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        name.setTextColor(Colors.text(act))
        box.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
        val cb = android.widget.CheckBox(act)
        cb.text = UiText.text(act, "Move to trash")
        cb.isChecked = true
        cb.textSize = 17f
        cb.setTextColor(Colors.text(act))
        box.addView(cb, LinearLayout.LayoutParams(WRAP, WRAP).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = d(10) })
        val btn = TextView(act)
        btn.text = UiText.text(act, "Delete")
        btn.textSize = 17f
        btn.gravity = Gravity.CENTER
        btn.setTextColor(Colors.iconFg(act))
        btn.background = Colors.rounded(Colors.iconBg(act), d(28).toFloat())
        box.addView(btn, LinearLayout.LayoutParams(MATCH, d(52)).also { it.topMargin = d(14) })
        val dlg = AlertDialog.Builder(act).setView(box).create()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        btn.setOnClickListener {
            dlg.dismiss()
            val toTrash = cb.isChecked
            Thread {
                if (toTrash) TrashStore.trash(context, f) else f.deleteRecursively()
                post { trashAt = 0L; reload() }
            }.start()
        }
        dlg.show()
    }

    /** A file was dragged onto the Trash icon: no question asked. */
    private fun trashDropped(f: File) {
        Thread {
            val ok = TrashStore.trash(context, f)
            post { trashAt = 0L; reload(); context.toast(UiText.text(context, if (ok) "Moved to trash" else "Could not move to trash")) }
        }.start()
    }

    private fun paste() {
        val c = context
        Thread {
            val n = try { Clip.paste(c, Files.root(c)) } catch (_: Exception) { 0 }
            post { reload(); c.toast(if (n > 0) "Pasted" else "Nothing to paste") }
        }.start()
    }

    private fun newItem(folder: Boolean) {
        val c = context
        val et = EditText(act)
        et.setText(if (folder) "New Folder" else "New File.txt")
        et.selectAll()
        AlertDialog.Builder(act).setTitle(if (folder) "New folder" else "New file").setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim().replace('/', '_')
                if (name.isNotEmpty()) {
                    try {
                        val f = Files.unique(Files.root(c), name)
                        if (folder) f.mkdirs() else f.createNewFile()
                    } catch (e: Exception) { c.toast("Cannot create: " + e.message) }
                    reload()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    fun widgetMenu(box: WidgetBox, x: Int, y: Int) {
        val m = PopupMenuX(context, this)
        m.show(x, y, listOf(MenuItem("Remove") { removeWidget(box) }))
    }
}

/**
 * A widget on the desktop. Position and size are whole grid cells (col,row,sx,sy), so widgets always
 * snap to the home screen grid. Long-press shows the resize frame (corner handles); long-press again
 * shows the Remove menu.
 */
class WidgetBox(
    ctx: Context, val wid: Int, private val content: View, private val hv: AppWidgetHostView?,
    val defWdp: Int, val defHdp: Int, private val owner: DesktopView
) : FrameLayout(ctx) {
    var col = -1
    var row = -1
    var sx = 0
    var sy = 0
    /** Old pixel based position (px,py,w,h) from a previous version, converted on first layout. */
    var legacy: IntArray? = null

    private var moving = false
    private var moved = false
    private var resizeMode = false
    private var justActivatedResize = false
    private var resizing = false
    private var resizeCorner = 3
    private var startRawX = 0f
    private var startRawY = 0f
    private var startCol = 0
    private var startRow = 0
    private var startSx = 0
    private var startSy = 0
    private var lastPushW = -1
    private var lastPushH = -1
    private val edgePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.accent(ctx); style = android.graphics.Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat()
    }
    private val handlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.accent(ctx); style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeWidth = ctx.dp(4).toFloat()
    }
    private var hoverCorner = -1

    /** Corners that make sense: in the middle of the screen all four, at one side only the corners on the far side. */
    private fun cornerEnabled(i: Int): Boolean {
        val leftRoom = col > 0
        val rightRoom = col + sx < owner.gridCols()
        if (!leftRoom && !rightRoom) return true
        return if (i % 2 == 0) leftRoom else rightRoom
    }

    /** The middle of the widget: only there a long press opens the menu. */
    private fun inCenter(x: Float, y: Float): Boolean {
        val z = minOf(context.dp(48).toFloat(), minOf(width, height) / 4f)
        return x >= z && x <= width - z && y >= z && y <= height - z
    }

    private fun cornerAt(x: Float, y: Float): Int {
        if (owner.widgetsLocked()) return -1
        val z = context.dp(44)
        val l = x <= z
        val r = x >= width - z
        val t = y <= z
        val b = y >= height - z
        val i = when {
            l && t -> 0
            r && t -> 1
            l && b -> 2
            r && b -> 3
            else -> -1
        }
        return if (i >= 0 && cornerEnabled(i)) i else -1
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE || ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
            val h = cornerAt(ev.x, ev.y)
            if (h != hoverCorner) { hoverCorner = h; invalidate() }
        } else if (ev.actionMasked == MotionEvent.ACTION_HOVER_EXIT && hoverCorner != -1) { hoverCorner = -1; invalidate() }
        return super.dispatchGenericMotionEvent(ev)
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            // while the frame is shown, a long press near the edges / corners is part of resizing: no menu
            if (resizeMode && !inCenter(e.x, e.y)) return
            moving = true; moved = false
            rememberStart()
            if (!resizeMode) {
                resizeMode = true; justActivatedResize = true
                owner.endWidgetEdit(this@WidgetBox)
                invalidate()
            } else { moving = false; owner.widgetMenu(this@WidgetBox, e.rawX.toInt(), e.rawY.toInt()) }
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    })

    init {
        // Small gutter so neighbouring widgets do not touch.
        addView(content, LayoutParams(MATCH, MATCH).also { it.setMargins(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4)) })
        setWillNotDraw(false)
    }

    private fun rememberStart() { startCol = col; startRow = row; startSx = sx; startSy = sy }

    fun endEdit() { if (resizeMode) { resizeMode = false; invalidate() } }
    fun editing() = resizeMode

    private val lockGd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            owner.widgetMenu(this@WidgetBox, e.rawX.toInt(), e.rawY.toInt())
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    })

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (resizeMode) {
            val inset = context.dp(2).toFloat()
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, context.dp(12).toFloat(), context.dp(12).toFloat(), edgePaint)
        }
        for (i in 0..3) {
            if (!(resizeMode && cornerEnabled(i)) && i != hoverCorner) continue
            val rad = context.dp(16).toFloat()
            val m = context.dp(8).toFloat()
            val cx = if (i % 2 == 0) m + rad else width - m - rad
            val cy = if (i < 2) m + rad else height - m - rad
            val start = when (i) { 0 -> 185f; 1 -> 275f; 2 -> 95f; else -> 5f }
            canvas.drawArc(android.graphics.RectF(cx - rad, cy - rad, cx + rad, cy + rad), start, 80f, false, handlePaint)
        }
    }

    fun pushSizeIfChanged() {
        if (width != lastPushW || height != lastPushH) { lastPushW = width; lastPushH = height; pushSize() }
    }

    fun pushSize() {
        val h = hv ?: return
        val dm = context.resources.displayMetrics
        val dw = ((width - context.dp(8)) / dm.density).toInt()
        val dh = ((height - context.dp(8)) / dm.density).toInt()
        if (dw <= 0 || dh <= 0) return
        try { h.updateAppWidgetSize(Bundle(), dw, dh, dw, dh) } catch (_: Exception) {}
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (owner.widgetsLocked()) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                owner.widgetMenu(this, ev.rawX.toInt(), ev.rawY.toInt()); return true
            }
            lockGd.onTouchEvent(ev)
            return false
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            startRawX = ev.rawX; startRawY = ev.rawY
            rememberStart()
            if ((ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                owner.widgetMenu(this, ev.rawX.toInt(), ev.rawY.toInt()); return true
            }
            val mouse = ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
            val corner = cornerAt(ev.x, ev.y)
            resizing = (resizeMode || mouse) && corner >= 0
            if (resizing) {
                if (!resizeMode) { resizeMode = true; owner.endWidgetEdit(this); invalidate() }
                resizeCorner = corner
                moving = true; moved = false; return true
            }
        }
        gd.onTouchEvent(ev)
        // While the frame is shown the whole widget is a drag handle (the widget itself is not clickable).
        return moving || resizeMode
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (owner.widgetsLocked()) return true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { moving = resizeMode; moved = false; return true }
        // Keep feeding the gesture detector (long-press timer) unless a corner handle is being dragged.
        if (!resizing) gd.onTouchEvent(ev)
        if (!moving) return true
        val cw = owner.cellW().toFloat()
        val ch = owner.cellH().toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - startRawX
                val dy = ev.rawY - startRawY
                if (Math.abs(dx) > context.dp(6) || Math.abs(dy) > context.dp(6)) moved = true
                if (!moved) return true
                var nc = col; var nr = row; var nsx = sx; var nsy = sy
                if (resizing) {
                    val right = resizeCorner == 1 || resizeCorner == 3
                    val bottom = resizeCorner == 2 || resizeCorner == 3
                    if (right) nsx = Math.round(((startSx * cw) + dx) / cw).coerceAtLeast(1)
                    else {
                        nc = Math.round(startCol + dx / cw).coerceIn(0, startCol + startSx - 1)
                        nsx = startCol + startSx - nc
                    }
                    if (bottom) nsy = Math.round(((startSy * ch) + dy) / ch).coerceAtLeast(1)
                    else {
                        nr = Math.round(startRow + dy / ch).coerceIn(0, startRow + startSy - 1)
                        nsy = startRow + startSy - nr
                    }
                } else {
                    nc = Math.round(startCol + dx / cw).coerceIn(0, maxOf(0, owner.gridCols() - sx))
                    nr = Math.round(startRow + dy / ch).coerceIn(0, maxOf(0, owner.gridRows() - sy))
                }
                if ((nc != col || nr != row || nsx != sx || nsy != sy) && owner.canPlace(this, nc, nr, nsx, nsy)) {
                    col = nc; row = nr; sx = nsx; sy = nsy
                    owner.requestLayout(); invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                moving = false
                if (moved) { owner.saveWidgets(); pushSize() }
                justActivatedResize = false
                resizing = false
            }
        }
        return true
    }
}
