package com.buildsession.betterYAMF.window.core

enum class ShellMode { NORMAL, MINI, COLLAPSED }

enum class RegionId {
    BODY_MASK,
    HANDLE,
    BAR_BOTTOM,
    BAR_SIDE,
    RESIZE_HANDLE,
    CONTENT,
    BUTTON_CLOSE,
    BUTTON_FULLSCREEN,
    BUTTON_MINIMIZE,
    BUTTON_COLLAPSE,
    SUPER_CLOSE
}

enum class DropKind { CORNER_TL, CORNER_TR, CORNER_BL, CORNER_BR, SIDE_LEFT, SIDE_RIGHT }

enum class CloseOrigin { BUTTON_X, DOUBLE_TAP_BAR, DRAG_UP_BAR }

enum class FullscreenOrigin { BUTTON, DRAG_DOWN_BAR }

data class WindowRegistration(
    val windowId: Int,
    val density: Float
)

data class CoreConfig(
    val verticalDragThresholdDp: Float = 80f,
    val doubleTapTimeoutMs: Long = 300L
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

    data class Tap(
        override val windowId: Int,
        override val timeMs: Long,
        override val region: RegionId,
        override val screenX: Float = 0f,
        override val screenY: Float = 0f
    ) : Pointer

    data class Fling(
        override val windowId: Int,
        override val timeMs: Long,
        val velocityX: Float,
        val velocityY: Float
    ) : WindowInputEvent
}

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
    data class EnterMini(override val windowId: Int) : WindowCommand
    data class ExitMini(override val windowId: Int) : WindowCommand
    data class EnterCollapsed(override val windowId: Int) : WindowCommand
    data class ExitCollapsed(override val windowId: Int) : WindowCommand
    data class ShowSuperMenu(override val windowId: Int) : WindowCommand
    data class HideSuperMenu(override val windowId: Int) : WindowCommand
    data class RequestClose(override val windowId: Int, val origin: CloseOrigin) : WindowCommand
    data class RequestFullscreen(override val windowId: Int, val origin: FullscreenOrigin) : WindowCommand
    data class RaiseWindow(override val windowId: Int) : WindowCommand
}

data class ShellSnapshot(
    val windowId: Int,
    val mode: ShellMode,
    val superMenuVisible: Boolean,
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
