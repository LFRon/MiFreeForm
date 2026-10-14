package com.buildsession.betterYAMF.xposed.hook.freeform

object FreeformPolicyAndroid16 : FreeformPolicy {
    override val displayName: String = "Android 16 sync-crop matrix reassert"
    override val configOverrideHintCandidates: List<String> = listOf(
        "com.android.server.wm.TaskFragment\$ConfigOverrideHint"
    )
    override val reassertTaskTransforms: Boolean = true
    override val clearTaskCrop: Boolean = true
}
