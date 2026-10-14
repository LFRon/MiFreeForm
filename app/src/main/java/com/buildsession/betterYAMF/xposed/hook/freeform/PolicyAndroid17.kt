package com.buildsession.betterYAMF.xposed.hook.freeform

object FreeformPolicyAndroid17 : FreeformPolicy {
    override val displayName: String = "Android 17 hint moved to AppCompatSandboxingPolicy"
    override val configOverrideHintCandidates: List<String> = listOf(
        "com.android.server.wm.AppCompatSandboxingPolicy\$ConfigOverrideHint",
        "com.android.server.wm.TaskFragment\$ConfigOverrideHint"
    )
    override val reassertTaskTransforms: Boolean = true
    override val clearTaskCrop: Boolean = true
}
