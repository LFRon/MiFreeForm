package com.buildsession.betterYAMF.window.core

import kotlin.math.abs

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
    /** 闭环（不变量 2）：模式迁移命令的指令计数与未决登记。 */
    private var nextCommandId = 1L
    private var pendingMiniCommandId = 0L
    private var pendingMiniFromMode = ShellMode.NORMAL

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
            is WindowInputEvent.RequestMini -> onRequestMini()
            is WindowInputEvent.Fling -> listOf(WindowCommand.FlingBy(windowId, event.velocityX, event.velocityY))
            is WindowInputEvent.CommandResult -> onCommandResult(event)
            is WindowInputEvent.ScreenMetricsChanged -> {
                onScreenMetricsChanged(event.width, event.height)
                emptyList()
            }
        }
    }

    /** 外部请求进入 mini（如"打开即 mini"）：仅 NORMAL→MINI 迁移，幂等。 */
    private fun onRequestMini(): List<WindowCommand> {
        if (mode == ShellMode.MINI) return emptyList()
        return listOf(
            miniCommand(ShellMode.MINI) { id -> WindowCommand.EnterMini(windowId, DropKind.CORNER_TR, id) }
        )
    }

    /**
     * 模式迁移命令（进/出 mini）：分配命令 id 并登记未决回灌——
     * 宿主执行失败回 Failed（回退模式）；被后续命令取代则闭包作废（陈旧结果忽略）。
     */
    private fun miniCommand(target: ShellMode, build: (Long) -> WindowCommand): WindowCommand {
        val id = nextCommandId++
        pendingMiniCommandId = id
        pendingMiniFromMode = mode
        mode = target
        return build(id)
    }

    /** 命令结果回灌：仅收敛当前未决的模式命令；陈旧结果（已被取代/已收敛）一律忽略。 */
    private fun onCommandResult(event: WindowInputEvent.CommandResult): List<WindowCommand> {
        if (event.commandId != pendingMiniCommandId) return emptyList()
        pendingMiniCommandId = 0L
        if (event.status == CommandStatus.FAILED) {
            mode = pendingMiniFromMode
        }
        return emptyList()
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
            RegionId.BAR_BOTTOM, RegionId.BAR_SIDE -> decideBarRelease(out, event, tapEligible = true)
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
            RegionId.BAR_BOTTOM, RegionId.BAR_SIDE -> decideBarRelease(out, event, tapEligible = false)
            RegionId.RESIZE_HANDLE -> {
                out += WindowCommand.ResizePreviewCommit(windowId)
                pressedRegion = null
            }
            else -> pressedRegion = null
        }
        return out
    }

    private fun decideDrop(out: MutableList<WindowCommand>) {
        when (val kind = dropActive) {
            // 角落与侧边一致：缩小并贴边显示应用画面（不再收起为图标球）
            DropKind.CORNER_TL, DropKind.CORNER_TR, DropKind.CORNER_BL, DropKind.CORNER_BR,
            DropKind.SIDE_LEFT, DropKind.SIDE_RIGHT ->
                out += miniCommand(ShellMode.MINI) { id -> WindowCommand.EnterMini(windowId, kind, id) }
            null -> out += WindowCommand.ClampToScreen(windowId, animated = true)
        }
        out += WindowCommand.DropZoneHide(windowId)
        dropActive = null
        pressedRegion = null
    }

    /**
     * 栏释放判定。[tapEligible]=false（Cancel 打断）绝不计数点按。
     * 栏点按（双击关闭）由 Press→Up 位移判定：宿主点击回调被 OnTouchListener 吞掉
     * （View.dispatchTouchEvent 语义），判定收归核心；跨栏共享计时。
     */
    private fun decideBarRelease(
        out: MutableList<WindowCommand>,
        event: WindowInputEvent.Pointer,
        tapEligible: Boolean
    ) {
        val threshold = config.verticalDragThresholdDp * registration.density
        val slop = config.barTapSlopDp * registration.density
        val diffY = event.screenY - pressY
        val diffX = event.screenX - pressX
        when {
            diffY < -threshold -> out += WindowCommand.RequestClose(windowId, CloseOrigin.DRAG_UP_BAR)
            diffY > threshold -> out += WindowCommand.RequestFullscreen(windowId, FullscreenOrigin.DRAG_DOWN_BAR)
            else -> {
                out += WindowCommand.ClearVerticalDragPreview(windowId)
                if (tapEligible && abs(diffY) <= slop && abs(diffX) <= slop) {
                    if (event.timeMs - lastBarTapMs < config.doubleTapTimeoutMs) {
                        out += WindowCommand.RequestClose(windowId, CloseOrigin.DOUBLE_TAP_BAR)
                    }
                    lastBarTapMs = event.timeMs
                }
            }
        }
        pressedRegion = null
    }

    private fun onTapConfirmed(): List<WindowCommand> = when (mode) {
        ShellMode.MINI ->
            listOf(miniCommand(ShellMode.NORMAL) { id -> WindowCommand.ExitMini(windowId, id) })
        else -> emptyList()
    }

    private fun raiseIfNeeded(out: MutableList<WindowCommand>) {
        if (!topPort.isTop(windowId)) {
            out += WindowCommand.RaiseWindow(windowId)
            topPort.markTop(windowId)
        }
    }
}
