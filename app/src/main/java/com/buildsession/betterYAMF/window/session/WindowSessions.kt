package com.buildsession.betterYAMF.window.session

import android.content.ComponentName
import android.graphics.Rect
import java.util.concurrent.ConcurrentHashMap

/**
 * 模块运行期会话表（仅 system_server 进程内使用）。
 *
 * 角色：流程（YAMFManager）/ 宿主（AppWindow）/ 实现（FreeformBackend 等）三方共享的
 * 会话数据"单一事实源"——替代原先散挂在 YAMFManager 上的全局表（分层归属清晰化）。
 * 控制流仍归各层，这里只存数据；写入者约定：
 * - [hostedTasks] / [taskActivities]：流程（开窗认领 / 释放 / 任务事件），键 = taskId；
 * - [chromeTouchHoles]：宿主（几何落地时为内容区打洞），键 = windowId。
 */
object WindowSessions {

    /** 正被本模块窗口承载的任务集合。 */
    val hostedTasks = ConcurrentHashMap.newKeySet<Int>()

    /** 受管任务 → 顶层 Activity（启动资产定位用）。 */
    val taskActivities = ConcurrentHashMap<Int, ComponentName>()

    /** 壳窗口 → 内容触摸洞（应用区域）。 */
    val chromeTouchHoles = ConcurrentHashMap<Int, Rect>()
}
