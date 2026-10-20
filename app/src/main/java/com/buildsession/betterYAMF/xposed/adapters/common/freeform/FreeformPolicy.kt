package com.buildsession.betterYAMF.xposed.adapters.common.freeform

interface FreeformPolicy {
    val displayName: String
    val configOverrideHintCandidates: List<String>
    val reassertTaskTransforms: Boolean
    val clearTaskCrop: Boolean
}
