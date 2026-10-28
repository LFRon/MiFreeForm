package com.buildsession.betterYAMF.xposed.adapters

import com.buildsession.betterYAMF.xposed.adapters.baseline.FrameworkBaseline
import com.buildsession.betterYAMF.xposed.adapters.baseline.FrameworkShape

/**
 * A distribution family plus its framework generation, e.g. `lineageos/24`.
 *
 * This layer is advisory and fail-open: it never decides eligibility, it only corrects a probed
 * [FrameworkShape] fact that a vendor patch made misleading and records what the distribution is
 * expected to ship. An unknown or unreviewed ROM simply runs with the probed baseline.
 */
internal data class RomProfile(
    val id: String,
    val sdk: Int,
    val baseline: FrameworkBaseline,
    /** Distribution label facts, matched against the public Build fields only. */
    val match: (PlatformIdentity) -> Boolean,
    /** Documented shipped Home, used for logs and review notes; runtime detection stays authoritative. */
    val shippedLauncher: String?,
    val notes: String,
    val overrides: FrameworkOverrides = FrameworkOverrides.NONE,
) {
    fun applyTo(shape: FrameworkShape): FrameworkShape = overrides.applyTo(shape)

    /** Overrides exist because a vendor patch can add or remove one member independently of the
     * branch. `null` keeps the probed value, so a ROM only states what it actually changes.
     */
    data class FrameworkOverrides(
        val baseline: FrameworkBaseline? = null,
        val taskLeash: Boolean? = null,
        val insetsIgnoreVisibility: Boolean? = null,
        val displayPolicyBarGetters: Boolean? = null,
        val shadeCollapseOnMainThread: Boolean? = null,
        val windowSurfaceController: Boolean? = null,
    ) {
        fun applyTo(shape: FrameworkShape): FrameworkShape = FrameworkShape(
            baseline = baseline ?: shape.baseline,
            taskLeash = taskLeash ?: shape.taskLeash,
            insetsIgnoreVisibility = insetsIgnoreVisibility ?: shape.insetsIgnoreVisibility,
            displayPolicyBarGetters = displayPolicyBarGetters ?: shape.displayPolicyBarGetters,
            shadeCollapseOnMainThread = shadeCollapseOnMainThread ?: shape.shadeCollapseOnMainThread,
            windowSurfaceController = windowSurfaceController ?: shape.windowSurfaceController,
        )

        companion object {
            val NONE = FrameworkOverrides()
        }
    }
}

/** Reviewed distributions. Adding a ROM means adding one profile here, never touching a binding. */
internal object RomProfiles {
    private val all: List<RomProfile> = listOf(
        com.buildsession.betterYAMF.xposed.adapters.rom.lineageos.LineageOs24Profile,
        com.buildsession.betterYAMF.xposed.adapters.rom.pixelos.PixelOs17Profile,
        com.buildsession.betterYAMF.xposed.adapters.rom.pixel.Pixel17Profile,
    )

    /** Exact SDK + public-label match; a missing match is the supported "unknown ROM" case. */
    fun detect(sdk: Int, identity: PlatformIdentity): RomProfile? =
        all.firstOrNull { it.sdk == sdk && it.match(identity) }
}
