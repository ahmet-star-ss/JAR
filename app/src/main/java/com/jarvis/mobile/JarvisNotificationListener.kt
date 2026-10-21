package com.jarvis.mobile

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Gelen bildirimleri son 40 kayıtla sınırlı olarak tutar; get_notifications aracı okur. */
class JarvisNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var connected = false
        private val recent = ArrayList<String>()

        fun snapshot(limit: Int): List<String> = synchronized(recent) {
            recent.takeLast(limit).reversed()
        }
    }

    override fun onListenerConnected() {
        connected = true
        try {
            activeNotifications?.forEach { add(it) }
        } catch (e: Exception) {}
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) add(sbn)
    }

    private fun add(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val ex = sbn.notification.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        val line = "[$label] $title: $text".take(200)
        synchronized(recent) {
            if (recent.lastOrNull() != line) recent.add(line)
            while (recent.size > 40) recent.removeAt(0)
        }
    }
}
