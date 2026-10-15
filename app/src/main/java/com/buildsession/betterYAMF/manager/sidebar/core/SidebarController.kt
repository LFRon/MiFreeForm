package com.buildsession.betterYAMF.manager.sidebar.core

/**
 * 侧边栏交互逻辑核心（纯 Kotlin，零 Android 依赖，JVM 单测口径与 `window/core` 一致）。
 *
 * 逐条固化的既有语义（行为对账来源：`分阶段计划/10-app-side-logic-layer.md` §4）：
 * 1. 按下记录锚点（baseY / rawY），不产生命令；宿主启动 200ms 长按计时；
 * 2. 长按到点（仍按住）→ 触感 + 进入"可拖动"态；此后 Move 按"锚点 + Δy"移动并持久化；
 * 3. 未进入拖动态时 Move 只更新滑动采样（swipeX），不产生移动命令；
 * 4. 抬手：rawY 与按下完全一致 ⇒ 点按（触感 + 开菜单 + 收起）；
 *    swipeX > 200（绝对 rawX，现状阈值语义）⇒ 同一组命令并清零 swipeX；
 *    两个条件可叠加（与现状 if 顺序一致）；
 * 5. swipeX 跨手势残留语义保留（按下不清零，只在滑动分支命中时清零）。
 */
class SidebarController {

    private var pressed = false
    private var movable = false
    private var anchorY = 0
    private var pressRawY = 0f
    private var swipeX = 0

    /** 是否处于"按住且已进入拖动"。 */
    val dragging: Boolean get() = pressed && movable

    fun onInput(event: SidebarInputEvent): List<SidebarCommand> = when (event) {
        is SidebarInputEvent.Press -> onPress(event)
        is SidebarInputEvent.Move -> onMove(event)
        is SidebarInputEvent.Up -> onUp(event)
        SidebarInputEvent.HoldElapsed -> onHoldElapsed()
    }

    private fun onPress(event: SidebarInputEvent.Press): List<SidebarCommand> {
        pressed = true
        movable = false
        anchorY = event.baseY
        pressRawY = event.rawY
        return emptyList()
    }

    private fun onHoldElapsed(): List<SidebarCommand> {
        if (!pressed || movable) return emptyList()
        movable = true
        return listOf(SidebarCommand.Haptic)
    }

    private fun onMove(event: SidebarInputEvent.Move): List<SidebarCommand> {
        swipeX = event.rawX.toInt()
        if (!pressed || !movable) return emptyList()
        val y = anchorY + (event.rawY - pressRawY).toInt()
        return listOf(SidebarCommand.MoveToY(y, persist = true))
    }

    private fun onUp(event: SidebarInputEvent.Up): List<SidebarCommand> {
        pressed = false
        movable = false
        val out = mutableListOf<SidebarCommand>()
        if (pressRawY == event.rawY) {
            out += SidebarCommand.Haptic
            out += SidebarCommand.OpenMenu
            out += SidebarCommand.StopSidebar
        }
        if (swipeX > SWIPE_X_THRESHOLD) {
            out += SidebarCommand.Haptic
            out += SidebarCommand.OpenMenu
            out += SidebarCommand.StopSidebar
            swipeX = 0
        }
        return out
    }

    private companion object {
        /** 现状阈值：绝对 rawX 大于 200 视为滑动（浮动语义如实保留）。 */
        const val SWIPE_X_THRESHOLD = 200
    }
}
