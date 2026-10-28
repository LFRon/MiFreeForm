package com.buildsession.betterYAMF.xposed.adapters

import org.junit.Assert.*
import org.junit.Test

class AdapterCatalogTest {
    private val launcher3 = "com.android.launcher3"
    private val pixel = "com.google.android.apps.nexuslauncher"
    private val lineage = PlatformIdentity(product = "lineage_astonc-userdebug")
    private fun select(sdk: Int, pkg: String = launcher3, process: String = pkg,
        platform: PlatformIdentity = lineage, version: Long? = 907L) =
        AdapterCatalog.selectLauncher(sdk, pkg, process, platform, version)
    private fun active(sdk: Int = 37, pkg: String = launcher3, process: String = pkg,
        home: String? = pkg, recents: String? = pkg, version: Long? = 907L,
        platform: PlatformIdentity = lineage) = AdapterCatalog.selectRuntimeLauncher(
            LauncherRuntimeIdentity(sdk, pkg, process, version, home, recents), platform)

    @Test fun knownCurrentCombinationHasOneExplicitEntry() {
        val selected = select(37) as LauncherSelection.Selected
        assertEquals("launcher3/17", selected.adapter.identity.id)
        assertEquals("LineageOS 24.0", selected.adapter.identity.referenceRom)
        assertEquals(AdapterEvidence.DEVICE_BASELINE, selected.adapter.identity.evidence)
        assertNotNull(SystemAdapters.select(37, lineage))
    }

    @Test fun pixelSourceAuditIsNotLabelledAsDeviceValidation() {
        val selected = select(37, pixel) as LauncherSelection.Selected
        assertEquals("pixel/17", selected.adapter.identity.id)
        assertEquals("PixelOS 17", selected.adapter.identity.referenceRom)
        assertEquals(AdapterEvidence.SOURCE_REVIEWED, selected.adapter.identity.evidence)
    }

    @Test fun lineageWithPixelHomeAndQuickstepSelectsPixelInsteadOfTheRomBaseline() {
        val selected = active(pkg = pixel) as LauncherSelection.Selected
        assertEquals("pixel/17", selected.adapter.identity.id)
        // The combination remains source-reviewed; a different ROM does not upgrade its evidence.
        assertEquals(AdapterEvidence.SOURCE_REVIEWED, selected.adapter.identity.evidence)
    }

    @Test fun pixelRomWithLauncher3HomeAndQuickstepSelectsLauncher3() {
        val selected = active(platform = PlatformIdentity(product = "PixelOS_sagit-userdebug"))
            as LauncherSelection.Selected
        assertEquals("launcher3/17", selected.adapter.identity.id)
    }

    @Test fun installedButNonDefaultLauncherCannotEnableAnEntry() {
        for ((host, home) in listOf(launcher3 to pixel, pixel to launcher3, launcher3 to "example.home")) {
            val rejected = active(pkg = host, home = home) as LauncherSelection.Unavailable
            assertTrue(rejected.reason.contains("default Home"))
        }
    }

    @Test fun aHomeShortcutCannotSubstituteForTheActualQuickstepHost() {
        for ((home, recents) in listOf(pixel to launcher3, launcher3 to pixel)) {
            val rejected = active(pkg = home, recents = recents) as LauncherSelection.Unavailable
            assertTrue(rejected.reason.contains("configured Recents host"))
        }
    }

    @Test fun missingOrAmbiguousRolesDoNotGuessTheInstalledLauncher() {
        assertTrue(active(home = null) is LauncherSelection.Unavailable)
        assertTrue(active(recents = null) is LauncherSelection.Unavailable)
        assertTrue(active(home = "android") is LauncherSelection.Unavailable)
    }

