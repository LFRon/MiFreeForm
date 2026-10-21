package com.buildsession.betterYAMF.window.api

enum class PointerPhase { DOWN, MOVE, UP, CANCEL }
/** Display pixel positions in the current rotation; gestureY stays in the DOWN coordinate system.
 * timeMs uses monotonic uptime milliseconds. One gesture cannot span different display ids.
 */
data class EntryInput(val phase: PointerPhase, val x: Float, val y: Float,
    /** Swipe displacement stays in DOWN coordinates; hover uses current display coordinates. */
    val gestureY: Float, val width: Int, val height: Int,
    val displayId: Int = 0, val rotation: Int = 0, val timeMs: Long = 0L,
    /** DOWN only: true/false is a verified navigation-start fact supplied by the input owner.
     * Null retains the geometry-based admission contract for existing adapters. This fact does
     * not imply task tracking, source readiness or permission to take over the native gesture.
     */
    val navigationStart: Boolean? = null)
/** Native input ownership, independent of source pixels, Launcher lifecycle or backend readiness.
 * WAITING is retryable within this gesture; REJECTED is terminal until the next DOWN.
 */
enum class EntryTracking { WAITING, TRACKING, REJECTED }
/** A positive taskId identifies this gesture's definite candidate even while WAITING. Use -1
 * while its identity is unknown; a later different positive id cannot replace that application.
 */
data class EntryCandidate(val taskId: Int, val home: Boolean, val tracking: EntryTracking)
data class EntryDrop(val taskId: Int, val x: Int, val y: Int)
data class EntryTargetFrame(val bounds: WindowRect, val visible: Boolean, val active: Boolean)
/** Declare only verified mechanism combinations. Class/API existence alone is not sufficient. */
data class LauncherEntryCapabilities(val retainedSource: Boolean, val legacyAfterNative: Boolean)
enum class EntryDisposition { PASS_THROUGH, TAKEOVER_ARMED, LEGACY_QUEUED }

/** Implemented on the native input thread. Native objects are never returned through this port. */
interface LauncherEntryPort {
    val capabilities: LauncherEntryCapabilities
    /** Fresh local facts for this input, including UP. Null means temporarily unavailable.
     * A changed native owner must report REJECTED. No Binder calls or resource readiness waits.
     */
    fun candidate(): EntryCandidate?
    fun present(frame: EntryTargetFrame)
    /** Only arms capture. It must not consume the original UP input dispatch. */
    fun beginRetained(drop: EntryDrop): Boolean
    /** Runs after original UP cleanup; waits for native ownership stability before adoption. */
    fun queueLegacy(drop: EntryDrop)
    fun suppressNativeGesture(durationMs: Long)
}

interface LauncherEntryHandle {
    /** Synchronous local decision, never a Binder wait. Wrong thread/reentry preserves native input. */
    fun onInput(input: EntryInput): EntryDisposition
    fun targetBounds(width: Int, height: Int): WindowRect
    /** Retires the candidate and hides the target through the port; thread-safe and idempotent.
     * A cancelled in-flight query cannot revive it. Already armed native gates still require
     * original-UP cleanup by their native owner.
     */
    fun cancel()
}
