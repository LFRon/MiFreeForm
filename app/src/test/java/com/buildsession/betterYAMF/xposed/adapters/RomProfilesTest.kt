package com.buildsession.betterYAMF.xposed.adapters

import com.buildsession.betterYAMF.xposed.adapters.baseline.FrameworkBaseline
import com.buildsession.betterYAMF.xposed.adapters.baseline.FrameworkShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomProfilesTest {
    private val shape = FrameworkShape(FrameworkBaseline.AOSP, taskLeash = false,
        insetsIgnoreVisibility = false, displayPolicyBarGetters = false,
        shadeCollapseOnMainThread = true, windowSurfaceController = false)

    @Test fun reviewedDistributionsAreRecognisedFromPublicLabelsOnly() {
        assertEquals("lineageos/24", detect(PlatformIdentity(product = "lineage_astonc-userdebug")))
        assertEquals("lineageos/24", detect(PlatformIdentity(display = "lineage_astonc-userdebug 17 BP1A")))
        assertEquals("pixelos/17", detect(PlatformIdentity(product = "PixelOS_astonc-userdebug")))
        assertEquals("pixel/17", detect(PlatformIdentity(
            fingerprint = "google/astonc/astonc:17/BP1A/1:user/release-keys")))
    }

    @Test fun unreviewedBuildsKeepWorkingWithoutAProfile() {
        for (identity in listOf(PlatformIdentity(), PlatformIdentity(product = "aosp_astonc-user"),
            PlatformIdentity(product = "raven"), PlatformIdentity(display = "CINNAMONBUN"),
            PlatformIdentity(fingerprint = "vendor/astonc/astonc:17/BP1A/1:userdebug/test-keys"))) {
            assertNull(RomProfiles.detect(37, identity))
        }
    }

    @Test fun aProfileNeverAppliesToAnotherSdk() {
        val lineage = PlatformIdentity(product = "lineage_astonc-userdebug")
        assertNull(RomProfiles.detect(36, lineage))
        assertNull(RomProfiles.detect(38, lineage))
    }

    @Test fun aospDistributionsAndTheGoogleBranchDeclareTheirReviewedBaseline() {
        assertEquals(FrameworkBaseline.AOSP, RomProfiles.detect(37,
            PlatformIdentity(product = "lineage_astonc"))?.baseline)
        assertEquals(FrameworkBaseline.AOSP, RomProfiles.detect(37,
            PlatformIdentity(product = "PixelOS_astonc"))?.baseline)
        assertEquals(FrameworkBaseline.GOOGLE, RomProfiles.detect(37,
            PlatformIdentity(fingerprint = "google/astonc/astonc:17/BP1A/1:user/release-keys"))?.baseline)
    }

    @Test fun everyProfileCarriesAReviewNote() {
        for (id in listOf("lineageos/24", "pixelos/17", "pixel/17")) {
            val identity = when (id) {
                "lineageos/24" -> PlatformIdentity(product = "lineage_astonc")
                "pixelos/17" -> PlatformIdentity(product = "PixelOS_astonc")
                else -> PlatformIdentity(fingerprint = "google/astonc/astonc:17/BP1A/1:user")
            }
            val profile = requireNotNull(RomProfiles.detect(37, identity)) { id }
            assertEquals(id, profile.id)
            assertEquals(37, profile.sdk)
            assertTrue(profile.notes.isNotBlank())
        }
    }

    @Test fun noOverrideLeavesTheProbedShapeUntouched() {
        val profile = requireNotNull(RomProfiles.detect(37, PlatformIdentity(product = "lineage_astonc")))
        assertEquals(shape, profile.applyTo(shape))
    }

    @Test fun anOverrideCorrectsOnlyTheFactItNames() {
        val corrected = RomProfile.FrameworkOverrides(taskLeash = true).applyTo(shape)
        assertTrue(corrected.taskLeash)
        assertEquals(shape.copy(taskLeash = true), corrected)

        val relabelled = RomProfile.FrameworkOverrides(baseline = FrameworkBaseline.GOOGLE).applyTo(shape)
        assertEquals(FrameworkBaseline.GOOGLE, relabelled.baseline)
        assertFalse(relabelled.taskLeash)
    }

    private fun detect(identity: PlatformIdentity, sdk: Int = 37): String? =
        RomProfiles.detect(sdk, identity)?.id
}
