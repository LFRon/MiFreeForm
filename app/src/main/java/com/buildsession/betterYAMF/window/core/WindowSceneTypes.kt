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

/** Visual bridge, in display pixels. Its opacity and geometry are solved by CORE. */
enum class FullscreenStage { DRAGGING, RETURNING, COVERING, HIDING, EXPANDING, WAITING, REVEALING, FINISHED }

data class FullscreenVisual(
    val stage: FullscreenStage,
    val maskAlpha: Float,
    val cornerRadius: Float,
    val outline: SceneRect,
    val outlineRadius: Float,
    val outlineAlpha: Float,
    val allowsBarGesture: Boolean,
    val inputBounds: SceneRect
)

/** DONE requires the current fullscreen layout's new draw to have committed and native motion
 * to have relinquished it. Failures distinguish retained, released and unknown ownership.
 */
data class FullscreenHandoffResult(
    val status: CommandStatus, val taskReleased: Boolean,
    /** An uncertain native failure permits cleanup, never restoration using an old task pose. */
    val ownershipKnown: Boolean = true
)

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
    // 帧_04.jpg: a visible gray frame around the fullscreen preview, with the same fade track.
    val fullscreenOutlineWidth get() = 4f * density
    val fullscreenOutlineAlphaGain get() = 3.5f
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
    val controlsColor: Int = 0xFF777777.toInt(),
    val fullscreen: FullscreenVisual? = null,
    val taskAlpha: Float = alpha,
    /** False means this frame contains only the bridge; EXL must stop updating the live task. */
    val presentsLiveContent: Boolean = true,
    /** Retained entry pixels replace live content until CORE selects the replacement frame. */
    val showEntryImage: Boolean = false
) {
    fun hitTest(x: Float, y: Float, style: SceneStyle): RegionId? {
        if (alpha <= 0f || !outer.contains(x, y)) return null
        if (!acceptsContent) {
            if (fullscreen != null) {
                if (fullscreen.allowsBarGesture) {
                    if (portrait && y >= content.bottom) return RegionId.BAR_BOTTOM
                    if (!portrait && x >= content.right) return RegionId.BAR_SIDE
                }
                // Consume bridge touches without starting a body drag/fling that could outlive
                // the return animation and unexpectedly move the restored normal window.
                return null
            }
            return RegionId.BODY_MASK
        }
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
    /** Final destination layout, before native adoption; never reshapes an already prepared app. */
    data class EntryLayout(val metrics: SceneMetrics, val width: Int, val height: Int,
        val preferredTop: Int = 0) : WindowSceneEvent
    data object RestorePreview : WindowSceneEvent
    data object PresentationInvalidated : WindowSceneEvent
    data class TaskColor(val color: Int) : WindowSceneEvent
    data object TaskAvailable : WindowSceneEvent
    data class Prepared(val commandId: Long, val status: CommandStatus) : WindowSceneEvent
    data class Tick(val generation: Long, val timeMs: Long) : WindowSceneEvent
    /** DONE acknowledges a synchronized transaction commit, not physical presentation. */
    data class FrameCommitted(val frameId: Long, val status: CommandStatus) : WindowSceneEvent
    data class FullscreenHandoff(val commandId: Long, val result: FullscreenHandoffResult) : WindowSceneEvent
    data class Metrics(val metrics: SceneMetrics) : WindowSceneEvent
    data class Visibility(val visible: Boolean) : WindowSceneEvent
    data object ResetPosition : WindowSceneEvent
    data object Destroy : WindowSceneEvent
}

sealed interface WindowSceneEffect {
    /** The destination layout is selected; acquire the live task using that exact viewport. */
    data object ActivateEntry : WindowSceneEffect
    /** Prepare live content. An already retained source image can remain visible throughout. */
    data class Prepare(val commandId: Long, val normalContent: SceneRect) : WindowSceneEffect
    data class Present(val frameId: Long, val frame: WindowSceneFrame) : WindowSceneEffect
    data class RequestFrame(val generation: Long) : WindowSceneEffect
    /** DONE means a destination frame committed, including an intentionally transparent seed. */
    data class SourceHandoffResult(val status: CommandStatus) : WindowSceneEffect
    /** Abort the source owner after its destination-frame acknowledgement already completed. */
    data class CancelSourceHandoff(val reason: CommandStatus) : WindowSceneEffect
    /** The selected, visible live replacement frame committed; its retained pixels can be freed. */
    data object ReleaseEntryImage : WindowSceneEffect
    data class SystemCommand(val command: WindowCommand) : WindowSceneEffect
    data class HandoffFullscreen(val commandId: Long) : WindowSceneEffect
    data object FinishFullscreen : WindowSceneEffect
    data object FinishClose : WindowSceneEffect
    data class Failed(val operation: String) : WindowSceneEffect
}
