package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.buildsession.betterYAMF.xposed.adapters.AndroidEnvironment
import com.buildsession.betterYAMF.xposed.adapters.LauncherRuntimeIdentity

/** Native lookup used only at service initialization / system events. Keeping it separate from
 * observer registration lets device checks validate these hidden APIs without installing hooks.
 */
internal class LauncherEnvironmentQuery(context: Context) {
    private val application = context.applicationContext
    private val pm = application.packageManager
    private val getHomeActivities = PackageManager::class.java
        .getMethod("getHomeActivities", List::class.java).apply { isAccessible = true }
    @SuppressLint("DiscouragedApi") // Same framework resource read by A17 LauncherProxyService.
    private val recentsResource = application.resources
        .getIdentifier("config_recentsComponentName", "string", "android")

    data class Snapshot(val runtime: LauncherRuntimeIdentity,
        val home: ComponentName?, val recents: ComponentName?)

    fun read(): Snapshot {
        // Returns the preferred component for the calling user. resolveActivity alone can
        // instead return ResolverActivity when the user has not selected a default Home.
        val home = getHomeActivities.invoke(pm, ArrayList<ResolveInfo>()) as? ComponentName
        val recents = if (recentsResource == 0) null else
            ComponentName.unflattenFromString(application.getString(recentsResource))
        return Snapshot(LauncherRuntimeIdentity(AndroidEnvironment.sdk, application.packageName,
            Application.getProcessName(), pm.getPackageInfo(application.packageName, 0).longVersionCode,
            home?.packageName, recents?.packageName), home, recents)
    }
}
