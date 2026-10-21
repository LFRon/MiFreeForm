package com.buildsession.betterYAMF.xposed.adapters.android17.shell

import android.app.ActivityManager.RunningTaskInfo
import android.app.TaskInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.UserHandle
import android.view.SurfaceControl
import androidx.test.platform.app.InstrumentationRegistry
import com.buildsession.betterYAMF.xposed.adapters.android17.system.ShellTaskTransactionGuard
import com.buildsession.betterYAMF.xposed.adapters.common.system.ShellProcessIdentity
import com.buildsession.betterYAMF.xposed.adapters.common.system.ShellOwnershipProtocol
import dalvik.system.PathClassLoader
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Structural and value checks only. No Hook installation, real task mutation or process restart.
 * Run with --no-hidden-api-checks on the selected SDK-37 platform.
 */
class ShellNativeBindingTest {
    private fun shell(): Android17ShellBindings {
        assumeTrue(Build.VERSION.SDK_INT == 37)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.packageManager.getApplicationInfo("com.android.systemui", 0)
        return Android17ShellBindings(PathClassLoader(app.sourceDir, ClassLoader.getSystemClassLoader()))
    }

    @Test fun installedShellHasAllRequiredEntryAndIsolationBindings() {
        val bindings = shell()
        assertTrue(bindings.observers.any { it.declaringClass == bindings.freeform })
        assertTrue(bindings.decorations.isNotEmpty())
        assertEquals("onCreate", bindings.applicationCreate.name)
    }

    @Test fun installedSystemUiIdentityAndPrivilegesSupportTheHandshake() {
        assumeTrue(Build.VERSION.SDK_INT == 37)
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val pkg = ShellOwnershipProtocol.PACKAGE
        val info = pm.getApplicationInfo(pkg, 0)
        val systemUser = UserHandle.getUserHandleForUid(info.uid) == UserHandle.getUserHandleForUid(0)
        assertEquals(systemUser, ShellProcessIdentity.acceptsHost(pkg, info.processName, systemUser, info.uid, info.uid))
        for (permission in listOf("android.permission.START_TASKS_FROM_RECENTS", "android.permission.INTERACT_ACROSS_USERS_FULL"))
            assertEquals(permission, PackageManager.PERMISSION_GRANTED, pm.checkPermission(permission, pkg))
    }

