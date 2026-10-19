package com.buildsession.betterYAMF.xposed.utils

import com.buildsession.betterYAMF.common.runMain
import kotlinx.coroutines.CoroutineScope
import java.util.LinkedList

object RunMainThreadQueue {
    private val queue = LinkedList<suspend CoroutineScope.() -> Unit>()

    @Synchronized
    fun add(run: suspend CoroutineScope.() -> Unit) {
        queue.offer(run)
        if (queue.size == 1) {
            runMain {
                while (queue.isNotEmpty()) {
                    // 铁律：模块主线程队列任务**逐个隔离**——任何一项抛出都不得穿透到
                    // system_server 主消息循环（真机实证：UI 异常曾直接把系统进程打崩）。
                    val item = queue.poll() ?: continue
                    runCatching { item(this) }
                        .onFailure { log("reYAMF_RunMain", "main-thread task failed", it) }
                }
            }
        }
    }
}
