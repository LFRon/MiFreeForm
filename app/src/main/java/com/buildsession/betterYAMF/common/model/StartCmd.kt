package com.buildsession.betterYAMF.common.model

import android.content.ComponentName

data class StartCmd(
    val componentName: ComponentName? = null,
    val userId: Int? = null,
    val taskId: Int? = null,
    val openAsMini: Boolean = false,
    /** 回收区投放时的松手点（屏坐标，-1 = 未知）。小窗登场动画从该点区域飞入磁吸槽位。 */
    val dropX: Int = -1,
    val dropY: Int = -1,
    val sourceBounds: android.graphics.Rect? = null,
    val awaitSourceRelease: Boolean = false,
    val sourcePackage: String? = null,
    val handoffId: Long = 0L,
    val entrySnapshot: EntrySnapshot? = null
)

