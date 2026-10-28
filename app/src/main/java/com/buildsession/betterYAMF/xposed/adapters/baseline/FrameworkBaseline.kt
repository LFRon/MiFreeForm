package com.buildsession.betterYAMF.xposed.adapters.baseline

import com.buildsession.betterYAMF.xposed.adapters.RomProfile
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import java.util.concurrent.ConcurrentHashMap

/**
 * Which framework branch an injected process actually runs.
 *
 * This is the axis that changes method/field shapes: LineageOS 24.0 and PixelOS 17 both sit on the
 * AOSP branch (no `Task#getLeash`, no three-argument `InsetsState#calculateInsets`, no
 * `WindowStateAnimator#mSurfaceController`), while the Google branch adds those and drops
 * `ShadeController#collapseOnMainThread`. A distribution name is only a label on top of it.
 */
internal enum class FrameworkBaseline { AOSP, GOOGLE, UNKNOWN }

/**
 * Member facts the reviewed hook code branches on. Every fact reads "the Google-branch member is
 * present"; a class missing from this process reports `false`, so the reviewed fallback stays.
 *
 * Facts come from class members, never from build labels: `Build.PRODUCT` and `Build.DISPLAY`
 * cannot prove a branch, and the `ro.*` version props that would are SELinux-denied even to
 * system_server on the reviewed devices.
 */
internal data class FrameworkShape(
    val baseline: FrameworkBaseline,
    val taskLeash: Boolean,
    val insetsIgnoreVisibility: Boolean,
    val displayPolicyBarGetters: Boolean,
    val shadeCollapseOnMainThread: Boolean,
    val windowSurfaceController: Boolean,
) {
    /** Short form for the boot log; the caller already knows the SDK. */
    val summary: String get() = "baseline=${baseline.name.lowercase()} " +
        "taskLeash=$taskLeash insetsIgnoreVisibility=$insetsIgnoreVisibility " +
        "barGetters=$displayPolicyBarGetters shadeCollapse=$shadeCollapseOnMainThread " +
        "surfaceController=$windowSurfaceController"

    companion object {
        /** One probe per injected ClassLoader; every process answers for the classes it can see. */
        private val probed = ConcurrentHashMap<ClassLoader, FrameworkShape>()

        /** Every fact falls back to the AOSP shape, so a failed probe degrades instead of aborting.
         * A failure is not cached: the next process-level caller retries once its classes exist.
         */
        fun of(loader: ClassLoader, rom: RomProfile? = null): FrameworkShape {
            val found = probed[loader] ?: runCatching { probe(loader) }
                .onSuccess { probed[loader] = it; log(TAG) { "framework shape ${it.summary}" } }
                .onFailure { logWarning(TAG, "framework shape probe unavailable; using AOSP fallbacks", it) }
                .getOrNull()
                ?: EMPTY
            return rom?.applyTo(found) ?: found
        }

        private val EMPTY = FrameworkShape(FrameworkBaseline.UNKNOWN, taskLeash = false,
            insetsIgnoreVisibility = false, displayPolicyBarGetters = false,
            shadeCollapseOnMainThread = false, windowSurfaceController = false)

        private fun probe(loader: ClassLoader): FrameworkShape = describe(
            task = loader.optional("com.android.server.wm.Task"),
            insets = loader.optional("android.view.InsetsState"),
            policy = loader.optional("com.android.server.wm.DisplayPolicy"),
            shade = loader.optional("com.android.systemui.shade.ShadeController"),
            animator = loader.optional("com.android.server.wm.WindowStateAnimator"),
        )

        /** Pure classification seam: every argument is the class this process actually loaded. */
        internal fun describe(task: Class<*>?, insets: Class<*>?, policy: Class<*>?,
            shade: Class<*>?, animator: Class<*>?): FrameworkShape {
            val taskLeash = task.hasMethod("getLeash")
            val insetsIgnoreVisibility = insets.hasMethod("calculateInsets",
                listOf("android.graphics.Rect", "int", "boolean"))
            val barGetters = policy.hasMethod("getStatusBar")
            val shadeCollapse = shade.hasMethod("collapseOnMainThread")
            val surfaceController = animator.hasField("mSurfaceController")

            // The ShadeController probe answers in SystemUI, the Task probe in system_server; the
            // Launcher process sees neither, so it stays UNKNOWN instead of guessing.
            val baseline = when {
                shade != null -> if (shadeCollapse) FrameworkBaseline.AOSP else FrameworkBaseline.GOOGLE
                task != null -> if (taskLeash) FrameworkBaseline.GOOGLE else FrameworkBaseline.AOSP
                else -> FrameworkBaseline.UNKNOWN
            }
            return FrameworkShape(baseline, taskLeash, insetsIgnoreVisibility, barGetters,
                shadeCollapse, surfaceController)
        }

        /** Name-only match; used for members whose name is unique on the reviewed classes. */
        private fun Class<*>?.hasMethod(name: String): Boolean = runCatching {
            this != null && generateSequence(this) { it.superclass }.any { type ->
                type.declaredMethods.any { it.name == name }
            }
        }.getOrDefault(false)

        /** Exact signature by type names: `Class` identity differs between injected loaders. */
        private fun Class<*>?.hasMethod(name: String, parameterTypes: List<String>): Boolean = runCatching {
            this != null && generateSequence(this) { it.superclass }.any { type ->
                type.declaredMethods.any { method ->
                    method.name == name &&
                        method.parameterTypes.map { it.name } == parameterTypes
                }
            }
        }.getOrDefault(false)

        private fun Class<*>?.hasField(name: String): Boolean = runCatching {
            this != null && generateSequence(this) { it.superclass }.any { type ->
                type.declaredFields.any { it.name == name }
            }
        }.getOrDefault(false)

        private fun ClassLoader.optional(name: String): Class<*>? =
            runCatching { loadClass(name) }.getOrNull()

        private const val TAG = "reYAMF_Adapters"
    }
}
