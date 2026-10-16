package com.buildsession.betterYAMF.xposed.hook.organizer

import android.app.ActivityManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.log
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话显示器控制器（系统侧，实现层）：米窗"虚拟屏承载"的唯一收口点。
 *
 * 设计目标（对照 LoS 24.0 源码逐点校验，详见旗标注释）：
 * 1. 应用以**普通全屏应用**身份运行在一块私有虚拟屏上——不触碰任务容器语义
 *    （无承载根/无 reparent/无密度覆盖/无焦点让渡），从根上消灭"小窗承载"派生的一整类问题；
 * 2. 显示密度 = 跟随默认显示器当前密度（**小窗不改应用 DPI**：跨屏迁移只产生窗口尺寸变化，
 *    不产生密度差异，应用按原生度量与目标窗口自行布局）；
 * 3. 系统层面"不是一个全局可用的虚拟屏"：不设 PUBLIC → `DisplayDeviceInfo.FLAG_PRIVATE`
 *    （VirtualDisplayAdapter.java:624-625），枚举/查询按 uid 过滤
 *    （DisplayManagerService.getDisplayIdsInternal:1625、getDisplayInfoInternal:1636），
 *    显示事件亦按 uid 过滤投递（deliverDisplayEvents:3994-3998）；
 * 4. 不触发桌面模式：不设 ALLOWS_CONTENT_MODE_SWITCH——私有屏会被强制剥离该旗标
 *    （DisplayManagerService.java:2067-2075「Private virtual displays are not allowed to
 *    perform projection」），且不设 SHOULD_SHOW_SYSTEM_DECORATIONS/PUBLIC/PRESENTATION/
 *    AUTO_MIRROR（各自对应：装饰栈不拉起、投屏类别不可见、不可被镜像）。
 *
 * 权限说明：本对象在 system_server 进程内运行（uid=1000），TRUSTED/SECURE 等旗标对
 * 系统 uid 免校验（DisplayManagerService.java:2170-2185），且启动闲置于私有屏的应用
 * 非可信屏会拒绝（ActivityTaskSupervisor.java:1326-1345）——故 TRUSTED 必需。
 */
object SessionDisplays {
    private const val TAG = "reYAMF_SessionDisplay"

    /*
     * LoS 24.0 旗标字面量（DisplayManager.java:210-557；常量位于隐藏 API 面，按项目惯例取字面量）。
     * 各旗标取值：
     *   VIRTUAL_DISPLAY_FLAG_SECURE                       1 << 2  （防 FLAG_SECURE 窗口被涂黑）
     *   VIRTUAL_DISPLAY_FLAG_TRUSTED                      1 << 10 （可信屏：允许普通应用启动+自持焦点前提）
     *   VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP            1 << 11 （独立显示组：不并入设备默认组）
     *   VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED              1 << 12 （不显示锁屏；依赖独立组）
     *   VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED      1 << 13 （注入触摸无提示音/振动）
     *   VIRTUAL_DISPLAY_FLAG_OWN_FOCUS                    1 << 14 （屏自持焦点/触摸模式，不搅物理屏）
     *   VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED     1 << 16 （永不夺物理屏顶层焦点）
     */
    private const val FLAG_SECURE = 1 shl 2
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val FLAG_OWN_FOCUS = 1 shl 14
    private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    private const val SESSION_DISPLAY_FLAGS = FLAG_SECURE or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or
            FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_OWN_FOCUS or
            FLAG_STEAL_TOP_FOCUS_DISABLED

    private class Session(
        val windowId: Int,
        val display: android.hardware.display.VirtualDisplay,
        val densityDpi: Int,
        @Volatile var width: Int,
        @Volatile var height: Int
    )

    private val byWindow = ConcurrentHashMap<Int, Session>()
    private val byDisplay = ConcurrentHashMap<Int, Session>()

    /** 为宿主窗口创建会话显示器（幂等）；返回 displayId，失败 -1。surface 由宿主稍后交接。 */
    fun createForWindow(windowId: Int, width: Int, height: Int, densityDpi: Int): Int {
        byWindow[windowId]?.let { return it.display.display.displayId }
        return runCatching {
            val w = width.coerceAtLeast(64)
            val h = height.coerceAtLeast(64)
            val display = Instances.displayManager.createVirtualDisplay(
                "reYAMF-session-$windowId", w, h, densityDpi, null, SESSION_DISPLAY_FLAGS
            )
            val displayId = display.display.displayId
            val session = Session(windowId, display, densityDpi, w, h)
            byWindow[windowId] = session
            byDisplay[displayId] = session
            log(TAG, "created display=$displayId window=$windowId size=${w}x$h dpi=$densityDpi")
            displayId
        }.getOrElse {
            log(TAG, "create display failed window=$windowId", it)
            -1
        }
    }

