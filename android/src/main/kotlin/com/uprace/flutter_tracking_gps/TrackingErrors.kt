package com.uprace.flutter_tracking_gps

import android.util.Log

/** Error codes the Dart side can switch on. */
internal object TrackingErrorCode {
    const val NO_ACTIVITY = "NO_ACTIVITY"
    const val PERMISSION_REQUEST_FAILED = "PERMISSION_REQUEST_FAILED"
    const val PERMISSION_REQUEST_PENDING = "PERMISSION_REQUEST_PENDING"
    const val START_FAILED = "START_FAILED"
    const val PAUSE_FAILED = "PAUSE_FAILED"
    const val RESUME_FAILED = "RESUME_FAILED"
    const val STOP_FAILED = "STOP_FAILED"
    const val RESTORE_FAILED = "RESTORE_FAILED"
    const val CORE_REJECTED = "CORE_REJECTED"
    const val STORE_READ_FAILED = "STORE_READ_FAILED"
    const val STORE_CLEAR_FAILED = "STORE_CLEAR_FAILED"
    const val LOCATION_UPDATES_FAILED = "LOCATION_UPDATES_FAILED"
    const val STORE_WRITE_FAILED = "STORE_WRITE_FAILED"
    const val STEPS_FAILED = "STEPS_FAILED"
    const val JOURNAL_REPAIRED = "JOURNAL_REPAIRED"
    const val JOURNAL_CORRUPT = "JOURNAL_CORRUPT"
    const val ENGINE_CHANGED = "ENGINE_CHANGED"
    const val FOREGROUND_START_FAILED = "FOREGROUND_START_FAILED"
    const val STORE_LIST_FAILED = "STORE_LIST_FAILED"
}

private const val LOG_TAG = "FlutterTrackingGps"

/**
 * Turns any failure into the three-part [FlutterError] the channel carries:
 * a stable code, a short message, and the stack trace as details. Nothing is
 * swallowed: the failure is logged here as well.
 */
internal fun Throwable.toFlutterError(code: String, message: String): FlutterError {
    Log.e(LOG_TAG, "$code: $message", this)
    return FlutterError(
        code = code,
        message = "$message (${this::class.java.simpleName}: ${this.message ?: "no message"})",
        details = stackTraceToString(),
    )
}

internal fun flutterError(code: String, message: String, details: Any? = null): FlutterError {
    Log.e(LOG_TAG, "$code: $message")
    return FlutterError(code = code, message = message, details = details)
}
