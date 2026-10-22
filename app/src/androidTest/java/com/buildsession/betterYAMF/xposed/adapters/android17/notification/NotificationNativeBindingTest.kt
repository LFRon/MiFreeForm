package com.buildsession.betterYAMF.xposed.adapters.android17.notification

import android.app.ActivityOptions
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.PathClassLoader
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Read-only signature validation. No Hook installation, native task/notification mutation, root
 * or process restart. Run with --no-hidden-api-checks on the reviewed SDK-37 device.
 */
class NotificationNativeBindingTest {
    @Test fun actualLosSystemUiHasTheCompletePostUnlockAndNativeAnimationBindings() {
        assumeTrue(Build.VERSION.SDK_INT == 37)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.packageManager.getApplicationInfo("com.android.systemui", 0)
        val loader = PathClassLoader(app.sourceDir, ClassLoader.getSystemClassLoader())
        val n = try { NotificationUiBindings(loader) } catch (error: ReflectiveOperationException) {
            val type = loader.loadClass("com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter")
            throw AssertionError("SystemUI notification binding mismatch:\n" +
                type.declaredMethods.sortedBy { it.name }.joinToString("\n") { it.toString() } + "\nFields:\n" +
                type.declaredFields.joinToString("\n") { it.toString() }, error)
        }
        assertEquals(1, n.bodyClick.parameterCount)
        assertEquals(2, n.afterKeyguard.parameterCount)
        assertEquals(4, n.onDismiss.parameterCount)
        assertTrue(n.dismissAction.isInterface)
        assertEquals("onNotificationClick", n.remoteClick.name)
        assertEquals("removeHunAfterClick", n.removeHun.name)
    }

    @Test fun actualLosServerHasTheIdentityGuardedNotificationAndLaunchContainerBindings() {
        assumeTrue(Build.VERSION.SDK_INT == 37)
        val services = File("/system/framework/services.jar"); assumeTrue(services.isFile)
        val loader = PathClassLoader(services.path, ClassLoader.getSystemClassLoader())
        val n = NotificationServerBindings(loader)
        assertEquals(8, n.cancelLocked.parameterCount)
        assertEquals(13, n.cancel.parameterCount)
        assertEquals("token", n.taskToken.name)
        assertEquals("getDisplayId", n.taskDisplay.name)
        val builder = loader.loadClass("android.window.TaskCreationParams\$Builder")
        builder.getDeclaredMethod("setParentContainer", loader.loadClass("android.window.WindowContainerToken"))
        assertEquals(ActivityOptions::class.java, n.launchRoot.declaringClass)
    }
}
