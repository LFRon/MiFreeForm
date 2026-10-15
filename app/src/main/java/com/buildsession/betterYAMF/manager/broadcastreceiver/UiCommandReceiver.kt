package com.buildsession.betterYAMF.manager.broadcastreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.manager.applist.AppListWindow

/**
 * 应用侧 UI 命令接收器（B9：模块不再直接驱动 app 组件）。
 *
 * 旧广播入口 `ACTION_OPEN_APP_LIST` 的接收与决策归 app 侧：收到后拉起应用列表浮层
 * （服务保持非导出）。对外广播 API 行为不变。
 */
class UiCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Protocol.ACTION_OPEN_APP_LIST -> {
                runCatching { context.startService(Intent(context, AppListWindow::class.java)) }
            }
        }
    }
}
