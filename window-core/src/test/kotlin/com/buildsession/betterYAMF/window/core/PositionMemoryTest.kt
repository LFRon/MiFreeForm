package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionMemoryTest {

    private val a = WindowRect(10, 20, 110, 220)
    private val b = WindowRect(30, 40, 130, 240)

    @Test
    fun takeReturnsRememberedAndClears() {
        val memory = PositionMemory()
        memory.rememberIfAbsent(a)
        assertEquals(a, memory.take())
        assertNull(memory.take())
    }

    @Test
    fun repeatedRememberDoesNotOverwriteFirstValue() {
        val memory = PositionMemory()
        memory.rememberIfAbsent(a)
        memory.rememberIfAbsent(b)
        assertEquals(a, memory.take())
    }

    @Test
    fun rememberNullKeepsMemoryEmpty() {
        val memory = PositionMemory()
        memory.rememberIfAbsent(null)
        memory.rememberIfAbsent(a)
        assertEquals(a, memory.take())
    }

    @Test
    fun rememberWorksAgainAfterTake() {
        val memory = PositionMemory()
        memory.rememberIfAbsent(a)
        memory.take()
        memory.rememberIfAbsent(b)
        assertEquals(b, memory.take())
    }
}
