package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.buildsession.betterYAMF.xposed.adapters.AdapterCatalog
import com.buildsession.betterYAMF.xposed.adapters.AndroidEnvironment
import com.buildsession.betterYAMF.xposed.adapters.LauncherSelection
import com.buildsession.betterYAMF.xposed.adapters.android17.launcher3.Android17Launcher3Adapter
import com.buildsession.betterYAMF.xposed.adapters.android17.pixelLauncher.Android17PixelLauncherAdapter
import com.buildsession.betterYAMF.xposed.adapters.android17.pixelLauncher.PixelNativeFreeformAccess
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.PathClassLoader
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Resolves real APKs without starting a Launcher, registering hooks, or reading live tasks.
 * Run with am instrument --no-hidden-api-checks to allow hidden-member access in this test process.
 * The optional pixel17Apk fixture is copied to the target app's code cache and made read-only by
 * the developer. It is SHA-256 pinned; no vendor APK is bundled in the test or installed as Home.
 * Structural bindings and native cache filtering do not prove live handoff timing on PixelOS.
 */
class LauncherNativeBindingTest {
    @Test fun android17HomeAndRecentsFactsMatchTheCurrentUser() {
        assumeTrue(Build.VERSION.SDK_INT == 37)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = LauncherEnvironmentQuery(context).read()
        val resolved = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        // A chooser / unset Home is a separate environment, not evidence of a broken lookup.
        assumeTrue(resolved?.activityInfo != null && resolved.activityInfo.packageName != "android")
        val activity = requireNotNull(resolved?.activityInfo)
        assertEquals(android.content.ComponentName(activity.packageName, activity.name), snapshot.home)
        assertNotNull("Android 17 must expose its configured Recents component", snapshot.recents)
        assertEquals(snapshot.home?.packageName, snapshot.runtime.defaultHomePackage)
        assertEquals(snapshot.recents?.packageName, snapshot.runtime.recentsPackage)
        assertEquals(Build.VERSION.SDK_INT, snapshot.runtime.sdk)
        assertEquals(context.packageName, snapshot.runtime.packageName)
        assertEquals(android.app.Application.getProcessName(), snapshot.runtime.processName)
        assertEquals(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode,
            snapshot.runtime.launcherVersionCode)
    }

    @Test fun installedAndroid17LauncherSatisfiesItsSelectedNativeBindings() {
        assumeTrue(Build.VERSION.SDK_INT == 37)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        val info = requireNotNull(home?.activityInfo?.applicationInfo)
        assumeTrue(info.packageName == "com.android.launcher3" ||
            info.packageName == "com.google.android.apps.nexuslauncher")
        val selection = AdapterCatalog.selectLauncher(Build.VERSION.SDK_INT, info.packageName,
            info.packageName, AndroidEnvironment.identity, AndroidEnvironment.launcherVersionCode(info))
        assertTrue("Installed Launcher must select one candidate unit: $selection",
            selection is LauncherSelection.Selected)
        val signature = if (info.packageName == "com.android.launcher3")
            Android17Launcher3Adapter.retirementSignature else Android17PixelLauncherAdapter.retirementSignature
        val loader = PathClassLoader(info.sourceDir, ClassLoader.getSystemClassLoader())
        assertBindingsAndCache(loader, signature, context)
    }

