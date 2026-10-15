package com.buildsession.betterYAMF.manager.sidebar.core

/** 侧边栏抽象输入（宿主把 View 触摸/定时器翻译成这些事件）。 */
sealed interface SidebarInputEvent {

    sealed interface Pointer : SidebarInputEvent {
        val rawX: Float
        val rawY: Float
    }

    /** 按下；[baseY] = 按下时窗口 params.y（拖动锚点，宿主提供）。 */
    data class Press(override val rawX: Float, override val rawY: Float, val baseY: Int) : Pointer

    data class Move(override val rawX: Float, override val rawY: Float) : Pointer

    data class Up(override val rawX: Float, override val rawY: Float) : Pointer

    /** 长按计时到点（宿主 200ms 定时器触发；仅在仍按住时有效）。 */
    data object HoldElapsed : SidebarInputEvent
}

/** 侧边栏抽象命令（宿主负责落地）。 */
sealed interface SidebarCommand {

    /** 移动到 y；[persist] = 同时持久化位置（拖动中）。 */
    data class MoveToY(val y: Int, val persist: Boolean) : SidebarCommand

    /** 触感反馈。 */
    data object Haptic : SidebarCommand

    /** 打开侧边栏菜单（宿主启动菜单服务）。 */
    data object OpenMenu : SidebarCommand

    /** 收起侧边栏（宿主停止自身）。 */
    data object StopSidebar : SidebarCommand
}
