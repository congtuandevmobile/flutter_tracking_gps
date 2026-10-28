package com.uprace.flutter_tracking_gps

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Flutter entry point. Thin on purpose: it validates, forwards to the
 * [TrackingEngine], and turns every failure into a [FlutterError]. Results are
 * delivered on the main thread; heavy work runs on `Dispatchers.IO` inside the
 * engine.
 */
class FlutterTrackingGpsPlugin :
    FlutterPlugin,
    ActivityAware,
    TrackingHostApi,
    PluginRegistry.RequestPermissionsResultListener {

    private var appContext: Context? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var scope: CoroutineScope? = null
    private var fixesJob: Job? = null
    private var snapshotsJob: Job? = null
    private var pendingPermission: ((Result<NativePermission>) -> Unit)? = null

    // ── FlutterPlugin ───────────────────────────────────────────────────────

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        TrackingHostApi.setUp(binding.binaryMessenger, this)
        StreamFixesStreamHandler.register(binding.binaryMessenger, FixesHandler())
        StreamSnapshotsStreamHandler.register(binding.binaryMessenger, SnapshotsHandler())
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        TrackingHostApi.setUp(binding.binaryMessenger, null)
        // The recording itself keeps going (see TrackingRuntime); only what this
        // plugin instance holds is released.
        fixesJob?.cancel()
        fixesJob = null
        snapshotsJob?.cancel()
        snapshotsJob = null
        scope?.cancel()
        scope = null
        pendingPermission = null
        appContext = null
    }

    // ── ActivityAware ───────────────────────────────────────────────────────

    override fun onAttachedToActivity(binding: ActivityPluginBinding) = attach(binding)
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = attach(binding)
    override fun onDetachedFromActivityForConfigChanges() = detach()
    override fun onDetachedFromActivity() = detach()

    private fun attach(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addRequestPermissionsResultListener(this)
    }

    private fun detach() {
        activityBinding?.removeRequestPermissionsResultListener(this)
        activityBinding = null
    }

    // ── Permission ──────────────────────────────────────────────────────────

    override fun permissionStatus(): NativePermission {
        val context = appContext ?: return NativePermission.DENIED
        return currentPermission(context)
    }

    override fun requestPermission(callback: (Result<NativePermission>) -> Unit) {
        val context = appContext
        val activity: Activity? = activityBinding?.activity
        if (context == null || activity == null) {
            callback(
                Result.failure(
                    flutterError(TrackingErrorCode.NO_ACTIVITY, "requesting a permission needs a foreground activity"),
                ),
            )
            return
        }
        if (currentPermission(context) == NativePermission.GRANTED) {
            callback(Result.success(NativePermission.GRANTED))
            return
        }
        if (pendingPermission != null) {
            callback(
                Result.failure(
                    flutterError(TrackingErrorCode.PERMISSION_REQUEST_PENDING, "a permission request is already open"),
                ),
            )
            return
        }
        pendingPermission = callback
        try {
            val wanted = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            ActivityCompat.requestPermissions(activity, wanted.toTypedArray(), REQUEST_CODE)
        } catch (e: RuntimeException) {
            pendingPermission = null
            callback(
                Result.failure(
                    e.toFlutterError(TrackingErrorCode.PERMISSION_REQUEST_FAILED, "could not ask for the permission"),
                ),
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val callback = pendingPermission ?: return true
        pendingPermission = null
        val context = appContext
        callback(Result.success(if (context == null) NativePermission.DENIED else currentPermission(context)))
        return true
    }

    private fun currentPermission(context: Context): NativePermission {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return NativePermission.DENIED
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val enabled = manager?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } ?: true
        return if (enabled) NativePermission.GRANTED else NativePermission.SERVICES_DISABLED
    }

    // ── Session commands ────────────────────────────────────────────────────

    override fun start(config: StartConfig, callback: (Result<NativeSnapshot>) -> Unit) {
        command(callback, TrackingErrorCode.START_FAILED, "could not start tracking") { context, engine ->
            if (currentPermission(context) != NativePermission.GRANTED) {
                engine.markPermissionDenied()
                return@command engine.snapshot()
            }
            startService(context, config)
            engine.start(config)
        }
    }

    override fun pause(sessionId: String, callback: (Result<NativeSnapshot>) -> Unit) {
        command(callback, TrackingErrorCode.PAUSE_FAILED, "could not pause tracking") { _, engine ->
            engine.pause(sessionId)
        }
    }

    override fun resume(sessionId: String, callback: (Result<NativeSnapshot>) -> Unit) {
        command(callback, TrackingErrorCode.RESUME_FAILED, "could not resume tracking") { _, engine ->
            engine.resume(sessionId)
        }
    }

    override fun stop(sessionId: String, callback: (Result<NativeSnapshot>) -> Unit) {
        command(callback, TrackingErrorCode.STOP_FAILED, "could not stop tracking") { context, engine ->
            val snapshot = engine.stop(sessionId)
            if (snapshot.state == NativeTrackingState.STOPPED) stopService(context)
            snapshot
        }
    }

    override fun getSnapshot(): NativeSnapshot {
        val context = appContext ?: return NativeSnapshot(
            state = NativeTrackingState.IDLE,
            segmentId = 0,
            fixCount = 0,
            updatedAtMs = System.currentTimeMillis(),
            distance = emptyDistance(),
        )
        return TrackingRuntime.engine(context).snapshot()
    }

    override fun submitPdr(sessionId: String, segmentId: Long, meters: Double) {
        val context = appContext ?: return
        TrackingRuntime.engine(context).submitPdr(sessionId, segmentId.toInt(), meters)
    }

    override fun restoreSession(config: StartConfig, callback: (Result<NativeSnapshot>) -> Unit) {
        command(callback, TrackingErrorCode.RESTORE_FAILED, "could not restore the session") { _, engine ->
            engine.restore(config)
        }
    }

    override fun getReport(): String {
        val context = appContext ?: return ""
        return TrackingRuntime.engine(context).report()
    }

    override fun readPersistedFixes(
        sessionId: String,
        afterSeq: Long,
        callback: (Result<List<NativeFix>>) -> Unit,
    ) {
        command(callback, TrackingErrorCode.STORE_READ_FAILED, "could not read the persisted fixes") { _, engine ->
            engine.readPersisted(sessionId, afterSeq)
        }
    }

    override fun clearSession(sessionId: String, callback: (Result<Unit>) -> Unit) {
        command(callback, TrackingErrorCode.STORE_CLEAR_FAILED, "could not clear the session") { _, engine ->
            engine.clearSession(sessionId)
        }
    }

    /**
     * Runs [block] on the plugin scope and delivers the outcome on the main
     * thread. A failure is turned into a [FlutterError]; cancellation is
     * rethrown, never reported as an error.
     */
    private fun <T> command(
        callback: (Result<T>) -> Unit,
        code: String,
        message: String,
        block: suspend (Context, TrackingEngine) -> T,
    ) {
        val context = appContext
        val activeScope = scope
        if (context == null || activeScope == null) {
            callback(Result.failure(flutterError(code, "$message: the plugin is detached")))
            return
        }
        activeScope.launch {
            try {
                callback(Result.success(block(context, TrackingRuntime.engine(context))))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                callback(Result.failure(e.toFlutterError(code, message)))
            }
        }
    }

    private fun startService(context: Context, config: StartConfig) {
        val intent = Intent(context, TrackingService::class.java)
            .setAction(TrackingService.ACTION_START)
            .putExtra(TrackingService.EXTRA_TITLE, config.notificationTitle)
            .putExtra(TrackingService.EXTRA_TEXT, config.notificationText)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopService(context: Context) {
        context.stopService(Intent(context, TrackingService::class.java))
    }

    // ── Event channel ───────────────────────────────────────────────────────

    /** Forwards the engine's fixes to Dart while a listener is attached. */
    private inner class FixesHandler : StreamFixesStreamHandler() {
        override fun onListen(p0: Any?, sink: PigeonEventSink<NativeFix>) {
            val context = appContext
            val activeScope = scope
            if (context == null || activeScope == null) {
                sink.error(TrackingErrorCode.START_FAILED, "the plugin is detached", null)
                return
            }
            fixesJob?.cancel()
            fixesJob = activeScope.launch {
                try {
                    TrackingRuntime.engine(context).fixes.collect { fix -> sink.success(fix) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val error = e.toFlutterError(TrackingErrorCode.LOCATION_UPDATES_FAILED, "fix stream failed")
                    sink.error(error.code, error.message, error.details)
                }
            }
        }

        override fun onCancel(p0: Any?) {
            fixesJob?.cancel()
            fixesJob = null
        }
    }

    /** Forwards the engine's snapshots (distance, quality, state) while Dart listens. */
    private inner class SnapshotsHandler : StreamSnapshotsStreamHandler() {
        override fun onListen(p0: Any?, sink: PigeonEventSink<NativeSnapshot>) {
            val context = appContext
            val activeScope = scope
            if (context == null || activeScope == null) {
                sink.error(TrackingErrorCode.START_FAILED, "the plugin is detached", null)
                return
            }
            snapshotsJob?.cancel()
            snapshotsJob = activeScope.launch {
                try {
                    TrackingRuntime.engine(context).snapshots.collect { snapshot -> sink.success(snapshot) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val error = e.toFlutterError(TrackingErrorCode.LOCATION_UPDATES_FAILED, "snapshot stream failed")
                    sink.error(error.code, error.message, error.details)
                }
            }
        }

        override fun onCancel(p0: Any?) {
            snapshotsJob?.cancel()
            snapshotsJob = null
        }
    }

    private companion object {
        const val REQUEST_CODE = 7421
    }
}
