package com.buildsession.betterYAMF.xposed.utils

import com.buildsession.betterYAMF.common.logging.AppLog
import com.buildsession.betterYAMF.BuildConfig

// Keep logging independent of Utils.kt's Android context initialization and Xposed's INFO sink.
inline fun log(tag: String, message: () -> String) {
    if (BuildConfig.DEBUG) AppLog.debug(tag, message)
}

fun log(tag: String, message: String, error: Throwable) = AppLog.error(tag, message, error)

fun logWarning(tag: String, message: String, error: Throwable? = null) = AppLog.warn(tag, message, error)

fun logError(tag: String, message: String, error: Throwable? = null) = AppLog.error(tag, message, error)
