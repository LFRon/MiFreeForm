package com.buildsession.betterYAMF.window.api

/** A window instance, never a reusable task id. Epoch and windowId are minted by the process owner;
 * windowId is unique among its live sessions. A restarted process must use a new epoch.
 */
data class SessionKey(val epoch: Long, val windowId: Int)

/** Owner-thread, idempotent cancellation. It retires this operation's resources only; committed
 * native work may finish later. The runtime supersedes its ticket before calling cancel().
 */
fun interface WindowOperation { fun cancel() }

/** All windows in a process share this serial owner. Port calls run on it; completions may arrive
 * synchronously or from other threads and are serialized by the runtime. All time values use
 * monotonic uptime milliseconds, matching input timestamps (never elapsed realtime/wall time).
 */
interface RuntimeScheduler {
    fun isCurrentThread(): Boolean
    fun post(work: () -> Unit)
    fun after(delayMs: Long, work: () -> Unit): WindowOperation
    /** Schedules a future external frame, never an immediate recursive clock tick. */
    fun nextFrame(work: (Long) -> Unit): WindowOperation
}

data class WindowOptions(
    val style: SceneStyle,
    val widthDp: Int = 280,
    val heightDp: Int = 0,
    val portraitY: Int = 0,
    val landscapeY: Int = 0
)

/** Selected by CORE before adoption. normalContent is in display pixels; logicalViewport is the
 * application's fixed logical pixel bounds. Presentation scales/crops content without relayout;
 * it must not silently change app density or rotation. Fullscreen relinquishes this constraint.
 */
data class TaskLayout(val normalContent: SceneRect, val logicalViewport: WindowRect)
data class FrameSubmission(val session: SessionKey, val frameId: Long, val frame: WindowSceneFrame)
data class DropTargetVisual(
    val kind: DropKind?, val active: Boolean, val cornerRadiusPx: Float,
    val sideLeft: SceneRect, val sideRight: SceneRect, val coordinateSpace: SceneMetrics
)

data class EntryRequest(
    val handoffId: Long = 0L,
    val source: SceneRect? = null,
    val dropX: Int = -1,
    val dropY: Int = -1,
    val awaitSourceRelease: Boolean = false,
    val retainedVisual: Boolean = false,
    val remoteSourceOwner: Boolean = false,
    val sourceCornerRadius: Float = 0f,
    /** Display pixels/second, normalized from the source platform's own velocity units. */
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val sourceImageAspect: Float? = null
)

data class HomeMaintenanceFacts(
    val ownsTask: Boolean, val aboveHomeGuaranteed: Boolean,
    val touchActive: Boolean, val gestureSuppressed: Boolean
)

enum class WindowFailure { PREPARATION, PRESENTATION, SOURCE_TIMEOUT, DESTINATION, EXECUTION, OWNERSHIP }

/** Exactly one terminal outcome of observing destination layout/ownership stability. */
sealed interface DestinationResult {
    data class Done(val metrics: SceneMetrics) : DestinationResult
    data object Failed : DestinationResult
    data object Superseded : DestinationResult
}

/** Bound to one session and immutable task id. Native tokens/leashes stay in this implementation.
 * Each asynchronous result must terminate exactly once with DONE/FAILED/SUPERSEDED, even when
 * coalesced. cancel() may trigger a callback; that callback cannot advance a cancelled ticket.
 */
interface TaskSessionPort {
    /** Actual fixed viewport of an already-owned task; null for a newly acquired task. */
    fun existingViewport(): WindowRect? = null
    /** Acceptance only. Resource availability is delivered as WindowEvent.TaskAvailable.
     * Rejection must leave no untracked task ownership; finish() still cleans a partial adoption.
     */
    fun adopt(layout: TaskLayout): Boolean
    /** DONE guarantees the selected viewport and its live surface are ready for presentation.
     * Returning from this method, a null controller or a fixed delay cannot prove readiness.
     */
    fun prepare(commandId: Long, layout: TaskLayout, result: (CommandStatus) -> Unit): WindowOperation
    /** released() reports actual ownership loss. DONE additionally requires the new fullscreen
     * layout's draw transaction to commit and native motion to finish. Failure reports ownership
     * through FullscreenHandoffResult; it must never restore a task using a stale pose.
     */
    fun handoffFullscreen(commandId: Long, released: () -> Unit,
        result: (FullscreenHandoffResult) -> Unit): WindowOperation
    fun raise(): Boolean
}

