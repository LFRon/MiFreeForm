package com.buildsession.betterYAMF.xposed.hook.system

import android.graphics.Rect
import android.view.Display
import com.buildsession.betterYAMF.xposed.utils.Instances
import de.robv.android.xposed.XposedHelpers

/**
 * 系统栏度量适配器（系统内部细节收口点）：
 * 返回**当前真实**的状态栏 / 导航栏高度（px）——宿主与核心策略只消费数值，
 * 绝不写死、不碰 WMS 内部。
 *
 * 关键源码事实（LineageOS 24.0）：
 * - 主路径 = 系统栏窗口实值：`WindowManagerService.mRoot`（WindowManagerService.java:733）
 *   → `RootWindowContainer#getDisplayContent(int)`（:1251，包私有）
 *   → `DisplayContent#getDisplayPolicy()`（DisplayContent.java:1907，包私有）
 *   → `DisplayPolicy#getStatusBar()`（:1400）/ `#getNavigationBar()`（:1408，包私有）
 *   → `WindowState#getFrame()` 高度——随旋转/异形屏/挖孔/导航方式自适应，本进程内最权威；
 * - 兜底 = 框架资源 dimen `status_bar_height` / `navigation_bar_height`（主路径异常时）。
 *
 * 不做缓存：调用点仅在 mini 触点/投放起点/展开回位等低频决策处，且旋转后高度可能变化，
 * 每次动态读取保证与当前显示配置一致。
 */
object SystemBarMetrics {

    /** 真实状态栏高度（px）；全部路径解析失败返回 0（调用方自行决定降级策略）。 */
    fun statusBarHeightPx(): Int =
        windowHeight("getStatusBar") ?: resourceHeight("status_bar_height") ?: 0

    /** 真实导航栏高度（px）；全部路径解析失败返回 0（调用方自行决定降级策略）。 */
    fun navigationBarHeightPx(): Int =
        windowHeight("getNavigationBar") ?: resourceHeight("navigation_bar_height") ?: 0

    /** 取 DisplayPolicy 并读系统栏窗口（getStatusBar / getNavigationBar）的 frame 高度。 */
    private fun windowHeight(accessor: String): Int? = runCatching {
        val policy = displayPolicy() ?: return null
        val window = XposedHelpers.callMethod(policy, accessor) ?: return null
        val frame = XposedHelpers.callMethod(window, "getFrame") as? Rect ?: return null
        frame.height().takeIf { it > 0 }
    }.getOrNull()

    private fun displayPolicy(): Any? = runCatching {
        val wms = XposedHelpers.getObjectField(Instances.activityTaskManager, "mWindowManager")
            ?: return null
        val root = XposedHelpers.getObjectField(wms, "mRoot") ?: return null
        val displayContent = XposedHelpers.callMethod(root, "getDisplayContent", Display.DEFAULT_DISPLAY)
            ?: return null
        XposedHelpers.callMethod(displayContent, "getDisplayPolicy")
    }.getOrNull()

    private fun resourceHeight(name: String): Int? = runCatching {
        val res = android.content.res.Resources.getSystem()
        val id = res.getIdentifier(name, "dimen", "android")
        if (id == 0) null else res.getDimensionPixelSize(id).takeIf { it > 0 }
    }.getOrNull()
}
