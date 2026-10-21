package com.buildsession.betterYAMF.window.api

enum class RegionId {
    BODY_MASK,
    HANDLE,
    BAR_BOTTOM,
    BAR_SIDE,
    RESIZE_HANDLE,
    CONTENT,
    /** Side icon activation. Press/Up within tap slop restores; never drags or flings a task. */
    STASH_HANDLE
}

enum class DropKind { CORNER_TL, CORNER_TR, CORNER_BL, CORNER_BR, SIDE_LEFT, SIDE_RIGHT }
enum class WindowSide { LEFT, RIGHT }

/** Display pixels and monotonic uptime milliseconds; fling velocity uses pixels/second. */
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

}

enum class CommandStatus { DONE, FAILED, SUPERSEDED }
