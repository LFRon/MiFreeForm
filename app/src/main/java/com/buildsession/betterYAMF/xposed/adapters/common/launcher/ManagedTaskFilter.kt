package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.TRUSTED_SENDER_PERMISSION
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookAfter
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookBefore
import java.util.concurrent.ConcurrentHashMap

/**
 * Launcher 侧"手势目标豁免"组件（M6 批次2）。
 *
 * 语义：受管浮窗任务（承载根 + 已认领子任务）永不被手势的目标判定选中——
 * 对齐系统对 pinned 任务的豁免（`TopTaskTracker` 中的 pinned 过滤即模板），
 * 使上滑/快速切换等手势始终以"下方应用"为目标，浮窗只受自身触摸与模块命令驱动。
 *
 * 职责边界：
 * - 数据：来自 system_server 广播（`YAMFManager.broadcastManagedTaskIds`，集合内容
 *   出自 `WindowBackend.managedTaskIds()` 端口）；本组件只消费，不生产；
 * - 过滤：作用于 Launcher 进程内 `TopTaskTracker` 的两条数据路径
 *   （shell 路径 `onVisibleTasksChanged` / legacy 路径 `getCachedTopTask`）；
 * - 宿主（`HookLauncher`）只负责安装本组件（接收器注册 + hook 安装）。
 *
 * 版本边界：`TopTaskTracker` 属 Launcher/ROM 实现细节，本组件通过初始化时核对的签名与字段访问；
 * 未来 Launcher 结构变化时只调整本组件，system_server 侧（端口/流程）不受影响。
 */
internal object ManagedTaskFilter {
    private const val TAG = "BetterYAMF_TaskFilter"

    private val managedTaskIds = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var receiverRegistered = false
    @Volatile
    private var filterAccess: QuickstepTaskFilterAccess? = null
    @Volatile
    private var tracker: Any? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ids = intent.getIntArrayExtra(Protocol.EXTRA_MANAGED_TASK_IDS) ?: return
            managedTaskIds.clear()
            ids.forEach { managedTaskIds.add(it) }
            // Pixel Launcher 17/907 does not update TopTaskTracker from onVisibleTasksChanged.
            // When a task leaves managed ownership for fullscreen, the legacy ordered-task cache
            // can still contain its previous home entry; without this invalidation the next
            // swipe-up reads that stale task and never leaves the fullscreen app.
            filterAccess?.let { access -> tracker?.let { target ->
                runCatching { access.invalidateTopTaskCache(target) }
                    .onFailure { log(TAG, "invalidate top task cache failed", it) }
            } }
            log(TAG) { "managed tasks updated: [${ids.joinToString()}]" }
        }
    }

    /** 注册集合同步接收器并主动查询一次当前值（Launcher 服务启动时调用；幂等）。 */
    fun ensureReceiverRegistered(context: Context) {
        // This receiver and its registration flag belong to the process. ActivityThread cleans
        // Service-context receivers on destruction, even while the process and this flag survive.
        val application = context.applicationContext
        runCatching {
            if (!receiverRegistered) {
                application.registerReceiver(
                    receiver,
                    IntentFilter(Protocol.ACTION_MANAGED_TASKS),
                    TRUSTED_SENDER_PERMISSION,
                    null,
                    Context.RECEIVER_EXPORTED
                )
                receiverRegistered = true
            }
            application.sendBroadcast(Intent(Protocol.ACTION_QUERY_MANAGED_TASKS).setPackage("android")
                .putExtra(Protocol.EXTRA_SOURCE_PACKAGE, application.packageName)
                .putExtra(Protocol.EXTRA_SOURCE_EPOCH, com.buildsession.betterYAMF.xposed.adapters.common.system.EntryProtocolIdentity.epoch))
            log(TAG) { "managed tasks receiver registered" }
        }.onFailure { log(TAG, "managed tasks receiver setup failed", it) }
    }

    /** 安装 TopTaskTracker 双路径过滤（Launcher 进程 handleLoadPackage 时调用）。 */
    fun installTopTaskFilters(access: QuickstepTaskFilterAccess): List<NativeHooks.Registration> {
        filterAccess = access
        // Without the per-task windowing mode only the broadcast set can be trusted, and a
        // fullscreen release that has not been delivered yet still hides the real target.
        if (!access.windowingModeAvailable) {
            log(TAG) { "task windowing mode unavailable; release/gesture race cannot be excluded" }
        }
        val hooks = mutableListOf<NativeHooks.Registration>()
        try {
            hooks += access.visible.hookBefore { param ->
                tracker = param.thisObject
                runCatching { filterVisibleGroups(param, access) }
                    .onFailure { log(TAG, "filter visible groups failed", it) }
            }
            hooks += access.cached.hookAfter { param ->
                tracker = param.thisObject
                runCatching { rebuildCachedTopTask(param, access) }
                    .onFailure { log(TAG, "filter cached top task failed", it) }
            }
            return hooks
        } catch (error: Throwable) {
            hooks.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
    }

    private fun isManaged(taskId: Int): Boolean =
        taskId > 0 && managedTaskIds.contains(taskId)

    /** shell 路径：visibleTasks 数组里"整组都是受管任务"的分组整组剔除。 */
    private fun filterVisibleGroups(param: NativeHooks.Call, access: QuickstepTaskFilterAccess) {
        if (managedTaskIds.isEmpty()) return
        val groups = param.args.getOrNull(0) as? Array<*> ?: return
        val kept = groups.filter { group ->
            if (group == null) return@filter true
            val ids = runCatching { access.groupHosted(group) }.getOrNull()
            // 解析失败（desk 等特殊组）、组内混有非受管任务、或任务已回到全屏 → 保留
            ids == null || ids.isEmpty() || ids.any { (id, hosted) -> !isManaged(id) || !hosted }
        }
        if (kept.size == groups.size) return
        val component = groups.javaClass.componentType
        val replacement = java.lang.reflect.Array.newInstance(component, kept.size)
        kept.forEachIndexed { index, group -> java.lang.reflect.Array.set(replacement, index, group) }
        param.args[0] = replacement
        log(TAG) { "filtered ${groups.size - kept.size} managed group(s) from visible tasks" }
    }

    /** 缓存路径：过滤所有受管任务，避免 Pixel 的多任务候选直接读到第二个受管任务。 */
    private fun rebuildCachedTopTask(param: NativeHooks.Call, access: QuickstepTaskFilterAccess) {
        if (managedTaskIds.isEmpty()) return
        val result = param.result ?: return
        val filtered = access.withoutManagedTasks(result, ::isManaged) ?: return
        param.result = filtered
        if (BuildConfig.DEBUG) {
            log(TAG) { "gesture task filtered managed=[${managedTaskIds.joinToString()}] " +
                "before=${access.cachedFacts(result)} after=${access.cachedFacts(filtered)}" }
        }
    }
}
