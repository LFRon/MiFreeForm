package com.buildsession.betterYAMF.window.core

enum class ShellMode { NORMAL, MINI }

enum class RegionId {
    BODY_MASK,
    HANDLE,
    BAR_BOTTOM,
    BAR_SIDE,
    RESIZE_HANDLE,
    CONTENT
}

enum class DropKind { CORNER_TL, CORNER_TR, CORNER_BL, CORNER_BR, SIDE_LEFT, SIDE_RIGHT }

enum class CloseOrigin { DOUBLE_TAP_BAR, DRAG_UP_BAR }

enum class FullscreenOrigin { DRAG_DOWN_BAR }

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

sealed interface WindowInputEvent {
    val windowId: Int
    val timeMs: Long

    sealed interface Pointer : WindowInputEvent {
        val region: RegionId
        val screenX: Float
        val screenY: Float
    }

    data class Press(
        override val windowId: Int,
        override val timeMs: Long,
        override val region: RegionId,
        override val screenX: Float,
        override val screenY: Float
    ) : Pointer

    data class Move(
        override val windowId: Int,
        override val timeMs: Long,
        override val region: RegionId,
        override val screenX: Float,
        override val screenY: Float
    ) : Pointer

    data class Up(
        override val windowId: Int,
        override val timeMs: Long,
        override val region: RegionId,
        override val screenX: Float,
        override val screenY: Float
    ) : Pointer

    data class Cancel(
        override val windowId: Int,
        override val timeMs: Long,
        override val region: RegionId,
        override val screenX: Float,
        override val screenY: Float
    ) : Pointer

    data class TapConfirmed(
        override val windowId: Int,
        override val timeMs: Long,
        override val region: RegionId,
        override val screenX: Float,
        override val screenY: Float
    ) : Pointer

    /** 外部请求（宿主内部触发，如"打开即 mini"）：进入角落 mini 态，等价于在右上角投放。 */
    data class RequestMini(
        override val windowId: Int,
        override val timeMs: Long
    ) : WindowInputEvent

    data class Fling(
        override val windowId: Int,
        override val timeMs: Long,
        val velocityX: Float,
        val velocityY: Float
    ) : WindowInputEvent

    /** 命令结果回灌（闭环不变量 2）：异步命令的 Done / Failed / Superseded。 */
    data class CommandResult(
        override val windowId: Int,
        override val timeMs: Long,
        val commandId: Long,
        val status: CommandStatus
    ) : WindowInputEvent

    /** 屏幕指标变化（旋转/尺寸变化；宿主采集后以事件入核）。 */
    data class ScreenMetricsChanged(
        override val windowId: Int,
        override val timeMs: Long,
        val width: Int,
        val height: Int
    ) : WindowInputEvent
}

enum class CommandStatus { DONE, FAILED, SUPERSEDED }

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

data class ShellSnapshot(
    val windowId: Int,
    val mode: ShellMode,
    val dragging: Boolean,
    val resizing: Boolean
)

interface TopPort {
    fun isTop(windowId: Int): Boolean
    fun markTop(windowId: Int)
}

object AlwaysTopPort : TopPort {
    override fun isTop(windowId: Int): Boolean = true
    override fun markTop(windowId: Int) {}
}
