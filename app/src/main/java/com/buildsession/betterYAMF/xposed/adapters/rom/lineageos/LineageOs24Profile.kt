package com.buildsession.betterYAMF.xposed.adapters.rom.lineageos

import com.buildsession.betterYAMF.xposed.adapters.RomProfile
import com.buildsession.betterYAMF.xposed.adapters.baseline.FrameworkBaseline

/**
 * LineageOS 24.0 (SDK 37). Verified against `lineage-24.0` sources: AOSP framework branch, no
 * `Task#getLeash`, only the four-argument `InsetsState#calculateInsets`, no
 * `WindowStateAnimator#mSurfaceController`, `ShadeController#collapseOnMainThread` present, and
 * `DisplayPolicy#getNavigationBar()` without a matching `getStatusBar()`.
 */
internal val LineageOs24Profile = RomProfile(
    id = "lineageos/24",
    sdk = 37,
    baseline = FrameworkBaseline.AOSP,
    match = { identity -> LINEAGE.containsMatchIn(identity.labels) },
    shippedLauncher = "com.android.launcher3",
    notes = "AOSP branch; the bundled Quickstep Launcher3 hosts Home and Recents",
)

private val LINEAGE = Regex("(?i)(?:^|[^a-z0-9])lineage")
