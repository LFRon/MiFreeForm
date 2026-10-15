package com.buildsession.betterYAMF.xposed.services

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManagerHidden
import android.content.pm.PackageManagerHidden
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAs
import com.buildsession.betterYAMF.common.model.AppInfo
import com.buildsession.betterYAMF.common.runMain
import com.buildsession.betterYAMF.ipc.IAppIconCallback
import com.buildsession.betterYAMF.ipc.IAppListCallback
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.getActivityInfoCompat
import java.io.ByteArrayOutputStream

/**
 * 应用清单查询（system_server 侧实现）：AIDL 服务的方法体实现，由 [YAMFManager] 委托。
 * 与窗口流程解耦；数据经 IAppListCallback / IAppIconCallback 回传 App 进程。
 */
object AppCatalog {

    fun getAppListAsync(callback: IAppListCallback) {
        runMain {
            var apps: List<ActivityInfo>
            val showApps: MutableList<AppInfo> = mutableListOf()
            val users = mutableMapOf<Int, String>()
            Instances.userManager.invokeMethodAs<List<UserInfo>>(
                "getUsers",
                args(true, true, true),
                argTypes(java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
            )!!
                .filter { it.isProfile || it.isPrimary }
                .forEach {
                    users[it.id] = it.name
                }

            users.forEach { usr ->
                apps = (Instances.packageManager as PackageManagerHidden).queryIntentActivitiesAsUser(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }, 0, usr.key
                ).map {
                    (Instances.iPackageManager as IPackageManagerHidden).getActivityInfoCompat(
                        ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                        0, usr.key
                    )
                }

                apps.forEach { activityInfo ->
                    showApps.add(
                        AppInfo(
                            activityInfo, usr.key, usr.value
                        )
                    )
                }
            }

            showApps.chunked(5).forEach { chunk ->
                callback.onAppListReceived(chunk.toMutableList())
            }

            callback.onAppListFinished()
        }
    }

    //Might be useful in the future
    fun getAppIcon(callback: IAppIconCallback, appInfo: AppInfo) {
        runMain {
            val drawable = appInfo.activityInfo.loadIcon(Instances.packageManager)

            val bitmap = when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                is AdaptiveIconDrawable -> {
                    val size = 108
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
                else -> {
                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
            }

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val byteArray = stream.toByteArray()
            callback.onResult(byteArray)
        }
    }
}
