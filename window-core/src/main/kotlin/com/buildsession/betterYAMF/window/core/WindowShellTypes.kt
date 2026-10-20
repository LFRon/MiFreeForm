package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

enum class ShellMode { NORMAL, MINI }
enum class CloseOrigin { DOUBLE_TAP_BAR, DRAG_UP_BAR }
enum class FullscreenOrigin { DRAG_DOWN_BAR }

data class ShellSnapshot(
    val windowId: Int, val mode: ShellMode, val dragging: Boolean, val resizing: Boolean
)

data class WindowRegistration(
    val windowId: Int,
    val density: Float
)

data class CoreConfig(
    val verticalDragThresholdDp: Float = 80f,
    val doubleTapTimeoutMs: Long = 300L,
    /** 栏点按判定位移上限（dp）：Press→Up 位移在此内视为一次"点按"（双击关闭用）。 */
    val barTapSlopDp: Float = 8f
)

sealed interface WindowCommand {
    val windowId: Int

    data class MoveBy(override val windowId: Int, val dx: Float, val dy: Float) : WindowCommand
    data class FlingBy(override val windowId: Int, val velocityX: Float, val velocityY: Float) : WindowCommand
    data class ClampToScreen(override val windowId: Int, val animated: Boolean) : WindowCommand
    data class DropZoneShow(override val windowId: Int) : WindowCommand
    data class DropHover(override val windowId: Int, val hover: DropKind?, val active: Boolean) : WindowCommand
    data class DropZoneHide(override val windowId: Int) : WindowCommand
    data class VerticalDragPreview(
        override val windowId: Int,
        val progress: Float,
        val downward: Boolean,
        val showFullscreenHint: Boolean
    ) : WindowCommand
    data class ClearVerticalDragPreview(override val windowId: Int) : WindowCommand
    data class ResizePreviewStart(override val windowId: Int) : WindowCommand
    data class ResizePreviewUpdate(override val windowId: Int, val dx: Float, val dy: Float) : WindowCommand
    data class ResizePreviewCommit(override val windowId: Int) : WindowCommand
    data class EnterMini(
        override val windowId: Int,
        val corner: DropKind = DropKind.CORNER_TR,
        /** 命令 id（>0 时宿主执行完毕回灌 CommandResult；0 = 不追踪）。 */
        val cmdId: Long = 0L
    ) : WindowCommand

    data class ExitMini(override val windowId: Int, val cmdId: Long = 0L) : WindowCommand
    data class RequestClose(override val windowId: Int, val origin: CloseOrigin) : WindowCommand
    data class RequestFullscreen(override val windowId: Int, val origin: FullscreenOrigin) : WindowCommand
    data class RaiseWindow(override val windowId: Int) : WindowCommand
}

interface TopPort {
    fun isTop(windowId: Int): Boolean
    fun markTop(windowId: Int)
}

object AlwaysTopPort : TopPort {
    override fun isTop(windowId: Int): Boolean = true
    override fun markTop(windowId: Int) {}
}
