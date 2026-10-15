package com.buildsession.betterYAMF.common.model

import android.content.ComponentName

data class StartCmd(
    val componentName: ComponentName? = null,
    val userId: Int? = null,
    val taskId: Int? = null,
    val openAsMini: Boolean = false
)

