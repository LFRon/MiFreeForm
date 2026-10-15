package com.buildsession.betterYAMF.xposed.hook.system

import android.os.SystemClock

/**
 * 系统侧共享信号（仅 system_server 进程内）。
 *
 * 写入者：
 * - 触摸在屏态：全局触摸钩子（FreeformBackend#hookGlobalTouchTracker）；
 * - 最近任务手势抑制：流程接收器（YAMFManager，经 `WindowBackend` 端口转发）。
 *
 * 读取者：后端实现转出（`WindowBackend.isTouchActive / recentsGestureSuppressUntilMs`）。
 */
object SystemSignals {

    @Volatile
    var lastTouchDownAtMs = 0L

    @Volatile
    var lastTouchUpAtMs = 0L

    @Volatile
    var recentsGestureSuppressUntilMs = 0L

    /** 是否正有手指按在屏幕上（用于把回置顶推迟到手势结束，避免中途改窗口栈把进行中的手势抢走）。 */
    fun isTouchActive(): Boolean {
        val last = lastTouchDownAtMs
        val up = lastTouchUpAtMs
        return last > up && SystemClock.uptimeMillis() - last < 1500
    }

    fun suppressRecentsGestureFor(durationMs: Long) {
        recentsGestureSuppressUntilMs = SystemClock.uptimeMillis() + durationMs
    }
}
