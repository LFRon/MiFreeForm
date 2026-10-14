package com.buildsession.betterYAMF.xposed.hook.freeform

interface FreeformPolicy {
    val displayName: String
    val configOverrideHintCandidates: List<String>
    val reassertTaskTransforms: Boolean
    val clearTaskCrop: Boolean
}

object FreeformPolicies {
    private const val SDK_ANDROID_16 = 36
    private const val SDK_ANDROID_17 = 37

    fun forSdk(sdkInt: Int): FreeformPolicy = when {
        sdkInt >= SDK_ANDROID_17 -> FreeformPolicyAndroid17
        sdkInt >= SDK_ANDROID_16 -> FreeformPolicyAndroid16
        else -> FreeformPolicyLegacy
    }
}
