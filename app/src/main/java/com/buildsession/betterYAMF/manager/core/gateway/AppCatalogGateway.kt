package com.buildsession.betterYAMF.manager.core.gateway

import com.buildsession.betterYAMF.common.model.AppInfo
import com.buildsession.betterYAMF.ipc.IAppListCallback
import com.buildsession.betterYAMF.manager.services.YAMFManagerProxy

/**
 * 应用目录门面（逻辑层）：分块拉取可启动应用清单（块回调在 binder 线程派发，
 * 调用方自行切主线程，保持既有语义）。
 */
object AppCatalogGateway {

    fun loadAsync(onChunk: (List<AppInfo>) -> Unit, onFinished: () -> Unit) {
        YAMFManagerProxy.getAppListAsync(object : IAppListCallback.Stub() {
            override fun onAppListReceived(appList: MutableList<AppInfo>) {
                onChunk(appList)
            }

            override fun onAppListFinished() {
                onFinished()
            }
        })
    }
}
