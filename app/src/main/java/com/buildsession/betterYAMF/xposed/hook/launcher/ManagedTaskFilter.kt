package com.buildsession.betterYAMF.xposed.hook.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.buildsession.betterYAMF.xposed.utils.TRUSTED_SENDER_PERMISSION
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
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
 * 版本边界：`TopTaskTracker` 属 Launcher/ROM 实现细节，本组件只依赖其公开方法名；
 * 未来 Launcher 结构变化时只调整本组件，system_server 侧（端口/流程）不受影响。
 */
object ManagedTaskFilter {
    private const val TAG = "BetterYAMF_TaskFilter"

    private val managedTaskIds = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ids = intent.getIntArrayExtra(Protocol.EXTRA_MANAGED_TASK_IDS) ?: return
            managedTaskIds.clear()
            ids.forEach { managedTaskIds.add(it) }
            log(TAG) { "managed tasks updated: [${ids.joinToString()}]" }
        }
    }

    /** 注册集合同步接收器并主动查询一次当前值（Launcher 服务启动时调用；幂等）。 */
    fun ensureReceiverRegistered(context: Context) {
        runCatching {
            if (!receiverRegistered) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(Protocol.ACTION_MANAGED_TASKS),
                    TRUSTED_SENDER_PERMISSION,
                    null,
                    Context.RECEIVER_EXPORTED
                )
                receiverRegistered = true
            }
            context.sendBroadcast(Intent(Protocol.ACTION_QUERY_MANAGED_TASKS))
            log(TAG) { "managed tasks receiver registered" }
        }.onFailure { log(TAG, "managed tasks receiver setup failed", it) }
    }

    /** 安装 TopTaskTracker 双路径过滤（Launcher 进程 handleLoadPackage 时调用）。 */
    fun installTopTaskFilters(classLoader: ClassLoader) {
        val trackerClass = runCatching {
            classLoader.loadClass("com.android.quickstep.TopTaskTracker")
        }.getOrNull()
        if (trackerClass == null) {
            logWarning(TAG, "TopTaskTracker not found, top task filter skipped")
            return
        }
        runCatching {
            findMethod(trackerClass) { name == "onVisibleTasksChanged" }.hookBefore { param ->
                runCatching { filterVisibleGroups(param) }
                    .onFailure { log(TAG, "filter visible groups failed", it) }
            }
            findMethod(trackerClass) { name == "getCachedTopTask" }.hookAfter { param ->
                runCatching { rebuildCachedTopTask(param) }
                    .onFailure { log(TAG, "filter cached top task failed", it) }
            }
            log(TAG) { "top task filter installed" }
        }.onFailure { log(TAG, "install top task filter failed", it) }
    }

    private fun isManaged(taskId: Int): Boolean =
        taskId > 0 && managedTaskIds.contains(taskId)

    /** 读 GroupedTaskInfo 的任务 id 列表（解析失败返回 null，调用方保守"保留"）。 */
    private fun groupTaskIds(group: Any): List<Int>? = runCatching {
        val list = XposedHelpers.callMethod(group, "getTaskInfoList") as? List<*> ?: return null
        list.mapNotNull { info -> info?.let { XposedHelpers.getIntField(it, "taskId") } }
    }.getOrNull()

    /** shell 路径：visibleTasks 数组里"整组都是受管任务"的分组整组剔除。 */
    private fun filterVisibleGroups(param: XC_MethodHook.MethodHookParam) {
        if (managedTaskIds.isEmpty()) return
        val groups = param.args.getOrNull(0) as? Array<*> ?: return
        val kept = groups.filter { group ->
            if (group == null) return@filter true
            val ids = groupTaskIds(group)
            // 解析失败（desk 等特殊组）或组内混有非受管任务 → 保留
            ids == null || ids.isEmpty() || ids.any { !isManaged(it) }
        }
        if (kept.size == groups.size) return
        val component = groups.javaClass.componentType
        val replacement = java.lang.reflect.Array.newInstance(component, kept.size)
        kept.forEachIndexed { index, group -> java.lang.reflect.Array.set(replacement, index, group) }
        param.args[0] = replacement
        log(TAG) { "filtered ${groups.size - kept.size} managed group(s) from visible tasks" }
    }

    /** legacy 路径：顶部任务命中受管任务时，用过滤后的列表重建 CachedTaskInfo。 */
    private fun rebuildCachedTopTask(param: XC_MethodHook.MethodHookParam) {
        if (managedTaskIds.isEmpty()) return
        val result = param.result ?: return
        // shell 模式实例没有 mAllCachedTasks（其数据已由 filterVisibleGroups 过滤）
        val all = runCatching { XposedHelpers.getObjectField(result, "mAllCachedTasks") as? List<*> }
            .getOrNull() ?: return
        val top = runCatching { XposedHelpers.callMethod(result, "getLegacyBaseTask") }.getOrNull() ?: return
        val topId = runCatching { XposedHelpers.getIntField(top, "taskId") }.getOrDefault(-1)
        if (!isManaged(topId)) return
        val filtered = all.filter { info ->
            info == null ||
                !isManaged(runCatching { XposedHelpers.getIntField(info, "taskId") }.getOrDefault(-1))
        }
        val context = XposedHelpers.getObjectField(result, "mContext")
        val displayId = XposedHelpers.getIntField(result, "mDisplayId")
        val activeDeskId = XposedHelpers.getIntField(result, "mActiveDeskId")
        val ctor = result.javaClass.getDeclaredConstructor(
            List::class.java, android.content.Context::class.java,
            java.lang.Integer.TYPE, java.lang.Integer.TYPE
        )
        ctor.isAccessible = true
        param.result = ctor.newInstance(filtered, context, displayId, activeDeskId)
        log(TAG) { "rebuilt top task without managed float (top was $topId)" }
    }
}
