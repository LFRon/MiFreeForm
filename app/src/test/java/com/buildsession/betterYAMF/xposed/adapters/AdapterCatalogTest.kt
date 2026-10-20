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

    @Test fun knownCurrentCombinationHasOneExplicitEntry() {
        val selected = select(37) as LauncherSelection.Selected
        assertEquals("android17/launcher3", selected.adapter.identity.id)
        assertEquals("LineageOS 24.0", selected.adapter.identity.referenceRom)
        assertEquals(AdapterEvidence.DEVICE_BASELINE, selected.adapter.identity.evidence)
        assertNotNull(SystemAdapters.select(37, lineage))
    }

    @Test fun pixelSourceAuditIsNotLabelledAsDeviceValidation() {
        val selected = select(37, pixel) as LauncherSelection.Selected
        assertEquals("android17/pixelLauncher", selected.adapter.identity.id)
        assertEquals("PixelOS 17", selected.adapter.identity.referenceRom)
        assertEquals(AdapterEvidence.SOURCE_REVIEWED, selected.adapter.identity.evidence)
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
