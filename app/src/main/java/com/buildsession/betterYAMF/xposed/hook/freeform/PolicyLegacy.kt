package com.buildsession.betterYAMF.xposed.hook.freeform

object FreeformPolicyLegacy : FreeformPolicy {
    override val displayName: String = "Android 13-15 legacy matrix"
    override val configOverrideHintCandidates: List<String> = listOf(
        "com.android.server.wm.TaskFragment\$ConfigOverrideHint"
    )
    override val reassertTaskTransforms: Boolean = false
    override val clearTaskCrop: Boolean = false
}
