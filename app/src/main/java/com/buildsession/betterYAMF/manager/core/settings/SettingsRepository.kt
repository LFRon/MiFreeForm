package com.buildsession.betterYAMF.manager.core.settings

import android.os.Handler
import android.os.Looper
import com.buildsession.betterYAMF.common.gson
import com.buildsession.betterYAMF.common.model.Config
import com.buildsession.betterYAMF.manager.services.YAMFManagerProxy

/**
 * 应用侧设置仓库（逻辑层）：配置的**唯一读写入口**（单写者）。
 *
 * 语义：
 * - [load]：从模块读取 JSON 并按字段解析（未连接/解析失败回退默认值），刷新缓存；
 * - [update]：每次先读最新值→应用变更→回写（读-改-写），消除多界面"陈旧副本整体覆盖"竞态；
 * - [scheduleSidebarY]：拖拽期间的位置持久化做 250ms 尾随节流（终值必达；[flushSidebarY] 冲刷）。
 *
 * 约定：调用点均在主线程（与既有行为一致）；UI 不感知 JSON/协议细节。
 */
object SettingsRepository {
    private const val SIDEBAR_Y_FLUSH_DELAY_MS = 250L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cached: Config? = null

    private var pendingY: Int? = null
    private var pendingYLandscape = false
    private val flushY = Runnable {
        val y = pendingY ?: return@Runnable
        pendingY = null
        update { if (pendingYLandscape) it.landscapeY = y else it.portraitY = y }
    }

    /** 从模块读取最新配置并刷新缓存（失败回退默认值）。 */
    @Synchronized
    fun load(): Config {
        val parsed = runCatching {
            gson.fromJson(YAMFManagerProxy.configJson, Config::class.java)
        }.getOrNull() ?: Config()
        cached = parsed
        return parsed
    }

    /** 最近一次已知配置（未加载过则先加载）。 */
    fun current(): Config = cached ?: load()

    /** 读-改-写：以模块当前值为基准应用 [block]（单写者语义）。 */
    @Synchronized
    fun update(block: (Config) -> Unit) {
        val fresh = runCatching {
            gson.fromJson(YAMFManagerProxy.configJson, Config::class.java)
        }.getOrNull() ?: Config()
        block(fresh)
        cached = fresh
        YAMFManagerProxy.updateConfig(gson.toJson(fresh))
    }

    /** 整体替换（恢复默认等）。 */
    @Synchronized
    fun replace(config: Config) {
        cached = config
        YAMFManagerProxy.updateConfig(gson.toJson(config))
    }

    // —— typed 便捷方法（UI 只表达意图） ——

    fun setLaunchSideBarAtBoot(enabled: Boolean) = update { it.launchSideBarAtBoot = enabled }

    fun setSidebarPosition(rightSide: Boolean) = update { it.sidebarPosition = rightSide }

    fun setEnableSidebar(enabled: Boolean) = update { it.enableSidebar = enabled }

    fun setSidebarTransparency(percent: Int) = update { it.sidebarTransparency = percent }

    fun setWindowRoundedCorner(dp: Int) = update { it.windowRoundedCorner = dp }

    fun setBarRoundedCorner(dp: Int) = update { it.barRoundedCorner = dp }

    fun setAnimationSpeed(speed: Float) = update { it.animationSpeed = speed }

    fun setWindowSize(widthDp: Int, heightDp: Int) = update {
        it.defaultWindowWidth = widthDp
        it.defaultWindowHeight = heightDp
    }

    fun resetToDefaults() = replace(Config())

    /**
     * 侧边栏拖拽位置持久化（节流入口）：250ms 尾随合并，拖动结束/服务停止时由
     * [flushSidebarY] 冲刷，保证终值必达。
     */
    fun scheduleSidebarY(y: Int, landscape: Boolean) {
        pendingY = y
        pendingYLandscape = landscape
        mainHandler.removeCallbacks(flushY)
        mainHandler.postDelayed(flushY, SIDEBAR_Y_FLUSH_DELAY_MS)
    }

    fun flushSidebarY() {
        mainHandler.removeCallbacks(flushY)
        flushY.run()
    }
}