    /** 交接/撤销内容表面（SurfaceView 生命周期；null = 表面销毁）。 */
    fun setSurface(windowId: Int, surface: Surface?) {
        val session = byWindow[windowId] ?: return
        runCatching { session.display.setSurface(surface) }
            .onFailure { log(TAG, "setSurface failed window=$windowId surface=$surface", it) }
    }

    /** 视口尺寸变化：显示器像素跟随内容区（1:1 映射，密度恒定）。 */
    fun resize(windowId: Int, width: Int, height: Int) {
        val session = byWindow[windowId] ?: return
        val w = width.coerceAtLeast(64)
        val h = height.coerceAtLeast(64)
        if (w == session.width && h == session.height) return
        session.width = w
        session.height = h
        runCatching { session.display.resize(w, h, session.densityDpi) }
            .onFailure { log(TAG, "resize failed window=$windowId size=${w}x$h", it) }
    }

    /** 释放会话显示器（私有屏移除即销毁其内容——VirtualDisplayAdapter.java:664 注释语义）。 */
    fun release(windowId: Int) {
        val session = byWindow.remove(windowId) ?: return
        byDisplay.remove(session.display.display.displayId)
        runCatching { session.display.release() }
            .onFailure { log(TAG, "release failed window=$windowId", it) }
        log(TAG, "released display=${session.display.display.displayId} window=$windowId")
    }

    fun displayIdFor(windowId: Int): Int = byWindow[windowId]?.display?.display?.displayId ?: -1

    fun containsWindow(windowId: Int): Boolean = byWindow.containsKey(windowId)

    fun containsDisplay(displayId: Int): Boolean = byDisplay.containsKey(displayId)

    fun displayIds(): Set<Int> = byDisplay.keys.toSet()

    fun anyActive(): Boolean = byWindow.isNotEmpty()

    /** 任务是否运行在任一会话显示器上（实现层分拣用）。 */
    fun isTaskOnSession(taskId: Int): Boolean {
        val displayId = displayIdOfTask(taskId) ?: return false
        return containsDisplay(displayId)
    }

    /** 任务的显示器 id（跨屏查询，-1 未知）。displayId 为隐藏字段，统一反射读取。 */
    fun displayIdOfTask(taskId: Int): Int? = runCatching {
        val tasks = XposedHelpers.callMethod(
            Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
        ) as? List<*> ?: return null
        tasks.filterIsInstance<ActivityManager.RunningTaskInfo>()
            .find { it.taskId == taskId }
            ?.let { XposedHelpers.getIntField(it, "displayId") }
    }.getOrNull()

    /**
     * 指针事件注入：壳内视口触摸 → 会话屏窗口。
     * 坐标换算：视口可能处于缩放态（窗口动画/尺寸未落定）——按 显示器像素 / 视口像素 比例换算。
     * displayId 经事件隐藏字段承载（MotionEvent#setDisplayId），注入以系统 uid 进行
     * （INJECT_EVENTS 权限天然满足），InputManagerService.injectInputEvent(:1032-1060)。
     */
    fun injectPointer(windowId: Int, event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean = runCatching {
        val session = byWindow[windowId] ?: return false
        val scaleX = if (viewWidth > 0) session.width.toFloat() / viewWidth else 1f
        val scaleY = if (viewHeight > 0) session.height.toFloat() / viewHeight else 1f
        val clone = MotionEvent.obtain(event)
        clone.setLocation(event.x * scaleX, event.y * scaleY)
        XposedHelpers.callMethod(clone, "setDisplayId", session.display.display.displayId)
        val result = Instances.inputManager.injectInputEvent(clone, 0) as? Boolean ?: true
        clone.recycle()
        result
    }.getOrDefault(false)

    /** 按键注入（返回键等导航键路由进会话屏）。 */
    fun injectKey(windowId: Int, keyCode: Int): Boolean = runCatching {
        val session = byWindow[windowId] ?: return false
        val now = SystemClock.uptimeMillis()
        listOf(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0),
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        ).forEach { key ->
            XposedHelpers.callMethod(key, "setDisplayId", session.display.display.displayId)
            Instances.inputManager.injectInputEvent(key, 0)
        }
        true
    }.getOrDefault(false)
}
