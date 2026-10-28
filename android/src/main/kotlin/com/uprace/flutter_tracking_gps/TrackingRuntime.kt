package com.uprace.flutter_tracking_gps

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Process-wide holder of the [TrackingEngine]. The engine deliberately outlives
 * the Flutter engine and the plugin instance: a recording must not stop because
 * the UI engine was detached. Its scope is the process, so nothing here holds
 * an Activity or a plugin; only the application context is used.
 */
internal object TrackingRuntime {
    private val lock = Any()
    private var engine: TrackingEngine? = null

    fun engine(context: Context): TrackingEngine = synchronized(lock) {
        engine ?: createEngine(context.applicationContext).also { engine = it }
    }

    private fun createEngine(appContext: Context): TrackingEngine = TrackingEngine(
        source = FusedLocationSource(appContext),
        store = FileFixStore(File(appContext.filesDir, STORE_DIRECTORY)),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private const val STORE_DIRECTORY = "flutter_tracking_gps"
}
