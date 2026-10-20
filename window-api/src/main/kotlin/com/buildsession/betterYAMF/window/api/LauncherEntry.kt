package com.buildsession.betterYAMF.window.api

enum class PointerPhase { DOWN, MOVE, UP, CANCEL }
/** Display pixel positions in the current rotation; gestureY stays in the DOWN coordinate system.
 * timeMs uses monotonic uptime milliseconds. One gesture cannot span different display ids.
 */
data class EntryInput(val phase: PointerPhase, val x: Float, val y: Float,
    /** Swipe displacement stays in DOWN coordinates; hover uses current display coordinates. */
    val gestureY: Float, val width: Int, val height: Int,
    val displayId: Int = 0, val rotation: Int = 0, val timeMs: Long = 0L)
data class EntryCandidate(val taskId: Int, val home: Boolean,
    val launcherResumed: Boolean, val sourceVisible: Boolean)
data class EntryDrop(val taskId: Int, val x: Int, val y: Int)
data class EntryTargetFrame(val bounds: WindowRect, val visible: Boolean, val active: Boolean)
/** Declare only verified mechanism combinations. Class/API existence alone is not sufficient. */
data class LauncherEntryCapabilities(val retainedSource: Boolean, val legacyAfterNative: Boolean)
enum class EntryDisposition { PASS_THROUGH, TAKEOVER_ARMED, LEGACY_QUEUED }

/** Implemented on the native input thread. Native objects are never returned through this port. */
interface LauncherEntryPort {
    val capabilities: LauncherEntryCapabilities
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
