package com.buildsession.betterYAMF.manager.sidebar

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.buildsession.betterYAMF.common.logging.AppLog

/**
 * 侧边栏服务生命周期编排（app 侧 host glue）：
 * UI 只表达 start/stop/restart/打开菜单，Intent/action/延时重启细节收口在此。
 */
object SidebarLauncher {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        if (!canPresent(context)) return
        context.startService(
            Intent(context, SidebarService::class.java).setAction(SidebarService.ACTION_START)
        )
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, SidebarService::class.java).setAction(SidebarService.ACTION_STOP)
        )
    }

    /** 打开侧边栏菜单（由侧边栏点按/滑动触发）。 */
    fun startMenu(context: Context) {
        if (!canPresent(context)) return
        context.startService(Intent(context, SidebarMenuService::class.java))
    }

    /** 重启侧边栏（配置变更后重读）：先停后启，[delayMs] 为停止到启动的宽限。 */
    fun restart(context: Context, delayMs: Long) {
        if (!canPresent(context)) return
        stop(context)
        mainHandler.postDelayed({ start(context) }, delayMs)
    }

    private fun canPresent(context: Context): Boolean {
        if (Settings.canDrawOverlays(context)) return true
        AppLog.warn("reYAMF_Sidebar", "Sidebar overlay permission unavailable")
        return false
    }
}
