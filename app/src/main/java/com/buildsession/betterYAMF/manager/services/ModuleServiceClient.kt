package com.buildsession.betterYAMF.manager.services

import android.app.Application
import android.app.BroadcastOptions
import android.content.Intent
import android.os.Build
import com.buildsession.betterYAMF.common.ipc.ServiceConnectionProtocol
import com.buildsession.betterYAMF.common.logging.AppLog

/** App transport for configuration/window RPCs. No activation or scope detection. */
internal object ModuleServiceClient {
    private lateinit var application: Application

    fun initialize(application: Application) {
        this.application = application
        requestConnection()
    }

    fun requestConnection() {
        if (!::application.isInitialized || Build.VERSION.SDK_INT < 34) return
        runCatching {
            application.sendBroadcast(Intent(ServiceConnectionProtocol.REQUEST).setPackage("android")
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY or Intent.FLAG_RECEIVER_FOREGROUND), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }.onFailure { AppLog.warn("reYAMF_AppService", "Cannot request the application service", it) }
    }
}
