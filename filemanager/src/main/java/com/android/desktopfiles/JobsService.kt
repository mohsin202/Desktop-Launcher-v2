package com.android.desktopfiles

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Keeps copy / move / delete running when the window is closed, and shows one notification with the combined progress. */
class JobsService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!Jobs.active()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
            try { getSystemService(NotificationManager::class.java).notify(1, build()) } catch (_: Exception) {}
            h.postDelayed(this, 700)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun build(): Notification {
        val act = Jobs.list.filter { !it.finished }
        val pct = (Jobs.fraction() * 100).toInt()
        val title = if (act.size == 1) act[0].title else act.size.toString() + " operations running"
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        return (if (android.os.Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "jobs") else Notification.Builder(this).setPriority(Notification.PRIORITY_LOW))
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Desktop Files").setContentText(title)
            .setProgress(100, pct, act.all { it.total <= 0 }).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("jobs", "File operations", NotificationManager.IMPORTANCE_LOW))
        try { if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, build()) } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        h.removeCallbacks(tick); h.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() { h.removeCallbacks(tick); super.onDestroy() }
}
