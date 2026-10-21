package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import com.buildsession.betterYAMF.xposed.adapters.AdapterCatalog
import com.buildsession.betterYAMF.xposed.adapters.AdapterIdentity
import com.buildsession.betterYAMF.xposed.adapters.AndroidEnvironment
import com.buildsession.betterYAMF.xposed.adapters.LauncherRuntimeIdentity
import com.buildsession.betterYAMF.xposed.adapters.LauncherSelection
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning

/** Service-scoped, main-thread platform facts. Like OverviewComponentObserver, query preferred
 * Home initially and on protected system events, never in input/frame callbacks or on a timer.
 * LauncherProxyService binds QUICKSTEP_SERVICE using config_recentsComponentName, independently
 * of preferred Home. Keep both identities; installing GApps does not prove they are the same.
 */
internal class LauncherEnvironmentMonitor(context: Context, private val expected: AdapterIdentity,
    private val onSelectionChanged: (Boolean) -> Unit) {
    private val application = context.applicationContext
    private val query = LauncherEnvironmentQuery(application)
    private data class State(val runtime: LauncherRuntimeIdentity?, val home: String?,
        val recents: String?, val issue: String?)
    private var state: State? = null
    private var homeRegistered = false
    private var packagesRegistered = false
    var isCurrentLauncher: Boolean = false
        private set

    private val homeChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refresh() }
    }
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            val current = state?.runtime
            if (pkg == application.packageName || pkg == current?.defaultHomePackage ||
                pkg == current?.recentsPackage || intent.action == ACTION_OVERLAY_CHANGED && pkg == "android")
                refresh()
        }
    }

    fun start() {
        checkMainThread()
        check(!homeRegistered && !packagesRegistered) { "Launcher environment monitor already started" }
        try {
            application.registerReceiver(homeChanges, IntentFilter().apply {
                addAction(ACTION_PREFERRED_ACTIVITY_CHANGED)
                addAction(Intent.ACTION_USER_UNLOCKED)
            }, Context.RECEIVER_EXPORTED)
            homeRegistered = true
            application.registerReceiver(packageChanges, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(ACTION_OVERLAY_CHANGED)
                addDataScheme("package")
            }, Context.RECEIVER_EXPORTED)
            packagesRegistered = true
            refresh()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun refresh() {
        checkMainThread()
        if (!homeRegistered || !packagesRegistered) return
        var failure: Throwable? = null
        val next = try {
            val (runtime, home, recents) = query.read()
            val selection = AdapterCatalog.selectRuntimeLauncher(runtime, AndroidEnvironment.identity)
            val issue = when (selection) {
                is LauncherSelection.Unavailable -> selection.reason
                is LauncherSelection.Selected -> if (selection.adapter.identity == expected) null else
                    "runtime identity does not match installed adapter ${expected.id}"
            }
            State(runtime, home?.flattenToShortString(), recents?.flattenToShortString(), issue)
        } catch (error: Throwable) {
            failure = error
            State(null, null, null, "Launcher environment query failed: ${error.cause ?: error}")
        }
        if (next == state) return
        state = next
        isCurrentLauncher = next.issue == null
        // Revoke the old entry before any subsequent event reaches its cached native binding.
        onSelectionChanged(isCurrentLauncher)
        if (next.issue != null) logWarning(TAG, "Launcher entry inactive: ${describe(next)}; ${next.issue}", failure)
        else log(TAG) { "Launcher environment active: ${describe(next)}" }
    }

    private fun describe(state: State) = "sdk=${AndroidEnvironment.sdk} user=${android.os.Process.myUserHandle()} host=${application.packageName} process=${Application.getProcessName()} apk=${state.runtime?.launcherVersionCode} home=${state.home} recents=${state.recents} adapter=${expected.id}"

    fun close() {
        checkMainThread()
        isCurrentLauncher = false
        state = null
        if (homeRegistered) {
            homeRegistered = false
            runCatching { application.unregisterReceiver(homeChanges) }
                .onFailure { logWarning(TAG, "Home listener cleanup failed", it) }
        }
        if (packagesRegistered) {
            packagesRegistered = false
            runCatching { application.unregisterReceiver(packageChanges) }
                .onFailure { logWarning(TAG, "Launcher package listener cleanup failed", it) }
        }
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private companion object {
        const val TAG = "reYAMF_Adapters"
        const val ACTION_PREFERRED_ACTIVITY_CHANGED = "android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED"
        const val ACTION_OVERLAY_CHANGED = "android.intent.action.OVERLAY_CHANGED"
    }
}
