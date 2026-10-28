package com.buildsession.betterYAMF.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Overload selection must match the classic helper surface the adapters were written against. */
class ReflectTest {
    @Suppress("unused")
    private open class Base {
        var flag: Boolean = false
        var count: Int = 0
        val inherited: String = "base"
        fun baseOnly(value: Int): String = "base:$value"
        fun choose(value: Any?): String = "object"
    }

    @Suppress("unused")
    private class Target : Base() {
        fun choose(value: String): String = "string"
        fun choose(value: Int): String = "int"
        fun choose(value: Int, other: Int): String = "int2"
        fun nullable(value: String?): String = if (value == null) "null" else value
        fun widen(value: Number): String = "number"
        fun varargs(vararg values: String): String = "var:${values.size}"
    }

    @Test
    fun exactOverloadWins() {
        val target = Target()
        assertEquals("string", Reflect.callMethod(target, "choose", "x"))
        assertEquals("int", Reflect.callMethod(target, "choose", 7))
        assertEquals("int2", Reflect.callMethod(target, "choose", 7, 8))
    }

    @Test
    fun wideningAndNullAreAccepted() {
        val target = Target()
        assertEquals("number", Reflect.callMethod(target, "widen", 3))
        assertEquals("number", Reflect.callMethod(target, "widen", 3L))
        assertEquals("null", Reflect.callMethod(target, "nullable", null))
        assertEquals("object", Reflect.callMethod(target, "choose", ArrayList<String>()))
    }

    @Test
    fun varargsResolveLikeTheClassicHelper() {
        // Resolution accepts an expanded arity; invocation still follows Method.invoke, which
        // requires the array form for a varargs target. This is the classic helper's behaviour.
        val resolved = Reflect.findMethodBestMatch(
            Target::class.java, "varargs", String::class.java, String::class.java,
        )
        assertEquals("varargs", resolved.name)
        assertTrue(resolved.parameterTypes.single().isArray)
        assertEquals("var:2", Reflect.callMethod(Target(), "varargs", arrayOf("a", "b")))
    }

    @Test
    fun explicitParameterTypesSelectTheOverload() {
        // Mirrors WCT#setAlwaysOnTop(WindowContainerToken, boolean): the runtime types alone cannot
        // separate the overloads, so call sites pass the parameter types explicitly.
        assertEquals("int", Reflect.callMethodTyped(Target(), "choose", arrayOf(Int::class.javaPrimitiveType), 7))
        assertEquals("object", Reflect.callMethodTyped(Target(), "choose", arrayOf(Any::class.java), 7))
    }

    @Test
    fun explicitConstructorTypesSelectTheOverload() {
        val fromInt = Reflect.newInstanceTyped(
            Overloaded::class.java, arrayOf(Int::class.javaPrimitiveType), 3,
        ) as Overloaded
        val fromLong = Reflect.newInstanceTyped(
            Overloaded::class.java, arrayOf(Long::class.javaPrimitiveType), 3L,
        ) as Overloaded
        assertEquals("int:3", fromInt.label)
        assertEquals("long:3", fromLong.label)
    }

    @Test
    fun inheritedMembersResolve() {
        val target = Target()
        assertEquals("base:5", Reflect.callMethod(target, "baseOnly", 5))
        assertEquals("base", Reflect.getObjectField(target, "inherited"))
        Reflect.setIntField(target, "count", 9)
        assertEquals(9, Reflect.getIntField(target, "count"))
        Reflect.setBooleanField(target, "flag", true)
        assertEquals(true, Reflect.getBooleanField(target, "flag"))
    }

    @Test
    fun staticMembersResolve() {
        assertEquals(5, Reflect.getStaticIntField(Holder::class.java, "VALUE"))
        assertEquals(true, Reflect.getStaticBooleanField(Holder::class.java, "FLAG"))
        assertEquals("static", Reflect.callStaticMethod(Holder::class.java, "label", 1))
    }

    @Test
    fun missingMembersReportClassicErrors() {
        val target = Target()
        assertNull(Reflect.findFieldIfExists(Target::class.java, "absent"))
        assertNull(Reflect.findMethodExactIfExists(Target::class.java, "absent"))
        val error = runCatching { Reflect.callMethod(target, "absent") }.exceptionOrNull()
        assertTrue(error is NoSuchMethodError)
        assertNotNull(target)
    }

    @Suppress("unused")
    private class Overloaded(val label: String) {
        constructor(value: Int) : this("int:$value")
        constructor(value: Long) : this("long:$value")
    }

    @Suppress("unused")
    private object Holder {
        const val VALUE = 5
        const val FLAG = true
        @JvmStatic
        fun label(times: Int): String = "static".repeat(times)
    }
}
