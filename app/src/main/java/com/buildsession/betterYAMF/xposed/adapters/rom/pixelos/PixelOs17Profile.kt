package com.buildsession.betterYAMF.xposed.adapters.rom.pixelos

import com.buildsession.betterYAMF.xposed.adapters.RomProfile
import com.buildsession.betterYAMF.xposed.adapters.baseline.FrameworkBaseline

/**
 * PixelOS 17 (SDK 37). `frameworks/base` is `PixelOS-AOSP/android_frameworks_base`, i.e. the AOSP
 * branch: no `Task#getLeash`, only the four-argument `InsetsState#calculateInsets`, no
 * `WindowStateAnimator#mSurfaceController`. Its SystemUI matches LineageOS 24.0 member for member,
 * so the shared AOSP bindings apply unchanged.
 *
 * The shipped Home is *not* implied by the name: the manifest builds AOSP `platform/packages/apps/
 * Launcher3` and also carries the Pixel Launcher prebuilt in `vendor/pixel/launcher`, so the
 * launcher adapter still comes from the runtime Home/Recents detection.
 */
internal val PixelOs17Profile = RomProfile(
    id = "pixelos/17",
    sdk = 37,
    baseline = FrameworkBaseline.AOSP,
    match = { identity -> PIXELOS.containsMatchIn(identity.labels) },
    shippedLauncher = null,
    notes = "AOSP branch; Launcher package must be detected at runtime",
)

private val PIXELOS = Regex("(?i)(?:^|[^a-z0-9])pixel[ _-]?os")
