package com.buildsession.betterYAMF.window.backend

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
    override fun openTask(taskId: Int): Boolean = false
    override fun releaseTask(taskId: Int): Boolean = false
    override fun applyGeometry(taskId: Int, physicalBounds: Rect) {}
    override fun reapplyGeometry(taskId: Int) {}
    override fun ensureTaskVisible(taskId: Int) {}
    override fun setTaskFocusable(taskId: Int, focusable: Boolean) {}
    override fun bringTaskToFront(taskId: Int): Boolean = false
    override fun isAlwaysOnTopActive(): Boolean = false
    override fun isTaskManaged(taskId: Int): Boolean = false
    override fun managedTaskIds(): IntArray = IntArray(0)
}
