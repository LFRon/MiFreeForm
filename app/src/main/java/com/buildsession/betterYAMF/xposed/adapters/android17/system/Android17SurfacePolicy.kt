package com.buildsession.betterYAMF.xposed.adapters.android17.system

import com.buildsession.betterYAMF.xposed.adapters.common.freeform.FreeformPolicy

object FreeformPolicyAndroid17 : FreeformPolicy {
    override val displayName: String = "Android 17 hint moved to AppCompatSandboxingPolicy"
    override val configOverrideHintCandidates: List<String> = listOf(
        "com.android.server.wm.AppCompatSandboxingPolicy\$ConfigOverrideHint",
        "com.android.server.wm.TaskFragment\$ConfigOverrideHint"
    )
    override val reassertTaskTransforms: Boolean = true
    override val clearTaskCrop: Boolean = true
}
