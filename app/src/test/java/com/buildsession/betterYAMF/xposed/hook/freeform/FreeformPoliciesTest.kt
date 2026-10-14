package com.buildsession.betterYAMF.xposed.hook.freeform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeformPoliciesTest {

    @Test
    fun sdk33to35RoutesToLegacyPolicy() {
        for (sdk in 33..35) {
            assertSame(FreeformPolicyLegacy, FreeformPolicies.forSdk(sdk))
        }
    }

    @Test
    fun sdk36RoutesToAndroid16Policy() {
        assertSame(FreeformPolicyAndroid16, FreeformPolicies.forSdk(36))
    }

    @Test
    fun sdk37AndAboveRoutesToAndroid17Policy() {
        assertSame(FreeformPolicyAndroid17, FreeformPolicies.forSdk(37))
        assertSame(FreeformPolicyAndroid17, FreeformPolicies.forSdk(40))
    }

    @Test
    fun legacyPolicyKeepsOriginalBehavior() {
        val policy = FreeformPolicies.forSdk(33)
        assertEquals(
            listOf("com.android.server.wm.TaskFragment\$ConfigOverrideHint"),
            policy.configOverrideHintCandidates
        )
        assertFalse(policy.reassertTaskTransforms)
        assertFalse(policy.clearTaskCrop)
    }

    @Test
    fun android16PolicyAddsReassertAndCropClear() {
        val policy = FreeformPolicies.forSdk(36)
        assertEquals(
            listOf("com.android.server.wm.TaskFragment\$ConfigOverrideHint"),
            policy.configOverrideHintCandidates
        )
        assertTrue(policy.reassertTaskTransforms)
        assertTrue(policy.clearTaskCrop)
    }

    @Test
    fun android17PolicyPrefersMovedHintClassAndFallsBack() {
        val policy = FreeformPolicies.forSdk(37)
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
            FreeformPolicies.forSdk(33).displayName,
            FreeformPolicies.forSdk(36).displayName,
            FreeformPolicies.forSdk(37).displayName
        )
        assertEquals(names.size, names.toSet().size)
    }
}
