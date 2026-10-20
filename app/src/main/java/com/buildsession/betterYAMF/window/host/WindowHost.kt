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
    val currentTaskId: Int

    /** Bind exactly once before invoking native task adoption. */
    fun bindTask(taskId: Int)

    /** 「打开即 mini」：任务就绪后由宿主请求进入角落 mini。 */
    fun markOpenAsMini(
        dropX: Int = -1, dropY: Int = -1,
        sourceBounds: android.graphics.Rect? = null, awaitSourceRelease: Boolean = false,
        sourcePackage: String? = null, handoffId: Long = 0L,
        entrySnapshot: com.buildsession.betterYAMF.common.model.EntrySnapshot? = null,
        launcherRotation: Int = -1, sourceEpoch: Long = 0L, sourceUserId: Int = 0
    )

    /** Adopt using the core's normal viewport; a staged source is displayed before this call. */
    fun openTask(): Boolean
    fun onEntryCancelled(handoffId: Long)

    /** The source has relinquished its visible card after our first frame was committed. */
    fun onSourceReleased(handoffId: Long)
    fun onPresentationInvalidated()
    fun onTaskOwnershipLost()

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
