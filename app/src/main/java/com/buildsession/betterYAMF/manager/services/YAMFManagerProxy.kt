package com.buildsession.betterYAMF.manager.services

import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.Handler
import android.os.Looper
import com.buildsession.betterYAMF.common.logging.AppLog
import com.buildsession.betterYAMF.common.model.AppInfo
import com.buildsession.betterYAMF.ipc.IAppIconCallback
import com.buildsession.betterYAMF.ipc.IAppListCallback
import com.buildsession.betterYAMF.ipc.IOpenCountListener
import com.buildsession.betterYAMF.ipc.IYAMFManager
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object YAMFManagerProxy : IYAMFManager {
    private const val TAG = "reYAMFManagerProxy"

    private class ServiceProxy(private val obj: IYAMFManager) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val result = try { method.invoke(obj, *args.orEmpty()) }
                catch (error: InvocationTargetException) { throw error.targetException }
            AppLog.debug(TAG) {
                if (result == null) "Call service method ${method.name}"
                else "Call service method ${method.name} with result " + result.toString().take(20)
            }
            return result
        }
    }

    private class Connection(val binder: IBinder, val service: IYAMFManager, val death: DeathRecipient)
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(IBinder?) -> Unit>() // main only
    @Volatile private var connection: Connection? = null
    private val service: IYAMFManager? get() = connection?.service
    val isConnected: Boolean get() = connection?.binder?.isBinderAlive == true

    fun addConnectionListener(listener: (IBinder?) -> Unit) {
        main.post { listeners.add(listener); listener(connection?.binder) }
    }

    private fun notifyConnectionChanged() {
        main.post { listeners.toList().forEach { it(connection?.binder) } }
    }

    fun linkService(binder: IBinder): Boolean = synchronized(lock) {
        if (connection?.binder == binder && binder.isBinderAlive) return@synchronized true
        val proxy = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(IYAMFManager::class.java),
            ServiceProxy(IYAMFManager.Stub.asInterface(binder))
        ) as IYAMFManager
        lateinit var next: Connection
        val death = DeathRecipient {
            synchronized(lock) {
                if (connection === next) {
                    connection = null
                    notifyConnectionChanged()
                    AppLog.warn(TAG, "System service connection died")
                }
            }
        }
        next = Connection(binder, proxy, death)
        try { binder.linkToDeath(death, 0) }
        catch (error: android.os.RemoteException) {
            AppLog.warn(TAG, "Cannot link system service", error)
            return@synchronized false
        }
        if (!binder.isBinderAlive) { binder.unlinkToDeath(death, 0); return@synchronized false }
        connection?.let { runCatching { it.binder.unlinkToDeath(it.death, 0) } }
        connection = next
        notifyConnectionChanged()
        true
    }

    override fun asBinder() = connection?.binder

    override fun getVersionName(): String? {
        return service?.versionName
    }

    override fun getVersionCode() = service?.versionCode ?: 0

    override fun getUid() = service?.uid ?: -1

    override fun createWindow() {
        service?.createWindow()
    }

    override fun getBuildTime(): Long {
        return service?.buildTime ?: 0
    }

    override fun getConfigJson(): String {
        return service?.configJson ?: "{}"
    }

    override fun updateConfig(newConfig: String) {
        service?.updateConfig(newConfig)
    }

    override fun registerOpenCountListener(iOpenCountListener: IOpenCountListener) {
        service?.registerOpenCountListener(iOpenCountListener)
    }

    override fun unregisterOpenCountListener(iOpenCountListener: IOpenCountListener) {
        service?.unregisterOpenCountListener(iOpenCountListener)
    }

    override fun currentToWindow() {
        service?.currentToWindow()
    }

    override fun resetAllWindow() {
        service?.resetAllWindow()
    }

    override fun createWindowUserspace(appInfo: AppInfo?) {
        service?.createWindowUserspace(appInfo)
    }

    override fun getAppListAsync(callback: IAppListCallback) {
        service?.getAppListAsync(callback)
    }

    override fun getAppIcon(callback: IAppIconCallback, appInfo: AppInfo) {
        service?.getAppIcon(callback, appInfo)
    }

    override fun collapseStatusBarPanel() {
        service?.collapseStatusBarPanel()
    }
}
