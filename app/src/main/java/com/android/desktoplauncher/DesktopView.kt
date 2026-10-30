package com.android.desktoplauncher

import android.app.AlertDialog
import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
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
    private var selectedKey: String? = null
    private var editFolder: String? = null
    private var trashIv: BadgedIcon? = null
    private var trashBase: android.graphics.drawable.Drawable? = null
    private var trashSig = ""
    private var trashBmp: android.graphics.Bitmap? = null
    private var trashAt = 0L
    private var openFolderId: String? = null
    private var folderPanel: View? = null
    private var lastX = 0
    private var lastY = 0

    init {
        UiText.applyDirection(this, act)
        isClickable = true
        isLongClickable = true
        setOnClickListener { clearSelection(); endWidgetEdit(); act.closeDrawer() }
        setOnLongClickListener { desktopMenu(lastX, lastY); true }
        setSecondaryClick { x, y -> desktopMenu(x, y) }
        setOnDragListener { _, event ->
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
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { lastX = ev.rawX.toInt(); lastY = ev.rawY.toInt() }
        return super.dispatchTouchEvent(ev)
    }

    private fun d(v: Int) = context.dp(v)

    fun clearSelection() { selectedKey = null; refreshSelection() }

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
        removeAllViews(); items.clear(); widgets.clear(); folderPanel = null; trashIv = null
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
        for (e in list) {
            val v = makeItem(e)
            addView(v)
            items += e to v
        }
        refreshFolderEdit()
        refreshTrashIcon()
        loadWidgets()
        refreshSelection()
        openFolderId?.let { id ->
            if (items.any { (it.first as? Entry.FolderE)?.id == id }) showFolder(id) else openFolderId = null
        }
        requestLayout()
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
            addView(box)
            widgets += box
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
        for (b in widgets) {
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
            val ok = b.col >= 0 && b.row >= 0 && b.col + b.sx <= gCols && b.row + b.sy <= gRows && !overlaps(b, b.col, b.row, b.sx, b.sy, placed)
            if (!ok) {
                val f = findFree(b, placed, b.sx, b.sy)
                if (f != null) { b.col = f[0]; b.row = f[1]; b.sx = f[2]; b.sy = f[3] } else { b.col = 0; b.row = 0 }
                changed = true
            }
            placed += b
        }
        if (changed) post { saveWidgets() }
    }

    fun addWidget(id: Int, info: android.appwidget.AppWidgetProviderInfo, screenX: Int, screenY: Int) {
        val hv: AppWidgetHostView = act.host.createView(act, id, info)
        val dens = resources.displayMetrics.density
        val box = WidgetBox(act, id, hv, hv, (maxOf(info.minWidth, 1) / dens).toInt(), (maxOf(info.minHeight, 1) / dens).toInt(), this)
        insertNew(box, screenX, screenY)
    }

    fun addInternal(type: String, screenX: Int, screenY: Int) {
        val def = InternalWidgets.def(type) ?: return
        val id = InternalWidgets.nextId(widgets.map { it.wid } + Prefs.list(context, Prefs.K_LWIDGETS).mapNotNull { it.substringBefore(":").toIntOrNull() })
        Prefs.setList(context, Prefs.K_LWIDGETS, Prefs.list(context, Prefs.K_LWIDGETS) + "$id:$type", fire = false)
        val box = WidgetBox(act, id, InternalWidgets.create(act, type) { act.openSearchFromWidget() }, null, def.minWdp, def.minHdp, this)
        insertNew(box, screenX, screenY)
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
        addView(box)
        widgets += box
        saveWidgets()
        requestLayout()
    }

    fun saveWidgets() {
        val tag = "g" + posTag()
        val ids = widgets.map { it.wid }.toSet()
        val keep = Prefs.list(context, Prefs.K_WIDGETS).filter { s ->
            val p = s.split(",")
            p.size == 6 && p[0].startsWith("g") && p[0] != tag && (p[1].toIntOrNull() ?: 0) in ids
        }
        Prefs.setList(context, Prefs.K_WIDGETS, keep + widgets.filter { it.col >= 0 }.map { "$tag,${it.wid},${it.col},${it.row},${it.sx},${it.sy}" }, fire = false)
    }

    fun removeWidget(box: WidgetBox) {
        if (box.wid > 0) act.host.deleteAppWidgetId(box.wid)
        else Prefs.setList(context, Prefs.K_LWIDGETS, Prefs.list(context, Prefs.K_LWIDGETS).filter { it.substringBefore(":") != box.wid.toString() }, fire = false)
        removeView(box)
        widgets.remove(box)
        saveWidgets()
        requestLayout()
    }

    /** Leaves resize mode on every widget (tap on empty desktop). */
    fun endWidgetEdit(except: WidgetBox? = null) {
        for (w in widgets) if (w !== except) w.endEdit()
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
        // Phone: columns share the width evenly. Desktop: fixed-width columns on the left.
        gCw = if (Modes.tablet(context)) cw0 else maxOf(cw0, availW / gCols)
        gCh = ch
    }

    private fun posTag() = if (Modes.tablet(context)) "t" else "p"

    private fun storedPos(): HashMap<String, IntArray> {
        val m = HashMap<String, IntArray>()
        val tag = posTag()
        for (s in Prefs.list(context, Prefs.K_POS)) {
            if (s.length < 4 || s.substring(0, 2) != "$tag@") continue
            val i2 = s.lastIndexOf('@')
            val i1 = s.lastIndexOf('@', i2 - 1)
            if (i1 < 2) continue
            val col = s.substring(i1 + 1, i2).toIntOrNull() ?: continue
            val row = s.substring(i2 + 1).toIntOrNull() ?: continue
            m[s.substring(2, i1)] = intArrayOf(col, row)
        }
        return m
    }

    private fun savePos(updates: Map<String, IntArray>) {
        val tag = posTag()
        val keep = Prefs.list(context, Prefs.K_POS).filter { s ->
            if (s.length < 4 || s.substring(0, 2) != "$tag@") true else {
                val i2 = s.lastIndexOf('@'); val i1 = s.lastIndexOf('@', i2 - 1)
                !(i1 >= 2 && updates.containsKey(s.substring(2, i1)))
            }
        }
        Prefs.setList(context, Prefs.K_POS, keep + updates.map { "$tag@${it.key}@${it.value[0]}@${it.value[1]}" }, fire = false)
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
        for (b in widgets) b.measure(MeasureSpec.makeMeasureSpec(maxOf(1, b.sx) * gCw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxOf(1, b.sy) * gCh, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        computeGrid(r - l, b - t)
        val tablet = Modes.tablet(context)
        cells.clear()
        placeWidgets()
        val occ = HashSet<Int>()
        val stored = storedPos()
        // 0) folders keep their cells (1x1 up to 3x3); if the span no longer fits it falls back to 1x1
        for ((e, _) in items) {
            if (e !is Entry.FolderE) continue
            val p = stored[e.key] ?: run { e.sx = 1; e.sy = 1; null } ?: continue
            fun fits(sx: Int, sy: Int): Boolean {
                if (p[0] < 0 || p[1] < 0 || p[0] + sx > gCols || p[1] + sy > gRows) return false
                for (cc in p[0] until p[0] + sx) for (rr in p[1] until p[1] + sy) if (blockedByWidget(cc, rr) || (cc * 10000 + rr) in occ) return false
                return true
            }
            if (!fits(e.sx, e.sy)) { e.sx = 1; e.sy = 1 }
            if (fits(e.sx, e.sy)) {
                for (cc in p[0] until p[0] + e.sx) for (rr in p[1] until p[1] + e.sy) occ.add(cc * 10000 + rr)
                cells[e.key] = p
            }
        }
        // 1) icons the user placed freely
        for ((e, _) in items) {
            if (cells.containsKey(e.key)) continue
            val p = stored[e.key] ?: continue
            if (p[0] in 0 until gCols && p[1] in 0 until gRows && !blockedByWidget(p[0], p[1]) && occ.add(p[0] * 10000 + p[1])) cells[e.key] = p
        }
        // 2) everything else flows automatically: desktop = columns (top to bottom), phone = rows (left to right)
        var i = 0
        for ((e, _) in items) {
            if (cells.containsKey(e.key)) continue
            var cell: IntArray? = null
            while (i < gCols * gRows && cell == null) {
                val k = i++
                val col = if (tablet) k / gRows else k % gCols
                val row = if (tablet) k % gRows else k / gCols
                if (!blockedByWidget(col, row) && occ.add(col * 10000 + row)) cell = intArrayOf(col, row)
            }
            cells[e.key] = cell ?: intArrayOf(0, 0)
        }
        for ((e, v) in items) {
            val c = cells[e.key] ?: continue
            val left = paddingLeft + d(8) + c[0] * gCw
            val top = paddingTop + c[1] * gCh
            val fx = if (e is Entry.FolderE) e.sx else 1
            val fy = if (e is Entry.FolderE) e.sy else 1
            v.layout(left, top, left + gCw * fx, top + gCh * fy)
        }
        folderPanel?.layout(0, 0, r - l, b - t)
        for (box in widgets) {
            val left = originX() + box.col * gCw
            val top = originY() + box.row * gCh
            box.layout(left, top, left + box.sx * gCw, top + box.sy * gCh)
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
            val last = try { TrashStore.list(c).sortedByDescending { it.expiry }.take(3) } catch (_: Exception) { emptyList() }
            val sig = last.joinToString("|") { it.file.absolutePath }
            if (sig == trashSig && trashBmp != null) return@Thread
            val bmp = if (last.isEmpty()) null else try { composeTrash(last) } catch (_: Throwable) { null }
            post {
                trashSig = sig
                trashBmp = bmp
                if (trashIv === iv) iv.setImageDrawable(if (bmp != null) trashDrawable(bmp) else base)
            }
        }.start()
    }

    private fun trashDrawable(fg: android.graphics.Bitmap): android.graphics.drawable.Drawable =
        android.graphics.drawable.AdaptiveIconDrawable(
            android.graphics.drawable.ColorDrawable(context.getColor(resources.getIdentifier("trash_bg", "color", context.packageName))),
            android.graphics.drawable.BitmapDrawable(resources, fg)
        )

    /**
     * Foreground of the trash icon: the bin picture, the last trashed items tossed in at odd angles,
     * then the front glass of the bin drawn over them (semi transparent) so they look like they are inside.
     */
    private fun composeTrash(last: List<TrashStore.Item>): android.graphics.Bitmap {
        val bin = android.graphics.BitmapFactory.decodeResource(resources, resources.getIdentifier("ic_trash_fg", "drawable", context.packageName))
        val n = bin.width // 432
        val k = n / 432f
        val out = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        cv.drawBitmap(bin, 0f, 0f, null)
        val order = last.reversed() // newest drawn last = on top
        // Items lie piled on the floor of the bin, each one differently turned (the first one upside down).
        val spots = when (order.size) {
            1 -> listOf(floatArrayOf(212f, 262f, -14f))
            2 -> listOf(floatArrayOf(186f, 272f, 168f), floatArrayOf(246f, 252f, -22f))
            else -> listOf(floatArrayOf(178f, 274f, 168f), floatArrayOf(252f, 266f, -38f), floatArrayOf(212f, 238f, 14f))
        }
        val ts = (84 * k).toInt()
        val border = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val shadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
        val inside = android.graphics.Path().apply {
            // the bin's inner outline, a little inset so nothing pokes out of the glass
            moveTo(110f * k, 126f * k); lineTo(200f * k, 94f * k); lineTo(323f * k, 133f * k)
            lineTo(298f * k, 316f * k); lineTo(232f * k, 340f * k); lineTo(129f * k, 303f * k); close()
        }
        cv.save()
        cv.clipPath(inside)
        for ((i, it) in order.withIndex()) {
            val t = Thumbs.tile(it.file, it.name, ts)
            val sp = spots[i]
            val cx = sp[0] * k; val cy = sp[1] * k
            cv.save()
            cv.rotate(sp[2], cx, cy)
            val b = ts * 0.05f
            val h = ts / 2f + b
            cv.drawRoundRect(cx - h + 4 * k, cy - h + 6 * k, cx + h + 4 * k, cy + h + 6 * k, ts * 0.18f, ts * 0.18f, shadow)
            cv.drawRoundRect(cx - h, cy - h, cx + h, cy + h, ts * 0.18f, ts * 0.18f, border)
            cv.drawBitmap(t, cx - ts / 2f, cy - ts / 2f, null)
            cv.restore()
        }
        cv.restore()
        // front glass: the bin's front panels again, see-through, over the items
        val front = android.graphics.Path().apply {
            moveTo(100f * k, 118f * k); lineTo(228f * k, 160f * k); lineTo(332f * k, 128f * k)
            lineTo(304f * k, 322f * k); lineTo(232f * k, 349f * k); lineTo(122f * k, 308f * k); close()
        }
        cv.save()
        cv.clipPath(front)
        val glass = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        glass.alpha = 105
        cv.drawBitmap(bin, 0f, 0f, glass)
        cv.restore()
        return out
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
            if (folderRectFree(e, col, row, e.sx, e.sy)) savePos(mapOf(e.key to intArrayOf(col, row)))
            requestLayout(); return
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
        savePos(mapOf(e.key to intArrayOf(col, row)))
        requestLayout()
    }

    private fun createFolder(target: Entry, dragged: Entry) {
        val c = context
        val id = System.currentTimeMillis().toString(36)
        val list = FolderStore.load(c)
        list += FolderData(id, "Folder", 1, 1, mutableListOf(target.key, dragged.key))
        FolderStore.save(c, list)
        cells[target.key]?.let { savePos(mapOf("folder:$id" to intArrayOf(it[0], it[1]))) }
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
        card.background = Colors.rounded(Colors.surface(c), d(32).toFloat())
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

        val rowH = d(112)
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
                val iv = android.widget.ImageView(c)
                val label: String
                if (en is Entry.AppE) { iv.setImageDrawable(Icons.app(c, en.info.comp, false)); label = en.info.label }
                else if (en is Entry.FileE) { Thumbs.bind(iv, en.file, d(56)); label = en.file.name } else label = ""
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
            PopupMenuX(c, this).show(loc[0] + v.width / 2, loc[1] + v.height / 2, listOf(
                MenuItem("Open") { closeFolder(); open(en) },
                MenuItem("Remove from folder") { removeFromFolder(f, en) }
            ))
            true
        }
        val rows = ((f.resolved.size + 2) / 3).coerceIn(1, 3)
        card.addView(grid, LinearLayout.LayoutParams(MATCH, rows * rowH))

        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        val cw = minOf(d(340), w - d(32))
        ov.addView(card, FrameLayout.LayoutParams(cw, WRAP, Gravity.CENTER))
        addView(ov, LayoutParams(MATCH, MATCH))
        folderPanel = ov
        requestLayout()
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
        private var sSx = 1
        private var sSy = 1
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private val longPress = Runnable {
            if (state == 1) {
                state = 2
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
                        fResize = true; x0 = ev.rawX; y0 = ev.rawY; sSx = e.sx; sSy = e.sy
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
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
                        if (mouse) { state = 2; v.elevation = d(8).toFloat(); v.bringToFront() }
                        else { v.removeCallbacks(longPress); state = 0 }
                    }
                    if (state == 2) {
                        if (far) moved = true
                        v.translationX = dx; v.translationY = dy
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
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
                label = e.file.name
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

    private fun blockedByWidget(col: Int, row: Int): Boolean =
        widgets.any { o -> o.col >= 0 && col >= o.col && col < o.col + o.sx && row >= o.row && row < o.row + o.sy }

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
            PopupMenuX(c, this).show(x, y, listOf(
                MenuItem("Open") { showFolder(e.id) },
                MenuItem("Rename") { renameFolder(e) },
                MenuItem("Ungroup") { ungroup(e) }
            ))
            return
        }
        selectedKey = e.key
        refreshSelection()
        val m = PopupMenuX(c, this)
        when (e) {
            is Entry.AppE -> m.show(x, y, listOf(
                MenuItem("Open") { open(e) },
                MenuItem("App Info") { Launch.info(c, e.info.pkg) },
                MenuItem("Share") { Launch.shareApp(c, e.info.pkg) },
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
        box.background = Colors.rounded(Colors.surfaceSolid(act), d(28).toFloat())
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
    private val handlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Colors.accent(ctx); style = android.graphics.Paint.Style.FILL }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
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

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (resizeMode) {
            val inset = context.dp(2).toFloat()
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, context.dp(12).toFloat(), context.dp(12).toFloat(), edgePaint)
            val r = context.dp(6).toFloat()
            val pts = arrayOf(floatArrayOf(inset, inset), floatArrayOf(width - inset, inset), floatArrayOf(inset, height - inset), floatArrayOf(width - inset, height - inset))
            for (pt in pts) canvas.drawCircle(pt[0], pt[1], r, handlePaint)
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
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            startRawX = ev.rawX; startRawY = ev.rawY
            rememberStart()
            if ((ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                owner.widgetMenu(this, ev.rawX.toInt(), ev.rawY.toInt()); return true
            }
            val handle = context.dp(28)
            val left = ev.x <= handle
            val right = ev.x >= width - handle
            val top = ev.y <= handle
            val bottom = ev.y >= height - handle
            resizing = resizeMode && (left || right) && (top || bottom)
            if (resizing) {
                resizeCorner = when {
                    left && top -> 0
                    right && top -> 1
                    left && bottom -> 2
                    else -> 3
                }
                moving = true; moved = false; return true
            }
        }
        gd.onTouchEvent(ev)
        // While the frame is shown the whole widget is a drag handle (the widget itself is not clickable).
        return moving || resizeMode
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
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
