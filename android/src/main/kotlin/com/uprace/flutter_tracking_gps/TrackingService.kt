package com.uprace.flutter_tracking_gps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * The default way the SDK keeps the process alive while a session records: a
 * foreground service of type `location` with a plain ongoing notification. The
 * title and text come from Flutter (`notificationTitle` / `notificationText`);
 * tapping it returns to the app. It owns no tracking logic and shows no live
 * data: the [TrackingEngine] does the work.
 *
 * It is NOT started when the app picked `NativeForegroundMode.EXTERNAL` (a
 * third-party plugin then owns a foreground service).
 */
class TrackingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT
        // Must run within seconds of startForegroundService().
        startForegroundCompat(buildNotification(title, text))
        // Not sticky: after a process kill there is no session to continue, and
        // a restarted service without one would only show a stale notification.
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
            )
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val tap = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val icon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_mylocation
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(tap)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.uprace.flutter_tracking_gps.START"
        const val ACTION_STOP = "com.uprace.flutter_tracking_gps.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "flutter_tracking_gps"
        private const val CHANNEL_NAME = "Activity recording"
        private const val NOTIFICATION_ID = 7421
        private const val DEFAULT_TITLE = "Recording"
        private const val DEFAULT_TEXT = "Tracking your activity. Tap to return to the app."
    }
}
