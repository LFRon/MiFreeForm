package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

sealed interface WindowSceneEvent {
    data class Input(val event: WindowInputEvent) : WindowSceneEvent
    data class OpenAsMini(
        val source: SceneRect? = null, val dropX: Int = -1, val dropY: Int = -1,
        val awaitSourceRelease: Boolean = false,
        val sourceCornerRadius: Float = 0f,
        val velocityX: Float = 0f, val velocityY: Float = 0f,
        /** Pixel aspect after platform normalization; independent of raster-rounded source bounds. */
        val sourceImageAspect: Float? = null,
        val origin: EntryOrigin = EntryOrigin.RECENTS
    ) : WindowSceneEvent
    /** A retained source image can present and animate before the live task has been prepared. */
    data object VisualAvailable : WindowSceneEvent
    data object SourceReleased : WindowSceneEvent
    data class EntryAbort(val failure: WindowFailure? = null) : WindowSceneEvent
    /** Final destination layout, before native adoption; never reshapes an already prepared app. */
    data class EntryLayout(val metrics: SceneMetrics, val width: Int, val height: Int,
        val preferredTop: Int = 0) : WindowSceneEvent
    data object RestorePreview : WindowSceneEvent
    data object PresentationInvalidated : WindowSceneEvent
    data class TaskColor(val color: Int) : WindowSceneEvent
    data object TaskAvailable : WindowSceneEvent
    data object TaskOwnershipLost : WindowSceneEvent
    data class Prepared(val commandId: Long, val status: CommandStatus) : WindowSceneEvent
    data class Tick(val generation: Long, val timeMs: Long) : WindowSceneEvent
    /** DONE acknowledges a synchronized transaction commit, not physical presentation. */
    data class FrameCommitted(val frameId: Long, val status: CommandStatus) : WindowSceneEvent
    data class FullscreenHandoff(val commandId: Long, val result: FullscreenHandoffResult) : WindowSceneEvent
    /** Existing application layout is an execution fact; CORE decides whether it needs migration. */
    data class Metrics(val metrics: SceneMetrics, val existingViewport: WindowRect? = null) : WindowSceneEvent
    data object StashSlotsChanged : WindowSceneEvent
    data class KeyboardOcclusion(val bottomInset: Int) : WindowSceneEvent
    data class Activate(val requestId: Long, val origin: EntryOrigin = EntryOrigin.NOTIFICATION) : WindowSceneEvent
    data class CancelActivation(val requestId: Long) : WindowSceneEvent
    data class Visibility(val visible: Boolean) : WindowSceneEvent
    data class HomeForeground(val facts: HomeMaintenanceFacts) : WindowSceneEvent
    data object ResetPosition : WindowSceneEvent
    data object Destroy : WindowSceneEvent
}

sealed interface WindowSceneEffect {
    data object StashSlotsChanged : WindowSceneEffect
    data class ActivationResult(val requestId: Long, val status: CommandStatus) : WindowSceneEffect
    data class AbortEntry(val failure: WindowFailure?) : WindowSceneEffect
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
    data object FinishOwnershipLoss : WindowSceneEffect
    data class Failed(val reason: WindowFailure) : WindowSceneEffect
}
