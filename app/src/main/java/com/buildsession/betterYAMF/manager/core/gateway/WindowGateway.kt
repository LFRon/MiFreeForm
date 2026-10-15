package com.buildsession.betterYAMF.manager.core.gateway

import com.buildsession.betterYAMF.common.model.AppInfo
import com.buildsession.betterYAMF.common.runMain
import com.buildsession.betterYAMF.ipc.IOpenCountListener
import com.buildsession.betterYAMF.manager.services.YAMFManagerProxy

/**
 * 窗口操作门面（逻辑层）：UI 只表达"开窗/切当前到小窗/重置/监听计数"，
 * 传输细节（RPC 代理、AIDL 回调桩）收口在本类。
 */
object WindowGateway {

    private val openCountStubs = mutableMapOf<(Int) -> Unit, IOpenCountListener>()

    fun createWindow(appInfo: AppInfo) {
        YAMFManagerProxy.createWindowUserspace(appInfo)
    }

    fun currentToWindow() {
        YAMFManagerProxy.currentToWindow()
    }

    fun resetAllWindow() {
        YAMFManagerProxy.resetAllWindow()
    }

    /** 打开窗口数变化回调（回调保证在主线程派发）。 */
    fun registerOpenCountListener(listener: (Int) -> Unit) {
        val stub = object : IOpenCountListener.Stub() {
            override fun onUpdate(count: Int) {
                runMain { listener(count) }
            }
        }
        openCountStubs[listener] = stub
        YAMFManagerProxy.registerOpenCountListener(stub)
    }

    fun unregisterOpenCountListener(listener: (Int) -> Unit) {
        openCountStubs.remove(listener)?.let { YAMFManagerProxy.unregisterOpenCountListener(it) }
    }
}
