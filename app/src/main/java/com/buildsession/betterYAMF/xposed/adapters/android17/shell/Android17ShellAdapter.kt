package com.buildsession.betterYAMF.xposed.adapters.android17.shell

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.xposed.adapters.common.system.ShellOwnershipProtocol as Wire
import com.buildsession.betterYAMF.xposed.adapters.common.system.UserScopedBroadcasts
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookBefore
import com.buildsession.betterYAMF.xposed.utils.TRUSTED_SENDER_PERMISSION
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning

/** Shared A17 Shell mechanism, explicitly selected with the LoS 24 / PixelOS 17 system adapter.
 * Marker lookup is local. No task-id broadcast races, IPC or reflection discovery in frame paths.
 */
internal object Android17ShellAdapter {
    private var installed = false
    @Volatile private var cookie: IBinder? = null
    @Volatile private var failed = false
    private var failureReason: String? = null
    private val lifetime = Binder()
    private var application: Application? = null
    private var reportedRejection: String? = null

    fun install(loader: ClassLoader) {
        if (installed) return
        com.buildsession.betterYAMF.xposed.adapters.AndroidEnvironment.reportBaseline(loader)
        val native = Android17ShellBindings(loader)
        val hooks = mutableListOf<NativeHooks.Registration>()
        try {
            for (method in native.observers + native.animations) {
                val index = method.parameterTypes.indexOfFirst { it.name == "android.window.TransitionInfo" }
                check(index >= 0)
                hooks += method.hookBefore { call ->
                    runCatching {
                        call.args[index] = native.project(call.args[index], cookie) { task -> native.cleanup(call.thisObject, task) }
                    }.onFailure(::isolationFailed)
                }
            }
            for (model in native.decorations) for (method in model.updates) hooks += method.hookBefore { call ->
                runCatching {
                    if (native.owns(call.args[0], cookie)) {
                        // Use the real close path: disposes caption input channels and surfaces.
                        model.destroy.invoke(call.thisObject, call.args[0])
                        call.result = if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                    }
                }.onFailure(::isolationFailed)
            }
            native.desktopRequest?.let { method -> hooks += method.hookBefore { call ->
                runCatching { if (native.requestOwned(call.args[1], cookie)) call.result = null }
                    .onFailure(::isolationFailed)
            } }
            // PixelOS 17 moved the application class into application.impl. Do this before its
            // Dagger/Shell construction, rather than hooking the obsolete com.android.systemui class.
            hooks += native.applicationCreate.hookBefore { call ->
                runCatching { connect(call.thisObject as Application) }.onFailure(::isolationFailed)
            }
            installed = true
            log(TAG) { "Shell native hooks installed uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} build=${BuildConfig.BUILD_TIME}" }
        } catch (error: Throwable) {
            hooks.asReversed().forEach { runCatching { it.unhook() }
                .onFailure { logWarning(TAG, "Shell hook rollback failed", it) } }
            throw error
        }
    }

    private fun connect(app: Application) {
        if (application != null) return
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    if (!UserScopedBroadcasts.isSystemSender(this) || UserScopedBroadcasts.senderPackage(this) != "android")
                        return reject("owner sender is not system_server")
                    val version = intent.getIntExtra(Wire.VERSION, 0)
                    val build = intent.getLongExtra(Wire.BUILD, 0L)
                    if (version != Wire.CURRENT || build != BuildConfig.BUILD_TIME)
                        return reject("owner version/build mismatch: version=$version build=$build expectedBuild=${BuildConfig.BUILD_TIME}")
                    val marker = intent.extras?.getBinder(Wire.COOKIE) ?: return reject("missing owner cookie")
                    if (!marker.isBinderAlive) return reject("owner cookie is dead")
                    cookie = marker
                    com.buildsession.betterYAMF.xposed.adapters.android17.notification.NotificationWindowAdapter
                        .connect(intent.extras?.getBinder(Wire.NOTIFICATION_BRIDGE))
                    acknowledge()
                    reportedRejection = null
                    log(TAG) { "Shell ownership marker received build=${BuildConfig.BUILD_TIME} ready=${!failed}" }
                }.onFailure(::isolationFailed)
            }
        }, IntentFilter(Wire.OWNER), TRUSTED_SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED)
        application = app
        UserScopedBroadcasts.sendIdentifiedPrivileged(app, Intent(Wire.QUERY).setPackage("android")
            .putExtra(Wire.VERSION, Wire.CURRENT).putExtra(Wire.BUILD, BuildConfig.BUILD_TIME))
    }

    private fun acknowledge() {
        val app = application ?: return
        val marker = cookie ?: return
        UserScopedBroadcasts.sendIdentifiedPrivileged(app, Intent(Wire.READY).setPackage("android")
            .putExtra(Wire.VERSION, Wire.CURRENT).putExtra(Wire.BUILD, BuildConfig.BUILD_TIME)
            .putExtra(Wire.AVAILABLE, !failed)
            .putExtra(Wire.FAILURE, failureReason)
            .putExtra(Wire.PID, android.os.Process.myPid())
            .putExtras(Bundle().apply { putBinder(Wire.COOKIE, marker); putBinder(Wire.PEER, lifetime) }))
    }

    private fun reject(reason: String) {
        if (reportedRejection == reason) return
        reportedRejection = reason
        logWarning(TAG, "Shell owner announcement rejected: $reason")
    }

    private fun isolationFailed(error: Throwable) {
        if (failed) return
        failureReason = generateSequence(error) { it.cause }.take(6)
            .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }.take(2048)
        failed = true
        logWarning(TAG, "Shell isolation failed; withdraw backend entry capability", error)
        runCatching { acknowledge() }.onFailure { logWarning(TAG, "Shell failure notification failed", it) }
    }
    private const val TAG = "reYAMF_Shell"
}
