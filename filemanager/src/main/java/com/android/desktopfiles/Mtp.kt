package com.android.desktopfiles

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.os.ParcelFileDescriptor
import java.io.File

/** A phone connected by USB cable in "File transfer" (MTP) mode. */
object Mtp {
    private const val PERM = "com.android.desktopfiles.USB_PERMISSION"
    const val ROOT = -1   // "children of the storage itself"

    class Item(val handle: Int, val name: String, val dir: Boolean, val size: Long, val mtime: Long, val storage: Boolean = false)

    private val open = HashMap<String, MtpDevice>()

    private fun isMtp(d: UsbDevice): Boolean {
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (f.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) return true
            if (f.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && (f.name ?: "").contains("MTP", true)) return true
        }
        return false
    }

    fun list(c: Context): List<UsbDevice> = try {
        c.getSystemService(UsbManager::class.java).deviceList.values.filter { isMtp(it) }
    } catch (_: Exception) { emptyList() }

    fun find(c: Context, name: String): UsbDevice? = list(c).firstOrNull { it.deviceName == name }

    fun label(d: UsbDevice): String = try { d.productName ?: d.manufacturerName ?: "Phone" } catch (_: Exception) { "Phone" }

    /** Asks for the USB permission when needed, opens the device, and tells the result on the main thread. */
    fun connect(c: Context, d: UsbDevice, done: (MtpDevice?, String) -> Unit) {
        val um = c.getSystemService(UsbManager::class.java)
        synchronized(open) { open[d.deviceName]?.let { done(it, ""); return } }
        fun openNow() {
            Thread {
                var err = ""
                var m: MtpDevice? = null
                try {
                    val conn = um.openDevice(d)
                    if (conn == null) err = "Could not open the phone"
                    else { val x = MtpDevice(d); if (x.open(conn)) m = x else err = "The phone did not answer. Set its USB mode to File transfer." }
                } catch (e: Exception) { err = e.message ?: "Error" }
                if (m != null) synchronized(open) { open[d.deviceName] = m }
                c.mainExecutor.execute { done(m, err) }
            }.start()
        }
        if (um.hasPermission(d)) { openNow(); return }
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                try { c.applicationContext.unregisterReceiver(this) } catch (_: Exception) {}
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openNow() else done(null, "USB permission was not given")
            }
        }
        c.applicationContext.registerReceiver(rcv, IntentFilter(PERM), Context.RECEIVER_NOT_EXPORTED)
        val pi = PendingIntent.getBroadcast(c, 0, Intent(PERM).setPackage(c.packageName), PendingIntent.FLAG_MUTABLE)
        um.requestPermission(d, pi)
    }

    /** One-argument form: null when the phone could not be opened. */
    fun connect(c: Context, d: UsbDevice, done: (MtpDevice?) -> Unit) = connect(c, d) { m, _ -> done(m) }

    /** The phone that is open already (null when it was not connected yet). */
    fun device(name: String): MtpDevice? = synchronized(open) { open[name] }

    /** Handle of the folder that holds [handle]; [ROOT] when it is at the top of the storage. Worker thread. */
    fun parentOf(m: MtpDevice, handle: Int): Int? = synchronized(m) {
        val p = m.getObjectInfo(handle)?.parent ?: return null
        if (p == 0) ROOT else p
    }

    fun forget(d: UsbDevice) { synchronized(open) { open.remove(d.deviceName)?.close() } }

    // ---- everything below runs on a worker thread; one call at a time per device ----

    fun storages(m: MtpDevice): List<Item> = synchronized(m) {
        (m.storageIds ?: IntArray(0)).map { id ->
            val si = m.getStorageInfo(id)
            Item(id, si?.description?.takeIf { it.isNotBlank() } ?: "Storage", true, si?.maxCapacity ?: 0L, 0L, true)
        }
    }

    fun children(m: MtpDevice, storage: Int, parent: Int): List<Item> = synchronized(m) {
        val hs = m.getObjectHandles(storage, 0, parent) ?: return emptyList()
        hs.toList().mapNotNull { h ->
            val i = m.getObjectInfo(h) ?: return@mapNotNull null
            Item(h, i.name ?: "?", i.format == MtpConstants.FORMAT_ASSOCIATION, i.compressedSizeLong, i.dateModified)
        }
    }

    /** Saves one item (a folder with everything in it) from the phone to [dest]. Counts the bytes in [job]. */
    fun download(m: MtpDevice, storage: Int, it: Item, dest: File, job: Job): Boolean {
        if (job.cancelled) return false
        job.detail = it.name
        val target = Fs.unique(dest, it.name)
        if (it.dir) {
            target.mkdirs()
            var ok = true
            for (k in children(m, storage, it.handle)) ok = download(m, storage, k, target, job) && ok
            return ok
        }
        val ok = synchronized(m) { m.importFile(it.handle, target.absolutePath) }
        job.done += it.size
        if (it.mtime > 0) target.setLastModified(it.mtime)
        return ok
    }

    fun sizeOf(m: MtpDevice, storage: Int, it: Item): Long =
        if (!it.dir) it.size else children(m, storage, it.handle).sumOf { sizeOf(m, storage, it) }

    fun newFolder(m: MtpDevice, storage: Int, parent: Int, name: String): Int = synchronized(m) {
        val info = MtpObjectInfo.Builder().setStorageId(storage).setParent(parent).setName(name).setFormat(MtpConstants.FORMAT_ASSOCIATION).build()
        m.sendObjectInfo(info)?.objectHandle ?: 0
    }

    /** Sends a file or folder from this device to the phone. */
    fun upload(m: MtpDevice, storage: Int, parent: Int, f: File, job: Job): Boolean {
        if (job.cancelled) return false
        job.detail = f.name
        if (f.isDirectory) {
            val h = newFolder(m, storage, parent, f.name)
            if (h == 0) return false
            var ok = true
            for (k in f.listFiles() ?: emptyArray()) ok = upload(m, storage, h, k, job) && ok
            return ok
        }
        val ok = synchronized(m) {
            val info = MtpObjectInfo.Builder().setStorageId(storage).setParent(parent).setName(f.name)
                .setFormat(MtpConstants.FORMAT_UNDEFINED).setCompressedSize(f.length()).build()
            val r = m.sendObjectInfo(info)
            if (r == null) false else ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { m.sendObject(r.objectHandle, f.length(), it) }
        }
        job.done += f.length()
        return ok
    }

    fun delete(m: MtpDevice, h: Int): Boolean = synchronized(m) { m.deleteObject(h) }
}
