package io.github.congtuandevmobile.flutter_tracking_gps

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
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The default way the SDK keeps the process alive while a session records: a
 * foreground service of type `location` with a plain ongoing notification. The
 * title and text come from Flutter (`notificationTitle` / `notificationText`);
 * tapping it returns to the app. It owns no tracking logic and shows no live
 * data: the [TrackingEngine] does the work.
 *
 * The SDK ALWAYS runs it while a session records, so the recording never depends on a
 * third party to keep the process alive. The SDK also OWNS the notification (channel,
 * tap action, lifecycle, minimal content): a presenter changes its text through the
 * SDK ([update], exposed to Dart as `updateNotification`) and never posts with the
 * SDK's id itself. If the presenter fails or its engine dies, this one stays.
 */
class TrackingService : Service() {
    private var title = DEFAULT_TITLE
    private var text = DEFAULT_TEXT

    // Only the recovery of a session whose process died lives here; the engine itself is process-wide.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recovery: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Replaces the text of the foreground notification (the channel, tap action and lifecycle stay the SDK's). */
    private fun show(newTitle: String, newText: String) {
        title = newTitle
        text = newText
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        title = intent?.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE
        text = intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT
        // Must run within seconds of startForegroundService(). The plugin waits for the acknowledgement.
        try {
            startForegroundCompat(buildNotification(title, text))
        } catch (e: RuntimeException) {
            // Not in the foreground: nothing may continue (no recovery, no sticky restart that would retry silently).
            handshake.fail(e)
            stopSelf()
            return START_NOT_STICKY
        }
        // Only a start the plugin is waiting for is acknowledged; a sticky restart does not answer another command.
        if (intent?.action == ACTION_START) handshake.acknowledge()
        // After the OS killed the process the system recreates this service (START_STICKY) with a NULL intent: that is
        // the moment to bring the recording back, without Flutter. A normal start carries ACTION_START: the plugin
        // starts the engine itself.
        if (intent == null || intent.action == ACTION_RECOVER) launchRecovery(startId)
        return START_STICKY
    }

    /**
     * Process-death recovery: finds the open session and continues it (see `TrackingEngine.recoverActive`). When
     * there is none, or it is not recording, the service stops, but only if no newer start arrived meanwhile
     * (`stopSelf(startId)`), and a cancelled recovery is never reported as a failure.
     */
    private fun launchRecovery(startId: Int) {
        if (recovery?.isActive == true) return
        recovery = scope.launch {
            val engine = TrackingRuntime.engine(applicationContext)
            try {
                val snapshot = engine.recoverActive()
                if (snapshot == null || snapshot.state != NativeTrackingState.RUNNING) {
                    stopSelf(startId)
                    return@launch
                }
                val extras = engine.sessionExtras()
                show(extras["title"] ?: DEFAULT_TITLE, extras["text"] ?: DEFAULT_TEXT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("FlutterTrackingGps", "recovery after a process death failed", e)
                stopSelf(startId)
            }
        }
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
        @Volatile private var instance: TrackingService? = null

        /** Signals that the service is in the foreground (see [ForegroundHandshake]). */
        internal val handshake = ForegroundHandshake()

        /** Changes the text of the running notification; a no-op when no session records. */
        fun update(title: String, text: String) {
            instance?.show(title, text)
        }

        const val ACTION_START = "io.github.congtuandevmobile.flutter_tracking_gps.START"
        const val ACTION_STOP = "io.github.congtuandevmobile.flutter_tracking_gps.STOP"
        const val ACTION_RECOVER = "io.github.congtuandevmobile.flutter_tracking_gps.RECOVER"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "flutter_tracking_gps"
        private const val CHANNEL_NAME = "Activity recording"
        private const val NOTIFICATION_ID = 7421
        private const val DEFAULT_TITLE = "Recording"
        private const val DEFAULT_TEXT = "Tracking your activity. Tap to return to the app."
    }
}
