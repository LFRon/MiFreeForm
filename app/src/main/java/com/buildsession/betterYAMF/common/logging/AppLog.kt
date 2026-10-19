package com.buildsession.betterYAMF.common.logging

import android.util.Log
import com.buildsession.betterYAMF.BuildConfig

/** One severity policy for the app and injected processes. Release emits WARN/ERROR only. */
object AppLog {
    fun debug(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    inline fun debug(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    fun warn(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
    }
}
