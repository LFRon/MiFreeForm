package com.buildsession.betterYAMF.window.api

import kotlin.math.roundToInt

/** Screen coordinates. Floating point values survive retargeting without integer drift. */
data class SceneRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    fun offset(dx: Float, dy: Float) = copy(left = left + dx, top = top + dy)
    fun rounded() = WindowRect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    /** Value geometry for the TOP uniform-cover contract; no motion or replacement policy. */
    fun coverTop(aspect: Float): SceneRect {
        require(aspect.isFinite() && aspect > 0f)
        val projectedWidth = maxOf(width, height * aspect)
        return SceneRect(left + (width - projectedWidth) / 2f, top, projectedWidth, projectedWidth / aspect)
    }
}

/** Display pixel coordinates in the declared physical rotation (0..3), never Launcher-local dp. */
data class SceneMetrics(val width: Int, val height: Int, val topInset: Int, val bottomInset: Int,
    val displayId: Int = 0, val rotation: Int = 0)

/** Uniform cover projection; TOP preserves the header as a viewport changes height. */
enum class ContentAlignment { CENTER, TOP }

/** Opaque retained pixels above the live task. Only the bottom edge of [clip] is feathered;
 * the rest is either source or live content, never a translucent overlap of two full layouts.
 * Coordinates and [featherPx] use display pixels, just like the shared content viewport.
 */
data class EntryImageVisual(val bounds: SceneRect, val clip: SceneRect, val featherPx: Float)

/** Application icon during side collapse/restoration. Display pixels; bounds may extend beyond
 * the display. Alpha and geometry are complete CORE outputs, not an animation for AD to start.
 * The icon belongs to the same task/session; presenting it must not launch another Activity.
 */
data class SideStashVisual(
    val side: WindowSide, val bounds: SceneRect, val cornerRadius: Float, val alpha: Float
)

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
    val configuredDurationMs: Float = 5100f,
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
    /** Retained pixels stay owned until a visible frame without them has committed. */
    val showEntryImage: Boolean = false,
    val entryImage: EntryImageVisual? = null,
    /** Source and task cover the same content viewport with the same alignment. */
    val contentAlignment: ContentAlignment = ContentAlignment.CENTER,
    /** Decoration opacity, including collapse into a side icon; excludes the icon itself. */
    val shellAlpha: Float = alpha,
    val sideStash: SideStashVisual? = null,
    /** False hides the owned content subtree, including its native touch targets. Ownership,
     * logical viewport and WM task visibility are retained; this is not task release/backgrounding.
     * Must be applied in the same presentation transaction as the matching shell buffer.
     */
    val taskVisible: Boolean = true,
    /** Permission to focus, not a request to steal focus. Restore only this session's suppression;
     * native policy may still prevent focus. Input reopening follows a committed restoration frame.
     */
    val taskFocusable: Boolean = true,
    val input: SceneInput = SceneInput.EMPTY,
    val coordinateSpace: SceneMetrics
)
