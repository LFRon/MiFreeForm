package com.buildsession.betterYAMF.xposed.adapters.rom.pixel

import com.buildsession.betterYAMF.xposed.adapters.RomProfile
import com.buildsession.betterYAMF.xposed.adapters.baseline.FrameworkBaseline

/**
 * Google stock Pixel 17 (SDK 37). Its framework is the Google branch: `Task#getLeash`,
 * `InsetsState#calculateInsets(frame, types, ignoreVisibility)` and
 * `WindowStateAnimator#mSurfaceController` exist, while `ShadeController#collapseOnMainThread` is
 * gone. The shipped Home is Pixel Launcher.
 *
 * Matching is deliberately narrow — only a `google/` fingerprint identifies the stock build. A ROM
 * that keeps the vendor fingerprint falls back to the probed shape, which is authoritative anyway.
 */
internal val Pixel17Profile = RomProfile(
    id = "pixel/17",
    sdk = 37,
    baseline = FrameworkBaseline.GOOGLE,
    match = { identity -> GOOGLE.containsMatchIn(identity.fingerprint) },
    shippedLauncher = "com.google.android.apps.nexuslauncher",
    notes = "Google branch; Pixel Launcher hosts Home and Recents",
)

private val GOOGLE = Regex("(?i)^google/")
