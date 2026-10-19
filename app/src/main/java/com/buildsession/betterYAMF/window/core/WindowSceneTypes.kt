package com.buildsession.betterYAMF.window.core

import kotlin.math.roundToInt

/** Screen coordinates. Floating point values survive retargeting without integer drift. */
data class SceneRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    fun offset(dx: Float, dy: Float) = copy(left = left + dx, top = top + dy)
    fun rounded() = WindowRect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

data class SceneMetrics(val width: Int, val height: Int, val topInset: Int, val bottomInset: Int)

/** Appearance policy, expressed without platform types. Dimensions originate in window_app.xml. */
data class SceneStyle(
    val density: Float,
    val miniCornerDp: Float,
    val barCornerDp: Float,
    val configuredDurationMs: Float = AnimTimingPolicy.CONFIGURED_MAX_MS,
    val animatorScale: Float = 1f,
    val coloredController: Boolean = false
) {
    val bezel get() = 6f * density
    val topBar get() = 18f * density
    val bottomBar get() = 20f * density
    val sideBar get() = 18f * density
    val margin get() = 8f * density
    val miniCorner get() = miniCornerDp * density
    val barCorner get() = barCornerDp * density
    val resizeTouchSize get() = 48f * density
    val handleWidth get() = 62f * density
    val handleThickness get() = 4f * density
    val timeScale: Float get() = if (animatorScale <= 0f) 0f else
        AnimTimingPolicy.resolveDuration(configuredDurationMs, animatorScale).toFloat() /
            AnimTimingPolicy.BASE_DURATION_MS
}

/** One immutable frame supplies decoration, task viewport and input regions together. */
data class WindowSceneFrame(
    val content: SceneRect,
    val outer: SceneRect,
    val miniFraction: Float,
    val alpha: Float,
    val outerRadius: Float,
    val contentRadius: Float,
    val controlsAlpha: Float,
    val acceptsContent: Boolean,
    val portrait: Boolean,
    val fullscreenHint: Boolean = false,
    val outerBottomRadius: Float = outerRadius,
    val controllerColor: Int = 0xFFFFFFFF.toInt(),
    val controlsColor: Int = 0xFF777777.toInt()
) {
    fun hitTest(x: Float, y: Float, style: SceneStyle): RegionId? {
        if (alpha <= 0f || !outer.contains(x, y)) return null
        if (!acceptsContent) return RegionId.BODY_MASK
        if (content.contains(x, y)) return null
        val size = style.resizeTouchSize
        if (x >= outer.right - size && y >= outer.bottom - size) return RegionId.RESIZE_HANDLE
        if (y < content.top) return RegionId.HANDLE
        return if (portrait) RegionId.BAR_BOTTOM else RegionId.BAR_SIDE
    }
}

sealed interface WindowSceneEvent {
    data class Input(val event: WindowInputEvent) : WindowSceneEvent
    data class OpenAsMini(
        val source: SceneRect? = null, val dropX: Int = -1, val dropY: Int = -1,
        val awaitSourceRelease: Boolean = false,
        val sourceCornerRadius: Float = 0f,
        val velocityX: Float = 0f, val velocityY: Float = 0f
    ) : WindowSceneEvent
    /** A retained source image can present and animate before the live task has been prepared. */
    data object VisualAvailable : WindowSceneEvent
    data object SourceReleased : WindowSceneEvent
    data object RestorePreview : WindowSceneEvent
    data object PresentationInvalidated : WindowSceneEvent
    data class TaskColor(val color: Int) : WindowSceneEvent
    data object TaskAvailable : WindowSceneEvent
    data class Prepared(val commandId: Long, val status: CommandStatus) : WindowSceneEvent
    data class Tick(val generation: Long, val timeMs: Long) : WindowSceneEvent
    /** DONE acknowledges a synchronized transaction commit, not physical presentation. */
    data class FrameCommitted(val frameId: Long, val status: CommandStatus) : WindowSceneEvent
    data class Metrics(val metrics: SceneMetrics) : WindowSceneEvent
    data class Visibility(val visible: Boolean) : WindowSceneEvent
    data object ResetPosition : WindowSceneEvent
    data object Destroy : WindowSceneEvent
}

sealed interface WindowSceneEffect {
    /** Prepare live content. An already retained source image can remain visible throughout. */
    data class Prepare(val commandId: Long, val normalContent: SceneRect) : WindowSceneEffect
    data class Present(val frameId: Long, val frame: WindowSceneFrame) : WindowSceneEffect
    data class RequestFrame(val generation: Long) : WindowSceneEffect
    /** DONE means a destination frame committed, including an intentionally transparent seed. */
    data class SourceHandoffResult(val status: CommandStatus) : WindowSceneEffect
    data class SystemCommand(val command: WindowCommand) : WindowSceneEffect
    data object FinishClose : WindowSceneEffect
    data class Failed(val operation: String) : WindowSceneEffect
}
