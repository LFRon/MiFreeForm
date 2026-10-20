package com.buildsession.betterYAMF.xposed.adapters

import com.buildsession.betterYAMF.xposed.adapters.common.freeform.FreeformPolicy
import com.buildsession.betterYAMF.xposed.adapters.common.freeform.FreeformPolicyLegacy
import com.buildsession.betterYAMF.xposed.adapters.android16.system.FreeformPolicyAndroid16
import com.buildsession.betterYAMF.xposed.adapters.android17.system.FreeformPolicyAndroid17

/** Retained surface-policy definitions are not complete task-backend support declarations. */
internal object SurfacePolicies {
    fun forSdk(sdk: Int): FreeformPolicy? = when (sdk) {
        33, 34, 35 -> FreeformPolicyLegacy
        36 -> FreeformPolicyAndroid16
        37 -> FreeformPolicyAndroid17
        else -> null
    }
}