    @Test fun reviewedPixel17ApkSatisfiesItsNativeBindingsAndFiltersManagedCache() {
        assumeTrue(Build.VERSION.SDK_INT == 37)
        val path = InstrumentationRegistry.getArguments().getString("pixel17Apk")
        assumeTrue("Pass -e pixel17Apk <read-only APK path> to audit the external fixture", path != null)
        val apk = File(requireNotNull(path))
        assertTrue(apk.isFile)
        assertFalse("Android dynamic code must be read-only", apk.canWrite())
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { stream ->
            val bytes = ByteArray(65536)
            while (true) {
                val count = stream.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        assertEquals("3d7c14c02a7a42c9b88b0d5ced4662330ce6e6989563dab5abe9a8158cd70b56",
            digest.digest().joinToString("") { "%02x".format(it) })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = requireNotNull(context.packageManager.getPackageArchiveInfo(apk.path, 0))
        val selected = AdapterCatalog.selectLauncher(37, info.packageName, info.packageName,
            launcherVersionCode = info.longVersionCode) as LauncherSelection.Selected
        assertSame(Android17PixelLauncherAdapter, selected.adapter)
        val loader = PathClassLoader(apk.path, ClassLoader.getSystemClassLoader())
        assertBindingsAndCache(loader, Android17PixelLauncherAdapter.retirementSignature, context)
        assertEquals("getEnabledShortcuts", PixelNativeFreeformAccess(loader).enabled.name)
    }

    private fun assertBindingsAndCache(loader: ClassLoader,
        signature: LauncherSourceRetirement.Signature, context: Context) {
        val native = QuickstepNativeAccess(loader, signature)
        assertEquals("onInputEvent", native.onInput.name)
        assertEquals("onGestureEnded", native.gestureEnd.name)
        assertEquals(Void.TYPE, native.onInput.returnType)
        assertEquals(Void.TYPE, native.gestureEnd.returnType)
        // Executing this simple native value constructor does not initialize Launcher services.
        // Pixel 17/907's onVisibleTasksChanged is empty; getCachedTopTask must be filtered itself.
        val type = native.taskFilter.cached.returnType
        val constructor = type.getDeclaredConstructor(List::class.java, Context::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val hosted = ActivityManager.RunningTaskInfo().apply { taskId = 710 }
        val underlying = ActivityManager.RunningTaskInfo().apply { taskId = 711 }
        val original = constructor.newInstance(listOf(hosted, underlying), context, 0, -1)
        val filtered = requireNotNull(native.taskFilter.withoutManagedTasks(original) { it == 710 })
        assertSame(hosted, type.getMethod("getLegacyBaseTask").invoke(original))
        assertSame(underlying, type.getMethod("getLegacyBaseTask").invoke(filtered))
        assertNull(native.taskFilter.withoutManagedTasks(filtered) { it == 710 })
        // Pixel GestureState.getRunningTaskIds(true) reads the first two cached entries directly.
        // A hosted task below an ordinary top task must be removed too, preserving all other facts.
        val third = ActivityManager.RunningTaskInfo().apply { taskId = 712 }
        val below = constructor.newInstance(listOf(underlying, hosted, third), context, 8, 3)
        val belowFiltered = requireNotNull(native.taskFilter.withoutManagedTasks(below) { it == 710 })
        val tasks = type.getDeclaredField("mAllCachedTasks").apply { isAccessible = true }
        assertEquals(listOf(underlying, third), tasks.get(belowFiltered))
        assertEquals(listOf(underlying, hosted, third), tasks.get(below))
        assertEquals(8, type.getDeclaredField("mDisplayId").apply { isAccessible = true }.getInt(belowFiltered))
        assertEquals(3, type.getDeclaredField("mActiveDeskId").apply { isAccessible = true }.getInt(belowFiltered))
        assertSame(context, type.getDeclaredField("mContext").apply { isAccessible = true }.get(belowFiltered))
        val allHosted = constructor.newInstance(listOf(hosted), context, 0, -1)
        val empty = requireNotNull(native.taskFilter.withoutManagedTasks(allHosted) { it == 710 })
        assertNull(type.getMethod("getLegacyBaseTask").invoke(empty))
        // Some LoS builds also retain the shell-backed representation with a null legacy list.
        type.declaredConstructors.firstOrNull { it.parameterTypes.map(Class<*>::getName) ==
            listOf("com.android.wm.shell.shared.GroupedTaskInfo") }?.let { shellConstructor ->
            shellConstructor.isAccessible = true
            val shell = shellConstructor.newInstance(*arrayOfNulls<Any>(1))
            assertNull(native.taskFilter.withoutManagedTasks(shell) { true })
        }
    }
}
