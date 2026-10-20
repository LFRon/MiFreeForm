package com.buildsession.betterYAMF.xposed.adapters.android16.system

import com.buildsession.betterYAMF.xposed.adapters.common.freeform.FreeformPolicy

object FreeformPolicyAndroid16 : FreeformPolicy {
    override val displayName: String = "Android 16 sync-crop matrix reassert"
    override val configOverrideHintCandidates: List<String> = listOf(
        "com.android.server.wm.TaskFragment\$ConfigOverrideHint"
    )
    override val reassertTaskTransforms: Boolean = true
    override val clearTaskCrop: Boolean = true
}
