package com.buildsession.betterYAMF.xposed.adapters.android17.system

import android.os.Build
import dalvik.system.PathClassLoader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Read-only structural validation against this phone's services.jar. No Hook registration,
 * native task/focus mutation, root, process restart or change to the running scene.
 */
class AutomaticTaskFocusNativeBindingTest {
    @Test fun currentSystemHasTheExactAutomaticFocusAndCandidateTraversalBindings() {
        assumeTrue(Build.VERSION.SDK_INT == 37)
        val services = File("/system/framework/services.jar")
        assumeTrue(services.isFile)
        AutomaticTaskFocusGuard(PathClassLoader(services.path, ClassLoader.getSystemClassLoader()))
    }
}
