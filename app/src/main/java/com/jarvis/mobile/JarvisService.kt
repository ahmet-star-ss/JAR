package com.jarvis.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo

class JarvisService : Service() {
    companion object {
        private const val CHANNEL = "jarvis_live"
        private const val ACTION_STOP = "com.jarvis.mobile.STOP"

        fun start(c: Context) {
            c.startForegroundService(Intent(c, JarvisService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, JarvisService::class.java))
        }
    }

    private var live: GeminiLive? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNow()
        if (live == null) {
            val l = GeminiLive(applicationContext, Tools(applicationContext))
            l.onEnd = { stopSelf() }
            live = l
            l.start()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "JARVIS", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat), "Durdur", stop
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("JARVIS dinliyor")
            .setContentText("Açmak için dokun")
            .setContentIntent(open)
            .addAction(action)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        live?.stop()
        live = null
        super.onDestroy()
    }
}
