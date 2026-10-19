package com.buildsession.betterYAMF.xposed.hook.launcher

import android.content.res.Configuration
import android.graphics.Rect
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherOverlayDisplayTest {
    /** Headless native check: no visible windows and no changes to the device's rotation settings.
     * Run with am instrument --no-hidden-api-checks to construct the fixed-rotation fixture.
     */
    @Test fun overlayFrameIgnoresItsParentsFixedRotationConfiguration() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        LauncherOverlayDisplay(base).use { real ->
            val expected = requireNotNull(real.frame())
            val fake = Configuration(base.resources.configuration)
            val field = Configuration::class.java.getField("windowConfiguration")
            val config = field.get(fake)
            val bounds = Rect(0, 0, expected.height, expected.width)
            field.type.getMethod("setBounds", Rect::class.java).invoke(config, bounds)
            field.type.getMethod("setMaxBounds", Rect::class.java).invoke(config, bounds)
            field.type.getMethod("setDisplayRotation", Int::class.javaPrimitiveType)
                .invoke(config, (expected.rotation + 1) % 4)
            fake.orientation = if (bounds.width() > bounds.height()) Configuration.ORIENTATION_LANDSCAPE
                else Configuration.ORIENTATION_PORTRAIT
            val parent = base.createConfigurationContext(fake)
            val parentBounds = parent.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            assertEquals(bounds, parentBounds) // The fixture really supplies the wrong dimensions.
            LauncherOverlayDisplay(parent).use { overlay ->
                assertEquals(expected, overlay.frame())
                assertNotEquals(parentBounds.width(), overlay.frame()?.width)
            }
        }
    }
    @Test fun aClosedOverlayCannotSupplyCoordinatesToALateCallback() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val overlay = LauncherOverlayDisplay(base)
        assertNotNull(overlay.frame())
        overlay.close()
        assertNull(overlay.frame())
        overlay.close()
        assertNull(overlay.frame())
        LauncherOverlayDisplay(base).use { next -> assertNotNull(next.frame()) }
    }
}
