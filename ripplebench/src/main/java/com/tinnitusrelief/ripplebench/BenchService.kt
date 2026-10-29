package com.tinnitusrelief.ripplebench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File

/** Runs the silent benchmark in a foreground service so it continues with the app in the background. */
class BenchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Silent benchmark", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Ripple bench (silent)")
            .setContentText("Running non-audible validation…")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        if (BenchState.running) return START_NOT_STICKY
        BenchState.running = true
        val quick = intent?.getBooleanExtra(EXTRA_QUICK, false) == true
        Thread {
            val wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ripplebench:run")
            wl.acquire(30 * 60_000L)
            val report = try {
                BenchRunner(this, lowLatencySeconds = if (quick) 20 else 120, mainSeconds = if (quick) 30 else 300).run()
            } catch (t: Throwable) {
                "FAILED with ${t.javaClass.simpleName}: ${t.message}"
            } finally {
                if (wl.isHeld) wl.release()
            }
            File(filesDir, "bench-results.txt").writeText(report)
            BenchState.lastReport = report
            BenchState.running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.apply { name = "ripple-bench"; start() }
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "bench"
        const val EXTRA_QUICK = "quick"
    }
}
