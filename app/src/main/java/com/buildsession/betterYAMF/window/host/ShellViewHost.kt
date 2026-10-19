package com.buildsession.betterYAMF.window.host

import android.content.Context
import android.graphics.Region
import android.os.IBinder
import com.buildsession.betterYAMF.common.logging.AppLog
import android.view.Display
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import de.robv.android.xposed.XposedHelpers

/**
 * 外壳自持表面托管器（对齐 ColorOS `SurfaceControlViewHost + WindowlessWindowManager` 模型）。
 *
 * 机制（LoS 24 源码核对）：
 * - 壳视图经 `SurfaceControlViewHost(Context, Display, WindowlessWindowManager, callsite)` 托管；
 *   `mSurfaceControl = wwm.mRootSurface`、`mOwnsSurfaceControl = false`（SurfaceControlViewHost.java:343）；
 * - WLM 以我们提供的**壳容器表面**为根：首个窗口的 leash 与内容面都挂在容器之下
 *   （WindowlessWindowManager.java:206-216）⇒ 对容器写一条变换即可带动整个壳，且**没有
 *   WMS 窗口参与**（WMS 无法干预，也不存在 relayout 事务互写）；
 * - 输入：WLM 保护方法 `setTouchRegion(IBinder window, Region)`（WindowlessWindowManager.java:148）
 *   按窗口局部坐标设定可触区；可触区之外由下方任务（应用）接收 ⇒ 壳的"触摸洞"语义
 *   （当前 WM 窗口经 FreeformBackend#applyChromeTouchRegion 实现）在该模型下成为原生能力。
 *
 * 生命周期：attach 失败（任何一步探测不过）返回 false，宿主回落既有 WMS 窗口路径 ——
 * 能力探测，非版本分支。所有系统类访问都收口在本类（适配器层），宿主不感知反射细节。
 */
internal class ShellViewHost(
    private val context: Context,
    private val display: Display,
    private val container: SurfaceControl,
    private val callsite: String
) {
    private var windowlessWm: Any? = null
    private var viewHost: Any? = null

    /** 是否已成功托管（false = 宿主不得使用本实例）。 */
    var attached = false
        private set

    private var lastRegionKey: String? = null

    /**
     * 托管壳视图：失败返回 false（宿主回落 WMS 窗口路径），绝不抛出。
     * [lp] 建议 WRAP_CONTENT×WRAP_CONTENT（WindowlessWindowLayout.java:63-66 支持 wrap：
     * 容器内容尺寸随壳布局自走，与旧 WM 窗口的 WRAP_CONTENT 语义一致，无需手工 relayout）。
     */
    fun attach(view: View, lp: WindowManager.LayoutParams): Boolean {
        if (attached) return true
        return runCatching {
            val cl = context.classLoader
            val wlmClass = cl.loadClass("android.view.WindowlessWindowManager")
            val wlm = XposedHelpers.newInstance(
                wlmClass,
                context.resources.configuration,
                container,
                null // hostInputTransferToken：与 ColorOS 同样传 null（触区路径不依赖宿主令牌）
            )
            val hostClass = cl.loadClass("android.view.SurfaceControlViewHost")
            val host = XposedHelpers.newInstance(hostClass, context, display, wlm, callsite)
            XposedHelpers.callMethod(host, "setView", view, lp)
            windowlessWm = wlm
            viewHost = host
            attached = true
            true
        }.onFailure { AppLog.error(TAG, "shell host attach failed", it) }
            .getOrDefault(false)
    }

    /**
     * 设定壳可触区（窗口局部坐标）。内容区应被挖洞（DIFFERENCE）⇒ 透传下方应用，
     * 与既有 chromeTouchHoles 语义一致。同一键值幂等跳过（避免逐帧 binder 噪声）。
     */
    fun setTouchRegion(region: Region, regionKey: String) {
        if (!attached) return
        if (regionKey == lastRegionKey) return
        runCatching {
            val host = viewHost ?: return
            val wlm = windowlessWm ?: return
            val token = XposedHelpers.callMethod(host, "getWindowToken") ?: return
            val binder = XposedHelpers.callMethod(token, "asBinder") as? IBinder ?: return
            XposedHelpers.callMethod(wlm, "setTouchRegion", binder, region)
            lastRegionKey = regionKey
        }.onFailure { AppLog.error(TAG, "setTouchRegion failed", it) }
    }

    /**
     * 把容器变换写入调用者事务（位置/缩放，SF 直接承担几何；绕容器原点缩放）。
     * 与任务 leash 写入合并同一事务 = ColorOS `applySpringTransform` 单写者单帧原子模型。
     */
    fun placeInto(tx: SurfaceControl.Transaction, x: Float, y: Float, scaleX: Float, scaleY: Float) {
        tx.setPosition(container, x, y)
        tx.setScale(container, scaleX, scaleY)
    }

    fun release() {
        runCatching { viewHost?.let { XposedHelpers.callMethod(it, "release") } }
            .onFailure { AppLog.error(TAG, "shell host release failed", it) }
        viewHost = null
        windowlessWm = null
        attached = false
        lastRegionKey = null
    }

    private companion object {
        const val TAG = "reYAMF_ShellHost"
    }
}
