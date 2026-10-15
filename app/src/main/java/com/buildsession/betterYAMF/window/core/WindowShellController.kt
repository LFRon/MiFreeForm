package com.buildsession.betterYAMF.window.core

class WindowShellController(
    private val registration: WindowRegistration,
    private val config: CoreConfig = CoreConfig(),
    private val topPort: TopPort = AlwaysTopPort
) {
    val windowId: Int = registration.windowId

    private var mode = ShellMode.NORMAL
    private var screenWidth = 0
    private var screenHeight = 0

    private var pressedRegion: RegionId? = null
    private var pressX = 0f
    private var pressY = 0f
    private var dropActive: DropKind? = null
    private var lastBarTapMs = 0L

    fun onScreenMetricsChanged(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }

    fun snapshot(): ShellSnapshot = ShellSnapshot(
        windowId = windowId,
        mode = mode,
        dragging = pressedRegion == RegionId.BODY_MASK || pressedRegion == RegionId.HANDLE,
        resizing = pressedRegion == RegionId.RESIZE_HANDLE
    )

    fun onInput(event: WindowInputEvent): List<WindowCommand> {
        if (event.windowId != windowId) return emptyList()
        return when (event) {
            is WindowInputEvent.Press -> onPress(event)
            is WindowInputEvent.Move -> onMove(event)
            is WindowInputEvent.Up -> onUp(event)
            is WindowInputEvent.Cancel -> onCancel(event)
            is WindowInputEvent.TapConfirmed -> onTapConfirmed()
            is WindowInputEvent.Tap -> onTap(event)
            is WindowInputEvent.RequestMini -> onRequestMini()
            is WindowInputEvent.Fling -> listOf(WindowCommand.FlingBy(windowId, event.velocityX, event.velocityY))
        }
    }

    /** 外部请求进入 mini（如"打开即 mini"）：仅 NORMAL→MINI 迁移，幂等。 */
    private fun onRequestMini(): List<WindowCommand> {
        if (mode == ShellMode.MINI) return emptyList()
        mode = ShellMode.MINI
        return listOf(WindowCommand.EnterMini(windowId, DropKind.CORNER_TR))
    }

    private fun onPress(event: WindowInputEvent.Press): List<WindowCommand> {
        pressedRegion = event.region
        pressX = event.screenX
        pressY = event.screenY
        dropActive = null
        return when (event.region) {
            RegionId.RESIZE_HANDLE -> listOf(WindowCommand.ResizePreviewStart(windowId))
            else -> emptyList()
        }
    }

    private fun onMove(event: WindowInputEvent.Move): List<WindowCommand> {
        if (event.region != pressedRegion) return emptyList()
        return when (event.region) {
            RegionId.BODY_MASK, RegionId.HANDLE -> onBodyMove(event)
            RegionId.BAR_BOTTOM, RegionId.BAR_SIDE -> onBarMove(event)
            RegionId.RESIZE_HANDLE -> listOf(
                WindowCommand.ResizePreviewUpdate(windowId, event.screenX - pressX, event.screenY - pressY)
            )
            else -> emptyList()
        }
    }

    private fun onBodyMove(event: WindowInputEvent.Move): List<WindowCommand> {
        val out = mutableListOf<WindowCommand>()
        out += WindowCommand.MoveBy(windowId, event.screenX - pressX, event.screenY - pressY)
        out += WindowCommand.DropZoneShow(windowId)
        if (screenWidth > 0 && screenHeight > 0) {
            val highlight = DropTargetCalculator.compute(
                event.screenX,
                event.screenY,
                screenWidth,
                screenHeight,
                allowCorners = mode == ShellMode.NORMAL
            )
            dropActive = if (highlight.active) highlight.hover else null
            out += WindowCommand.DropHover(windowId, highlight.hover, highlight.active)
        }
        return out
    }

    private fun onBarMove(event: WindowInputEvent.Move): List<WindowCommand> {
        val threshold = config.verticalDragThresholdDp * registration.density
        val diffY = event.screenY - pressY
        return if (diffY < 0) {
            listOf(
                WindowCommand.VerticalDragPreview(
                    windowId,
                    (-diffY / threshold).coerceIn(0f, 1f),
                    downward = false,
                    showFullscreenHint = false
                )
            )
        } else {
            listOf(
                WindowCommand.VerticalDragPreview(
                    windowId,
                    (diffY / threshold).coerceIn(0f, 1f),
                    downward = true,
                    showFullscreenHint = diffY > threshold
                )
            )
        }
    }

    private fun onUp(event: WindowInputEvent.Up): List<WindowCommand> {
        if (event.region != pressedRegion) return emptyList()
        val out = mutableListOf<WindowCommand>()
        raiseIfNeeded(out)
        when (event.region) {
            RegionId.BODY_MASK, RegionId.HANDLE -> decideDrop(out)
            RegionId.BAR_BOTTOM, RegionId.BAR_SIDE -> decideBarRelease(out, event)
            RegionId.RESIZE_HANDLE -> {
                out += WindowCommand.ResizePreviewCommit(windowId)
                pressedRegion = null
            }
            else -> pressedRegion = null
        }
        return out
    }

    private fun onCancel(event: WindowInputEvent.Cancel): List<WindowCommand> {
        if (event.region != pressedRegion) return emptyList()
        val out = mutableListOf<WindowCommand>()
        when (event.region) {
            RegionId.BODY_MASK, RegionId.HANDLE -> decideDrop(out)
            RegionId.BAR_BOTTOM, RegionId.BAR_SIDE -> decideBarRelease(out, event)
            else -> pressedRegion = null
        }
        return out
    }

    private fun decideDrop(out: MutableList<WindowCommand>) {
        val kind = dropActive
        when (kind) {
            DropKind.CORNER_TL, DropKind.CORNER_TR, DropKind.CORNER_BL, DropKind.CORNER_BR -> {
                out += WindowCommand.EnterMini(windowId, kind)
                mode = ShellMode.MINI
            }
            // 侧边吸附与角落一致：缩小并贴边显示应用画面（不再收起为图标球）
            DropKind.SIDE_LEFT, DropKind.SIDE_RIGHT -> {
                out += WindowCommand.EnterMini(windowId, kind)
                mode = ShellMode.MINI
            }
            null -> out += WindowCommand.ClampToScreen(windowId, animated = true)
        }
        out += WindowCommand.DropZoneHide(windowId)
        dropActive = null
        pressedRegion = null
    }

    private fun decideBarRelease(out: MutableList<WindowCommand>, event: WindowInputEvent.Pointer) {
        val threshold = config.verticalDragThresholdDp * registration.density
        val diffY = event.screenY - pressY
        when {
            diffY < -threshold -> out += WindowCommand.RequestClose(windowId, CloseOrigin.DRAG_UP_BAR)
            diffY > threshold -> out += WindowCommand.RequestFullscreen(windowId, FullscreenOrigin.DRAG_DOWN_BAR)
            else -> out += WindowCommand.ClearVerticalDragPreview(windowId)
        }
        pressedRegion = null
    }

    private fun onTapConfirmed(): List<WindowCommand> = when (mode) {
        ShellMode.MINI -> {
            mode = ShellMode.NORMAL
            listOf(WindowCommand.ExitMini(windowId))
        }
        else -> emptyList()
    }

    private fun onTap(event: WindowInputEvent.Tap): List<WindowCommand> {
        return when (event.region) {
            RegionId.BAR_BOTTOM, RegionId.BAR_SIDE -> {
                val out = mutableListOf<WindowCommand>()
                if (event.timeMs - lastBarTapMs < config.doubleTapTimeoutMs) {
                    out += WindowCommand.RequestClose(windowId, CloseOrigin.DOUBLE_TAP_BAR)
                }
                lastBarTapMs = event.timeMs
                out
            }
            else -> emptyList()
        }
    }

    private fun raiseIfNeeded(out: MutableList<WindowCommand>) {
        if (!topPort.isTop(windowId)) {
            out += WindowCommand.RaiseWindow(windowId)
            topPort.markTop(windowId)
        }
    }
}
