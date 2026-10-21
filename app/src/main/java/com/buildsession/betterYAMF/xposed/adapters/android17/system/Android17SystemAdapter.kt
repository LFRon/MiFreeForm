package com.buildsession.betterYAMF.xposed.adapters.android17.system

import com.buildsession.betterYAMF.window.backend.WindowBackend
import com.buildsession.betterYAMF.xposed.adapters.AndroidSystemAdapter
import com.buildsession.betterYAMF.xposed.adapters.SurfacePolicies
import com.buildsession.betterYAMF.xposed.adapters.common.organizer.SmoothWindowBackend
import com.buildsession.betterYAMF.xposed.adapters.common.system.SystemHookInstaller

/** LoS 24.0 and PixelOS seventeen use this reviewed organizer/draw-sync mechanism.
 * Resolve signatures before installing global task hooks; structure alone is not device proof.
 */
internal object Android17SystemAdapter : AndroidSystemAdapter {
    override val sdk = 37
    private var installed = false

    override fun install(classLoader: ClassLoader) {
        if (installed) return
        val organizer = classLoader.loadClass("android.window.ITaskOrganizer")
        val tx = classLoader.loadClass("android.view.SurfaceControl\$Transaction")
        val task = classLoader.loadClass("com.android.server.wm.Task")
        task.getDeclaredMethod("setTaskOrganizer", organizer, Boolean::class.javaPrimitiveType)
        task.getDeclaredMethod("fillTaskInfo", android.app.TaskInfo::class.java, Boolean::class.javaPrimitiveType,
            classLoader.loadClass("com.android.server.wm.TaskDisplayArea"))
        task.getDeclaredField("mLaunchCookie"); task.getDeclaredField("mTaskOrganizer")
        android.app.TaskInfo::class.java.getField("launchCookies")
        classLoader.loadClass("com.android.server.wm.WindowOrganizerController").getDeclaredMethod("getTaskOrganizerController")
        classLoader.loadClass("android.window.TaskCreationParams\$Builder").getDeclaredMethod("setLaunchCookie", android.os.IBinder::class.java)
        classLoader.loadClass("com.android.server.wm.WindowContainer")
            .getDeclaredMethod("migrateToNewSurfaceControl", tx)
        val window = classLoader.loadClass("com.android.server.wm.WindowState")
        window.getDeclaredMethod("finishDrawing", tx, Int::class.javaPrimitiveType)
        window.getDeclaredMethod("applyWithNextDraw", java.util.function.Consumer::class.java)
        val params = classLoader.loadClass("android.window.TaskCreationParams")
        classLoader.loadClass("com.android.server.wm.TaskOrganizerController")
            .getDeclaredMethod("createTask", params)
        val shellGuard = ShellTaskTransactionGuard(classLoader)
        val registrations = shellGuard.install()
        try { SystemHookInstaller.install(classLoader, checkNotNull(SurfacePolicies.forSdk(sdk))) }
        catch (error: Throwable) { registrations.asReversed().forEach { it.unhook() }; throw error }
        installed = true
    }

    override fun windowBackend(): WindowBackend {
        check(installed) { "Android 17 system hooks were not installed" }
        return SmoothWindowBackend
    }
}
