package com.buildsession.betterYAMF.window.host

import android.app.ActivityManager

/**
 * 窗口宿主端口：流程层（YAMFManager）与窗口宿主（AppWindow）之间的唯一接口。
 *
 * 流程只经本接口分发任务/可见性事件与生命周期操作，不感知宿主实现细节
 * （具体宿主类、视图结构、几何算法均不外泄）。
 */
interface WindowHost {

    val windowId: Int

    /** 当前承载的任务 id（流程在认领时写入；-1 = 尚未认领）。 */
    var currentTaskId: Int

    /** 是否由会话显示器承载（false = 容器路径）。供流程层判断"宿主任务离开会话屏"的陈旧态。 */
    val isSessionHosted: Boolean get() = false

    /** 「打开即 mini」：任务就绪后由宿主请求进入角落 mini。 */
    fun markOpenAsMini()

    /** organizer 路径：任务 leash 已就绪，按当前几何摆放。 */
    fun onOrganizerLeash()

    fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo)

    fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo)

    fun onTaskVisibilityChanged(visible: Boolean)

    fun onHomeTop()

    /** 几何诊断 dump（DUMP_GEOMETRY 广播）。 */
    fun dumpGeometry()

    /** 销毁宿主；[releaseTask]=true 先把任务释放回全屏，false 用于任务移交（静默拆除外壳）。 */
    fun onDestroy(releaseTask: Boolean)
}
