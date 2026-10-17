package com.buildsession.betterYAMF.window.backend

import android.app.ActivityManager
import android.content.ComponentName
import android.graphics.Rect
import android.view.MotionEvent
import android.view.Surface

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
     * 会话显示器承载时 = **无杀进程迁移**：任务整体迁移回默认显示器——框架以同进程活动
     * 重建完成回退密度（515）适配；应用进程/服务/最近任务条目全部保留。
     * [toFront]：置顶回前（下拉全屏路径）为 true；关闭小窗为 false——
     * 任务迁移回默认显示器后沉到显示区栈底（后台、留在最近任务），不抢当前前台，
     * 既满足"关闭小窗"语义，也避免表面重建黑帧上屏。
     * [sessionWindowId]：>=0 表示调用方（窗口宿主）显式声明"这是会话窗口的释放"，
     * 并携带窗口 id 供实现层在任务 id 失配时按"该会话显示器上的顶层任务"兜底解析
     * （真机实锤：应用自建新任务后，宿主绑定的旧 taskId 已不存在，迁移被跳过 →
     * 会话显示器释放把应用带走）。
     * 返回是否处理成功（任务已不在会话屏的重复调用按 no-op 成功处理）。
     */
    fun releaseTask(taskId: Int, toFront: Boolean = true, sessionWindowId: Int = -1): Boolean

    /**
     * 几何：按物理矩形（屏幕坐标）更新任务表面的缩放/位置/裁剪/圆角。
     * 实现自行选择承载方式（leash 变换 / 兼容路径），调用方不感知。
     */
    fun applyGeometry(taskId: Int, physicalBounds: Rect)

    /**
     * 静止态几何 = 真实窗口 bounds：应用按窗口尺寸自行重排（不缩放快照、不拉伸）。
     * 与 [applyGeometry]（动画/mini 的缩放态）成对使用：静止用本方法、过渡用前者。
     */
    fun applyRealBounds(taskId: Int, bounds: Rect)

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

    /**
     * 以"小窗会话"语义启动应用（**意图级**：freeform 目标、会话承载细节——如 organizer
     * 承载根绑定——全部由实现负责，根未就绪等降级也在实现内兜底）。
     * **小窗不改应用 DPI**：会话显示器密度跟随默认显示器，跨屏迁移不产生密度差异，
     * 不触发任何密度适配（应用按原 DPI 与目标窗口尺寸自行布局）。
     * **无杀进程优先**：若该应用已有可迁移任务（未运行在任何会话屏上），实现应先就地迁移
     * 该任务进入会话显示器（见 [moveTaskToSession]，应用进程存活、任务身份保留），
     * 迁移失败才退回冷启动——保证"一应用一任务"、不制造重复实例。
     * [windowId]：发起本次启动的宿主窗口标识（实现路由提示——如"显示器承载"实现据此把应用
     * 启动到该窗口的会话显示器上；逻辑层不解释其语义，仅透传）。
     * 返回是否已发出启动/迁移请求；流程层不感知任何系统侧标识与执行细节。
     */
    fun launchAppForSession(component: ComponentName, userId: Int, windowId: Int = -1): Boolean

    /**
     * 把既有任务就地**迁移进入会话显示器**（"重启进入会话"的无杀进程替代）：
     * 应用进程存活、任务身份保留（最近任务条目不消失、应用状态不重置），任务交由框架在
     * 目标显示器重新配置。**小窗不改应用 DPI**：会话显示器密度跟随默认显示器，
     * 迁移只产生窗口尺寸变化（标准 Android 窗口行为），不产生密度差异。
     * [windowId]：发起迁移的宿主窗口标识（实现据此解析会话显示器；语义同 [launchAppForSession]）。
     * 返回是否迁移成功；失败调用方可退回 [launchAppForSession] 冷启动。
     */
    fun moveTaskToSession(taskId: Int, windowId: Int): Boolean

    /**
     * 该任务是否已处于会话承载根之下（出生即入根 / 已认领）。
     * 供流程层区分"我方启动/认领的任务"（可就地认领）与"外部启动的任务"（需重启进入会话）。
     */
    fun isTaskInSessionRoot(taskInfo: ActivityManager.RunningTaskInfo): Boolean

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

    /**
     * 任务的最近快照（= 桌面最近任务卡片同一来源）：
     * 返回**已拷贝为稳定位图、方向已归一**的图（与快照缓冲生命周期解耦，可直接拉伸显示）；
     * null = 无可用快照。用途：小窗登场动画的首帧占位（杜绝"黑屏开场"）。
     */
    fun taskSnapshot(taskId: Int): android.graphics.Bitmap?

    // ==== 会话显示器能力（"虚拟屏承载"实现的实现层契约；逻辑层不调用） ====

    /**
     * 当前实现是否以"会话显示器"承载小窗内容：
     * true = 应用全屏运行在专属虚拟屏上，宿主以视口表面（SurfaceView）呈现内容；
     * false = 传统任务容器承载（承载根 + leash）。
     */
    fun usesDisplaySession(): Boolean

    /** 为宿主窗口创建会话显示器（幂等）；返回 displayId，失败 -1。surface 由宿主稍后交接。 */
    fun attachSessionDisplay(windowId: Int, width: Int, height: Int): Int

    /** 交接/撤销显示器内容表面（SurfaceView 生命周期；null = 表面销毁）。 */
    fun updateSessionDisplaySurface(windowId: Int, surface: Surface?)

    /** 视口尺寸变化：显示器像素跟随内容区（1:1 映射，密度恒定）。 */
    fun resizeSessionDisplay(windowId: Int, width: Int, height: Int)

    /** 释放宿主窗口的会话显示器（内容随显示器移除销毁）。 */
    fun releaseSessionDisplay(windowId: Int)

    /** 宿主窗口的会话显示器 id（无则 -1）。 */
    fun sessionDisplayId(windowId: Int): Int

    /** 任务是否运行在任一会话显示器上（流程分拣用；无会话时恒 false）。 */
    fun isTaskInSessionDisplay(taskInfo: ActivityManager.RunningTaskInfo): Boolean

    /** 壳内视口指针事件 → 会话屏注入（实现负责坐标换算与 displayId 承载）。 */
    fun injectSessionPointer(windowId: Int, event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean

    /** 导航键注入（返回键等路由进会话屏）。 */
    fun injectSessionKey(windowId: Int, keyCode: Int): Boolean
}

/** 后端 → 宿主的回灌事件。 */
interface WindowBackendCallbacks {
    /** 任务已被接管且承载句柄就绪（宿主可摆位）。 */
    fun onTaskAdopted(taskId: Int)

    /** 任务已消失／被系统回收（宿主应销毁对应外壳）。 */
    fun onTaskVanished(taskId: Int)
}