interface ScenePresenter {
    /** Installs a transparent initial scene; this is not a committed frame acknowledgment. */
    fun seed(frame: WindowSceneFrame)
    /** Applies the complete frame, including its input map, and reports exactly one terminal result.
     * DONE witnesses a produced shell buffer and its synchronized content transaction commit,
     * not physical scanout. Coalesced frames report SUPERSEDED; critical fences are then retried
     * by CORE. The adapter cannot invent a second animation or release retained pixels early.
     */
    fun present(submission: FrameSubmission, result: (CommandStatus) -> Unit)
    fun highFrameRate(enabled: Boolean)
    fun dropTarget(visual: DropTargetVisual?)
    fun releaseEntryImage()
}

interface SourceHandoffPort {
    /** Session-bound bridge-frame result. DONE permits the captured native source to finish;
     * it does not claim task adoption or source release. A later failure/supersession cancels
     * the still-pending source handoff. Transport and native continuation stay below this port.
     */
    fun acknowledge(status: CommandStatus)
    /** Runs only after SourceReleased. Done reports real destination rotation/transition readiness;
     * failures and cancellation also terminate exactly once. Asynchronous observer exceptions
     * must become Failed instead of escaping a platform callback. Cancellation stops the waiter.
     * This is a readiness observation, never a delay.
     */
    fun awaitDestination(result: (DestinationResult) -> Unit): WindowOperation
}

interface WindowLifecyclePort {
    fun taskReleased()
    /** Diagnostic cause is delivered outward, never interpreted by CORE. */
    fun failed(reason: WindowFailure, cause: Throwable? = null)
    /** Exactly once. Dispose this session's native input window, draw listeners, pixels and other
     * resources even if task release is false. Never remove a replacement session's resources.
     */
    fun finish(releaseTask: Boolean, restoreToFront: Boolean)
}

data class WindowPorts(val tasks: TaskSessionPort, val presenter: ScenePresenter,
    val source: SourceHandoffPort, val lifecycle: WindowLifecyclePort)

/** Public ingress is stable; CORE's own events/effects and controllers are not exported here. */
sealed interface WindowEvent {
    data class Input(val value: WindowInputEvent) : WindowEvent
    data class Entry(val request: EntryRequest) : WindowEvent
    /** Session's retained pixels are installed and drawable; not proof of a committed frame. */
    data object VisualAvailable : WindowEvent
    /** Currently-owned native task surface is available; not proof of viewport preparation. */
    data object TaskAvailable : WindowEvent
    /** This session no longer controls the task. Stop presentation and dispose its host;
     * never issue another task release. The adapter reconciles only its own layout overrides.
     */
    data object TaskOwnershipLost : WindowEvent
    /** Captured native source really finished and retired, in response to the bridge ACK. */
    data class SourceReleased(val handoffId: Long) : WindowEvent
    data class EntryCancelled(val handoffId: Long) : WindowEvent
    data class Metrics(val value: SceneMetrics) : WindowEvent
    data class Visibility(val visible: Boolean) : WindowEvent
    data class TaskColor(val color: Int) : WindowEvent
    data class HomeForeground(val facts: HomeMaintenanceFacts) : WindowEvent
    data object PresentationInvalidated : WindowEvent
    data object ResetPosition : WindowEvent
}

interface WindowRuntimeHandle {
    val session: SessionKey
    val destroyed: Boolean
    /** Thread-safe ingress. Queued events and callbacks from a destroyed session are discarded. */
    fun post(event: WindowEvent)
    /** Immutable last desired frame, safe to read across threads; not a native presentation query. */
    fun snapshot(): WindowSceneFrame
    /** Owner-thread only. Selection from CORE's processed metrics, never an ahead-of-pump fact
     * cache. Diagnostic/current selection, not permission to adopt independently.
     */
    fun normalLayout(): TaskLayout
    /** Immutable once bound. Must precede every native adoption or callback. */
    fun bindTask(taskId: Int): Boolean
    /** Initial ordinary entry uses the same core-selected layout as retained entry. */
    fun activateTask(): Boolean
    /** Thread-safe, idempotent terminal cancellation; no further events can revive this handle. */
    fun close(releaseTask: Boolean, restoreToFront: Boolean = false)
}
