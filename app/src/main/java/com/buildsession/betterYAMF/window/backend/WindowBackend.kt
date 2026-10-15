package com.buildsession.betterYAMF.window.backend

import android.app.ActivityManager
import android.graphics.Rect

/**
 * 系统窗口后端端口：宿主/流程 ↔ 系统侧"任务级窗口语义"的唯一入口。
 *
 * 设计边界（与 `window/core` 的 M1 抽象对应）：
 * - 核心（window/core）：只认抽象输入 → 抽象命令，零平台依赖；
 * - 宿主（window/host）：采集具体输入、执行命令、渲染外壳；
 * - 后端（本接口）：任务级窗口语义——认领/释放（organizer/leash、freeform、
 *   承载根）、几何（缩放/位置/裁剪/圆角）、焦点让渡、置前、可见性。
 *
 * 实现可替换：当前为 `SmoothTaskOrganizer`（TaskOrganizer 悬浮根 + leash）；
 * 未来迁移 libxposed API 版本或新增别的窗口承载方式时，只替换实现类，
 * 宿主与流程代码不动。**调用方不得绕过本接口直接操作
 * WindowOrganizerController / Task / SurfaceControl 等系统细节。**
 */
interface WindowBackend {

    /** 注册回灌回调并完成系统侧初始化（幂等）。 */
    fun initialize(callbacks: WindowBackendCallbacks)

    /**
     * 打开：把任务认领为平滑窗口（freeform + 承载根 + 变换接管），
     * 置前并恢复可聚焦。返回是否成功认领。
     */
    fun openTask(taskId: Int): Boolean

    /**
     * 关闭/释放：任务回到全屏并交还系统管理（含表面变换复位与焦点恢复）。
     * [toFront]：置顶回前（下拉全屏路径）为 true；关闭小窗为 false——
     * 任务复位为全屏后沉到原父级栈底（后台、留在最近任务），不抢当前前台，
     * 既满足"关闭小窗"语义，也避免表面重建黑帧上屏。
     * 返回是否处理成功。
     */
    fun releaseTask(taskId: Int, toFront: Boolean = true): Boolean

    /**
     * 几何：按物理矩形（屏幕坐标）更新任务表面的缩放/位置/裁剪/圆角。
     * 实现自行选择承载方式（leash 变换 / 兼容路径），调用方不感知。
     */
    fun applyGeometry(taskId: Int, physicalBounds: Rect)

    /** 稳态重施加（过渡结束后防系统重置冲掉变换；几何未就绪时 no-op）。 */
    fun reapplyGeometry(taskId: Int)

    /** 确保任务表面可见（几何未知的首帧兜底）。 */
    fun ensureTaskVisible(taskId: Int)

    /** 焦点让渡（浮窗语义：可聚焦 / 让焦给下方应用）。 */
    fun setTaskFocusable(taskId: Int, focusable: Boolean)

    /** 置前（回置顶等）。返回系统调用是否成功。 */
    fun bringTaskToFront(taskId: Int): Boolean

    /**
     * 是否已获得系统级置顶身份（承载根 always-on-top）：
     * 生效时宿主不得再做任何"抢层/回置顶"动作（层级由系统排序规则保证），
     * 仅保留焦点/几何等语义维护。
     */
    fun isAlwaysOnTopActive(): Boolean

    /** 任务是否由本后端接管（窗口语义成立，可施加几何/焦点操作）。 */
    fun isTaskManaged(taskId: Int): Boolean

    /**
     * 受管任务集合（承载根 + 已认领子任务）：
     * 供流程层做跨进程同步（如 Launcher 手势目标豁免）；未接管的实现返回空数组。
     */
    fun managedTaskIds(): IntArray

    /** 最近一次落地的任务物理矩形（会话内）；未落地/已释放返回 null。 */
    fun currentGeometry(taskId: Int): Rect?

    /** 任务消失等场景清理本后端的残留会话状态（几何缓存等）；幂等。 */
    fun forgetTask(taskId: Int)

    /**
     * 关闭前主动刷新任务快照（必须在应用仍完整可见时调用）。
     * 背景：关闭流程会先把任务表面收缩/隐藏，再沉入后台；系统在"后台化"时刻自动抓的
     * 快照恰好是空帧 → 最近任务卡片"白屏"。实现负责用当前完整画面刷新快照。
     */
    fun requestSnapshotRefresh(taskId: Int)

    /** 任务当前窗口模式（WINDOWING_MODE_*）；流程事件分拣用，反射细节由实现收口。 */
    fun windowingModeOf(taskInfo: ActivityManager.RunningTaskInfo): Int

    /** 该任务是否属于 Home（宿主置顶/让路判定用；实现封装系统判定细节）。 */
    fun isHomeTask(taskInfo: ActivityManager.RunningTaskInfo): Boolean

    /** 是否正有手指按在屏幕上（用于把回置顶推迟到手势结束）。 */
    fun isTouchActive(): Boolean

    /** 抑制最近任务手势（Launcher 协作信号；由系统侧接收器转发）。 */
    fun suppressRecentsGestureFor(durationMs: Long)

    /** 最近任务手势抑制截止时间戳（uptimeMillis；0 = 未抑制）。 */
    fun recentsGestureSuppressUntilMs(): Long

    /** 注入 HOME 键（"从最近任务打开即小窗"后关闭最近任务界面）。 */
    fun injectHomeKey(): Boolean
}

/** 后端 → 宿主的回灌事件。 */
interface WindowBackendCallbacks {
    /** 任务已被接管且承载句柄就绪（宿主可摆位）。 */
    fun onTaskAdopted(taskId: Int)

    /** 任务已消失／被系统回收（宿主应销毁对应外壳）。 */
    fun onTaskVanished(taskId: Int)
}
