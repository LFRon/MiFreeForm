package com.buildsession.betterYAMF.window.core

internal class FakeTopPort(var top: Boolean = true) : TopPort {
    override fun isTop(windowId: Int): Boolean = top
    override fun markTop(windowId: Int) {
        top = true
    }
}

internal fun press(
    region: RegionId,
    x: Float = 0f,
    y: Float = 0f,
    windowId: Int = 1,
    timeMs: Long = 0L
) = WindowInputEvent.Press(windowId, timeMs, region, x, y)

internal fun move(
    region: RegionId,
    x: Float,
    y: Float,
    windowId: Int = 1,
    timeMs: Long = 1L
) = WindowInputEvent.Move(windowId, timeMs, region, x, y)

internal fun up(
    region: RegionId,
    x: Float = 0f,
    y: Float = 0f,
    windowId: Int = 1,
    timeMs: Long = 2L
) = WindowInputEvent.Up(windowId, timeMs, region, x, y)

internal fun cancel(
    region: RegionId,
    x: Float = 0f,
    y: Float = 0f,
    windowId: Int = 1,
    timeMs: Long = 2L
) = WindowInputEvent.Cancel(windowId, timeMs, region, x, y)

internal fun tapConfirmed(
    region: RegionId = RegionId.BODY_MASK,
    windowId: Int = 1,
    timeMs: Long = 0L
) = WindowInputEvent.TapConfirmed(windowId, timeMs, region, 0f, 0f)

internal fun fling(
    velocityX: Float = 0f,
    velocityY: Float = 0f,
    windowId: Int = 1,
    timeMs: Long = 2L
) = WindowInputEvent.Fling(windowId, timeMs, velocityX, velocityY)

internal fun result(
    commandId: Long,
    status: CommandStatus,
    windowId: Int = 1,
    timeMs: Long = 3L
) = WindowInputEvent.CommandResult(windowId, timeMs, commandId, status)

internal fun requestMini(
    windowId: Int = 1,
    timeMs: Long = 0L
) = WindowInputEvent.RequestMini(windowId, timeMs)
