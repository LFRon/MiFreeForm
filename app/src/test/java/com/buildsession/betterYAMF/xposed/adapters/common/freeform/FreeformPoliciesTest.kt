package com.buildsession.betterYAMF.xposed.adapters.common.freeform

import com.buildsession.betterYAMF.xposed.adapters.SurfacePolicies
import com.buildsession.betterYAMF.xposed.adapters.android16.system.FreeformPolicyAndroid16
import com.buildsession.betterYAMF.xposed.adapters.android17.system.FreeformPolicyAndroid17
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeformPoliciesTest {

    @Test
    fun sdk33to35RoutesToLegacyPolicy() {
        for (sdk in 33..35) {
            assertSame(FreeformPolicyLegacy, SurfacePolicies.forSdk(sdk))
        }
    }

    @Test
    fun sdk36RoutesToAndroid16Policy() {
        assertSame(FreeformPolicyAndroid16, SurfacePolicies.forSdk(36))
    }

    @Test
    fun onlySdk37RoutesToAndroid17Policy() {
        assertSame(FreeformPolicyAndroid17, SurfacePolicies.forSdk(37))
        assertNull(SurfacePolicies.forSdk(38))
        assertNull(SurfacePolicies.forSdk(40))
        assertNull(SurfacePolicies.forSdk(32))
    }

    @Test
    fun legacyPolicyKeepsOriginalBehavior() {
        val policy = requireNotNull(SurfacePolicies.forSdk(33))
        assertEquals(
            listOf("com.android.server.wm.TaskFragment\$ConfigOverrideHint"),
            policy.configOverrideHintCandidates
        )
        assertFalse(policy.reassertTaskTransforms)
        assertFalse(policy.clearTaskCrop)
    }

    @Test
    fun android16PolicyAddsReassertAndCropClear() {
        val policy = requireNotNull(SurfacePolicies.forSdk(36))
        assertEquals(
            listOf("com.android.server.wm.TaskFragment\$ConfigOverrideHint"),
            policy.configOverrideHintCandidates
        )
        assertTrue(policy.reassertTaskTransforms)
        assertTrue(policy.clearTaskCrop)
    }

    @Test
    fun android17PolicyPrefersMovedHintClassAndFallsBack() {
        val policy = requireNotNull(SurfacePolicies.forSdk(37))
        assertEquals(
            listOf(
                "com.android.server.wm.AppCompatSandboxingPolicy\$ConfigOverrideHint",
                "com.android.server.wm.TaskFragment\$ConfigOverrideHint"
            ),
            policy.configOverrideHintCandidates
        )
        assertTrue(policy.reassertTaskTransforms)
        assertTrue(policy.clearTaskCrop)
    }

    @Test
    fun everyPolicyHasDistinctDisplayName() {
        val names = listOf(
            requireNotNull(SurfacePolicies.forSdk(33)).displayName,
            requireNotNull(SurfacePolicies.forSdk(36)).displayName,
            requireNotNull(SurfacePolicies.forSdk(37)).displayName
        )
        assertEquals(names.size, names.toSet().size)
    }
}
