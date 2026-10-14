package com.buildsession.betterYAMF.window.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowShellRegistryTest {

    private fun registration(id: Int) = WindowRegistration(windowId = id, density = 2f)

    @Test
    fun newestRegistrationBecomesTop() {
        val registry = WindowShellRegistry()
        registry.register(registration(1))
        registry.register(registration(2))
        assertTrue(registry.isTop(2))
        assertFalse(registry.isTop(1))
    }

    @Test
    fun markTopMovesWindowToTop() {
        val registry = WindowShellRegistry()
        registry.register(registration(1))
        registry.register(registration(2))
        registry.markTop(1)
        assertTrue(registry.isTop(1))
        assertFalse(registry.isTop(2))
    }

    @Test
    fun unregisterRemovesControllerAndOrder() {
        val registry = WindowShellRegistry()
        val first = registry.register(registration(1))
        val second = registry.register(registration(2))
        registry.unregister(second)
        assertEquals(1, registry.count)
        assertTrue(registry.isTop(1))
        registry.unregister(first)
        assertEquals(0, registry.count)
        assertTrue(registry.isTop(1))
    }

    @Test
    fun controllerRaiseUpdatesRegistryOrder() {
        val registry = WindowShellRegistry()
        val controller2 = registry.register(registration(2))
        registry.register(registration(1))
        assertFalse(registry.isTop(2))
        controller2.onInput(WindowInputEvent.Press(2, 0L, RegionId.CONTENT, 0f, 0f))
        assertEquals(
            listOf(WindowCommand.RaiseWindow(2)),
            controller2.onInput(WindowInputEvent.Up(2, 0L, RegionId.CONTENT, 0f, 0f))
        )
        assertTrue(registry.isTop(2))
    }

    @Test
    fun unregisteredIdIsNeverTopWhenOtherWindowsExist() {
        val registry = WindowShellRegistry()
        registry.register(registration(1))
        assertFalse(registry.isTop(99))
    }

    @Test
    fun emptyRegistryTreatsAnyIdAsTop() {
        val registry = WindowShellRegistry()
        assertTrue(registry.isTop(42))
    }

    @Test
    fun smoothWindowsShareDisplayIdButKeepSeparateControllers() {
        val registry = WindowShellRegistry()
        val a = registry.register(registration(0))
        val b = registry.register(registration(0))
        assertEquals(2, registry.count)
        assertTrue(registry.isTop(0))

        b.onInput(WindowInputEvent.Press(0, 0L, RegionId.CONTENT, 0f, 0f))
        assertEquals(
            emptyList<WindowCommand>(),
            b.onInput(WindowInputEvent.Up(0, 0L, RegionId.CONTENT, 0f, 0f))
        )

        registry.unregister(a)
        assertEquals(1, registry.count)
        assertTrue(registry.isTop(0))
        registry.unregister(b)
        assertEquals(0, registry.count)
        assertTrue(registry.isTop(0))
    }
}