    @Test fun currentHomeStillRequiresTheExactSdkApkAndMainProcess() {
        assertTrue(active(sdk = 36, pkg = pixel) is LauncherSelection.Unavailable)
        assertTrue(active(sdk = 38, pkg = pixel) is LauncherSelection.Unavailable)
        assertTrue(active(pkg = pixel, version = 908L) is LauncherSelection.Unavailable)
        assertTrue(active(pkg = pixel, version = null) is LauncherSelection.Unavailable)
        assertTrue(active(pkg = pixel, process = "$pixel:service") is LauncherSelection.Unavailable)
        assertTrue(active(platform = PlatformIdentity(product = "avium_sagit-userdebug"))
            is LauncherSelection.Unavailable)
    }

    @Test fun switchingHomeAwayAndBackNeverChangesTheInstalledUnit() {
        assertTrue(active(pkg = pixel) is LauncherSelection.Selected)
        assertTrue(active(pkg = pixel, home = launcher3) is LauncherSelection.Unavailable)
        val restored = active(pkg = pixel) as LauncherSelection.Selected
        assertEquals("pixel/17", restored.adapter.identity.id)
    }

    @Test fun unauditedPixelApkDoesNotInstallEvenIfThePackageAndSdkMatch() {
        assertTrue(select(37, pixel, version = 908L) is LauncherSelection.Unavailable)
        assertTrue(select(37, pixel, version = null) is LauncherSelection.Unavailable)
    }

    @Test fun pendingOlderTargetsNeverBorrowTheCurrentInstaller() {
        for ((sdk, pkg) in listOf(35 to launcher3, 36 to launcher3, 36 to pixel)) {
            val result = select(sdk, pkg) as LauncherSelection.Unavailable
            assertTrue(result.reason.contains("not been adapted"))
            assertNull(SystemAdapters.forSdk(sdk))
        }
    }

    @Test fun futureAndroidCannotSelectTheLastKnownMechanism() {
        for (sdk in listOf(38, 40)) {
            assertTrue(select(sdk) is LauncherSelection.Unavailable)
            assertNull(SystemAdapters.forSdk(sdk))
        }
    }

    @Test fun aPackageContainingLauncherIsNotACompatibleImplementation() {
        assertTrue(select(37, "example.launcher") is LauncherSelection.Unavailable)
        assertTrue(select(37, "com.motorola.launcher3") is LauncherSelection.Unavailable)
    }

    @Test fun secondaryProcessesCannotInstallDuplicateInputHooks() {
        assertTrue(select(37, process = "$launcher3:service") is LauncherSelection.Unavailable)
    }

    @Test fun visibleExcludedRomMarkersPreventBothProcessInstallers() {
        for (platform in listOf(PlatformIdentity(product = "avium_astonc-userdebug"),
            PlatformIdentity(product = "infinity_astonc-user"),
            PlatformIdentity(fingerprint = "OnePlus/AVIUM/astonc:17/build/id:userdebug/keys"),
            PlatformIdentity(display = "Project Infinity X 17"),
            PlatformIdentity(display = "AviumUI 16.2"))) {
            assertTrue(platform.excluded)
            assertTrue(select(37, platform = platform) is LauncherSelection.Unavailable)
            assertNull(SystemAdapters.select(37, platform))
        }
    }

    @Test fun ordinaryProductNamesAreNotExcludedBySubstring() {
        assertFalse(lineage.excluded)
        assertFalse(PlatformIdentity(product = "aosp_astonc-user").excluded)
        assertFalse(PlatformIdentity(display = "InfinityDisplay").excluded)
    }

    @Test fun privateVersionPropertiesAreNotAnInstallationPrecondition() {
        // Unreadable ROM properties must not silently disable the existing validated mechanism.
        // The selected implementation still needs complete ABI checks, and retains its baseline.
        val selected = select(37, platform = PlatformIdentity()) as LauncherSelection.Selected
        assertEquals("LineageOS 24.0", selected.adapter.identity.referenceRom)
        assertNotNull(SystemAdapters.select(37, PlatformIdentity()))
    }

    @Test fun registeredUnitsHaveUniqueIdsAndExactKeys() {
        val identities = AdapterCatalog.launchers.map { it.identity }
        assertEquals(identities.size, identities.map { it.id }.toSet().size)
        assertEquals(identities.size, identities.map { it.sdk to it.launcherPackage }.toSet().size)
    }
}