    @Test fun mixedProjectionKeepsNativeInputUnchangedAndBorrowsItsHandles() {
        val bindings = shell()
        val marker = Binder()
        val infoType = Class.forName("android.window.TransitionInfo")
        val tokenType = Class.forName("android.window.WindowContainerToken")
        val changeType = Class.forName("android.window.TransitionInfo\$Change")
        val info = infoType.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(6, 17)
        val rootToken = token(); val childToken = token(); val appToken = token(); val wallpaperToken = token()
        val surface = SurfaceControl::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        fun change(token: Any?, task: RunningTaskInfo? = null, parent: Any? = null): Any =
            changeType.getConstructor(tokenType, SurfaceControl::class.java).newInstance(token, surface).also {
                changeType.getMethod("setTaskInfo", RunningTaskInfo::class.java).invoke(it, task)
                changeType.getMethod("setParent", tokenType).invoke(it, parent)
            }
        val rootInfo = RunningTaskInfo().apply { taskId = 700 }
        @Suppress("UNCHECKED_CAST") val cookies = TaskInfo::class.java.getField("launchCookies").get(rootInfo) as MutableList<IBinder>
        cookies.add(marker)
        val root = change(rootToken, rootInfo)
        val child = change(childToken, parent = rootToken)
        val other = change(appToken, RunningTaskInfo().apply { taskId = 701 })
        val wallpaper = change(wallpaperToken)
        val synthetic = change(null)
        val original = listOf(child, other, root, wallpaper, synthetic)
        original.forEach { infoType.getMethod("addChange", changeType).invoke(info, it) }
        infoType.getMethod("setTrack", Int::class.javaPrimitiveType).invoke(info, 3)
        infoType.getMethod("setDebugId", Int::class.javaPrimitiveType).invoke(info, 42)
        infoType.getMethod("addRootLeash", Int::class.javaPrimitiveType, SurfaceControl::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(info, 0, surface, 0, 0)
        val seen = mutableListOf<RunningTaskInfo>()
        val projected = bindings.project(info, marker, seen::add)
        assertNotSame(info, projected)
        assertEquals(original, infoType.getMethod("getChanges").invoke(info))
        assertEquals(listOf(other, wallpaper, synthetic), infoType.getMethod("getChanges").invoke(projected))
        assertEquals(listOf(rootInfo), seen)
        for (name in listOf("getType", "getFlags", "getTrack", "getDebugId", "getRootCount"))
            assertEquals(infoType.getMethod(name).invoke(info), infoType.getMethod(name).invoke(projected))
        assertSame(infoType.getMethod("getRoot", Int::class.javaPrimitiveType).invoke(info, 0),
            infoType.getMethod("getRoot", Int::class.javaPrimitiveType).invoke(projected, 0))
        assertSame(info, bindings.project(info, Binder()))
        assertSame(info, bindings.project(info, null))
        assertSame(projected, bindings.project(projected, marker))
    }

    @Test fun nativeWriteGuardKeepsUnrelatedConfigAndHierarchyOperations() {
        assumeTrue(Build.VERSION.SDK_INT == 37)
        val services = File("/system/framework/services.jar")
        assumeTrue(services.isFile)
        val guard = ShellTaskTransactionGuard(PathClassLoader(services.path, ClassLoader.getSystemClassLoader()))
        val type = Class.forName("android.window.WindowContainerTransaction")
        val tokenType = Class.forName("android.window.WindowContainerToken")
        val tx = type.getDeclaredConstructor().newInstance()
        val ours = token(); val other = token(); val desk = token()
        for (task in listOf(ours, other)) {
            type.getMethod("setDensityDpi", tokenType, Int::class.javaPrimitiveType).invoke(tx, task, 320)
            type.getMethod("reparent", tokenType, tokenType, Boolean::class.javaPrimitiveType).invoke(tx, task, desk, true)
            type.getMethod("reorder", tokenType, Boolean::class.javaPrimitiveType).invoke(tx, task, true)
        }
        val binder = tokenType.getMethod("asBinder").invoke(ours)
        val oldConfig = (type.getMethod("getChanges").invoke(tx) as Map<*, *>).toMap()
        val oldOps = (type.getMethod("getHierarchyOps").invoke(tx) as List<*>).toList()
        guard.excludeNativeWrites(tx, owned = { it == binder })
        val changes = type.getMethod("getChanges").invoke(tx) as Map<*, *>
        assertEquals(setOf(tokenType.getMethod("asBinder").invoke(other)), changes.keys)
        assertSame(oldConfig[tokenType.getMethod("asBinder").invoke(other)], changes.values.single())
        val remainingOps = type.getMethod("getHierarchyOps").invoke(tx) as List<*>
        assertEquals(oldOps.takeLast(2), remainingOps)
        guard.excludeNativeWrites(tx, owned = { false })
        assertEquals(2, remainingOps.size)
    }

    @Test fun fullscreenOrSplitHandbackPreservesItsConfigAndReparent() {
        assumeTrue(Build.VERSION.SDK_INT == 37)
        val services = File("/system/framework/services.jar"); assumeTrue(services.isFile)
        val guard = ShellTaskTransactionGuard(PathClassLoader(services.path, ClassLoader.getSystemClassLoader()))
        val type = Class.forName("android.window.WindowContainerTransaction")
        val tokenType = Class.forName("android.window.WindowContainerToken")
        val app = token(); val target = token()
        val appBinder = tokenType.getMethod("asBinder").invoke(app)
        val targetBinder = tokenType.getMethod("asBinder").invoke(target)
        val tx = type.getDeclaredConstructor().newInstance()
        type.getMethod("setWindowingMode", tokenType, Int::class.javaPrimitiveType).invoke(tx, app, 0)
        type.getMethod("reparent", tokenType, tokenType, Boolean::class.javaPrimitiveType).invoke(tx, app, target, true)
        guard.excludeNativeWrites(tx, owned = { it == appBinder }, canReparentOut = { it == targetBinder })
        assertEquals(1, (type.getMethod("getChanges").invoke(tx) as Map<*, *>).size)
        assertEquals(1, (type.getMethod("getHierarchyOps").invoke(tx) as List<*>).size)
    }

    @Test fun pipAndAppRemovalAreAllowedWhileTheInternalRootRemainsProtected() {
        assumeTrue(Build.VERSION.SDK_INT == 37)
        val services = File("/system/framework/services.jar"); assumeTrue(services.isFile)
        val guard = ShellTaskTransactionGuard(PathClassLoader(services.path, ClassLoader.getSystemClassLoader()))
        val type = Class.forName("android.window.WindowContainerTransaction")
        val tokenType = Class.forName("android.window.WindowContainerToken")
        val app = token(); val closing = token(); val root = token()
        val rootBinder = tokenType.getMethod("asBinder").invoke(root)
        val tx = type.getDeclaredConstructor().newInstance()
        type.getMethod("setWindowingMode", tokenType, Int::class.javaPrimitiveType).invoke(tx, app, 2)
        type.getMethod("removeTask", tokenType).invoke(tx, closing)
        type.getMethod("removeTask", tokenType).invoke(tx, root)
        guard.excludeNativeWrites(tx, owned = { it != null }, internalRoot = { it == rootBinder })
        assertEquals(1, (type.getMethod("getChanges").invoke(tx) as Map<*, *>).size)
        assertEquals(1, (type.getMethod("getHierarchyOps").invoke(tx) as List<*>).size)
    }

    @Test fun opaqueActivityAndClientFragmentTokensDoNotInvalidateNativeIsolation() {
        assumeTrue(Build.VERSION.SDK_INT == 37)
        val services = File("/system/framework/services.jar"); assumeTrue(services.isFile)
        val guard = ShellTaskTransactionGuard(PathClassLoader(services.path, ClassLoader.getSystemClassLoader()))
        val type = Class.forName("android.window.WindowContainerTransaction")
        val activity = Binder()
        val fragment = Binder()
        val tx = type.getDeclaredConstructor().newInstance()
        // WOC handles these operands via ActivityRecord / TaskFragment lookup. They are not
        // WindowContainer.RemoteToken, and must pass through without a fromBinder hard cast.
        type.getMethod("finishActivity", IBinder::class.java).invoke(tx, activity)
        type.getMethod("deleteTaskFragment", IBinder::class.java).invoke(tx, fragment)
        val other = token()
        val tokenType = Class.forName("android.window.WindowContainerToken")
        type.getMethod("reorder", tokenType, Boolean::class.javaPrimitiveType).invoke(tx, other, true)
        val otherBinder = tokenType.getMethod("asBinder").invoke(other)
        val original = (type.getMethod("getHierarchyOps").invoke(tx) as List<*>).toList()
        val resolved = mutableListOf<IBinder>()
        guard.excludeNativeWrites(tx, owned = {
            assertNotSame(activity, it)
            assertNotSame(fragment, it)
            if (it != null) resolved.add(it)
            false
        })
        val kept = type.getMethod("getHierarchyOps").invoke(tx) as List<*>
        assertEquals(original, kept)
        assertSame(original[0], kept[0])
        assertSame(original[1], kept[1])
        assertSame(original[2], kept[2])
        assertEquals(setOf(otherBinder), resolved.toSet())
    }

    private fun token(): Any {
        val type = Class.forName("android.window.WindowContainerToken")
        val creator = type.getField("CREATOR").get(null) as Parcelable.Creator<*>
        return Parcel.obtain().let { parcel ->
            try {
                parcel.writeStrongBinder(Binder()); parcel.setDataPosition(0)
                requireNotNull(creator.createFromParcel(parcel))
            } finally { parcel.recycle() }
        }
    }
}
