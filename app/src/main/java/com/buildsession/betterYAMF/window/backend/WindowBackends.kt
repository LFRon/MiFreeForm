package com.buildsession.betterYAMF.window.backend

import com.buildsession.betterYAMF.window.api.WindowOperation
import android.app.ActivityManager
import android.content.ComponentName
import android.graphics.Rect

/**
 * 后端端口提供者：安装/取用当前 [WindowBackend]。
 *
 * system_server 侧在 `YAMFManager.systemReady` 安装实现（早于一切窗口创建）；
 * 未安装时返回安全兜底（全部 no-op，不抛异常）：系统侧初始化异常不应扩大为
 * 系统进程崩溃或引导循环。
 */
object WindowBackends {
    @Volatile
    private var backend: WindowBackend? = null

    fun install(backend: WindowBackend) {
        this.backend = backend
    }

    fun current(): WindowBackend = backend ?: NoopWindowBackend
}

private object NoopWindowBackend : WindowBackend {
    override fun initialize(callbacks: WindowBackendCallbacks) {}
    override fun openTask(taskId: Int, initialBounds: Rect?, logicalBounds: Rect?): Boolean = false
    override fun releaseTask(taskId: Int, toFront: Boolean): Boolean = false
    override fun handoffFullscreen(
        taskId: Int, onReleased: () -> Unit,
        onComplete: (com.buildsession.betterYAMF.window.api.FullscreenHandoffResult) -> Unit
    ): WindowOperation {
        onComplete(com.buildsession.betterYAMF.window.api.FullscreenHandoffResult(
            com.buildsession.betterYAMF.window.api.CommandStatus.FAILED, false))
        return WindowOperation {}
    }
    override fun applyGeometry(taskId: Int, physicalBounds: Rect) {}
    override fun preparePresentation(taskId: Int, normalBounds: Rect, onComplete: (com.buildsession.betterYAMF.window.api.CommandStatus) -> Unit): WindowOperation {
        onComplete(com.buildsession.betterYAMF.window.api.CommandStatus.FAILED)
        return WindowOperation {}
    }
    override fun writePresentationInto(
        tx: android.view.SurfaceControl.Transaction, taskId: Int, physicalBounds: Rect,
        cornerRadiusPx: Float, alpha: Float,
        contentAlignment: com.buildsession.betterYAMF.window.api.ContentAlignment
    ): Boolean = false
    override fun writeGeometryInto(
        tx: android.view.SurfaceControl.Transaction,
        taskId: Int,
        physicalBounds: Rect
    ) {}
    override fun createShellContainer(taskId: Int, name: String): android.view.SurfaceControl? = null
    override fun raiseShellContainer(taskId: Int, container: android.view.SurfaceControl) {}
    override fun destroyShellContainer(container: android.view.SurfaceControl) {}
    override fun settleGeometry(taskId: Int, bounds: Rect) {}
    override fun reapplyGeometry(taskId: Int) {}
    override fun ensureTaskVisible(taskId: Int) {}
    override fun setTaskFocusable(taskId: Int, focusable: Boolean) {}
    override fun bringTaskToFront(taskId: Int): Boolean = false
    override fun isAlwaysOnTopActive(): Boolean = false
    override fun isTaskManaged(taskId: Int): Boolean = false
    override fun managedTaskIds(): IntArray = IntArray(0)
    override fun currentGeometry(taskId: Int): Rect? = null
    override fun forgetTask(taskId: Int) {}
    override fun requestSnapshotRefresh(taskId: Int) {}
    override fun windowingModeOf(taskInfo: ActivityManager.RunningTaskInfo): Int = 0
    override fun isHomeTask(taskInfo: android.app.TaskInfo): Boolean = false
    override fun isTouchActive(): Boolean = false
    override fun suppressRecentsGestureFor(durationMs: Long) {}
    override fun isTaskSurfaceStable(taskId: Int): Boolean = false
    override fun recentsGestureSuppressUntilMs(): Long = 0L
    override fun injectHomeKey(): Boolean = false

    override fun launchAppForSession(component: ComponentName, userId: Int, windowId: Int): Boolean = false

    override fun isTaskInSessionRoot(taskInfo: ActivityManager.RunningTaskInfo): Boolean = false
}
