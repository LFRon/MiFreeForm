package com.buildsession.betterYAMF.xposed.hook.freeform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeformResolverTest {

    private class FakeLoader(private vararg val available: String) : ClassLoader(null) {
        protected override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name in available) return Any::class.java
            throw ClassNotFoundException(name)
        }
    }

    @Test
    fun returnsFirstLoadableCandidate() {
        val loader = FakeLoader("b")
        assertEquals("b", FreeformResolver.firstLoadable(loader, listOf("a", "b", "c")))
    }

    @Test
    fun prefersEarlierCandidateWhenAllLoadable() {
        val loader = FakeLoader("a", "b")
        assertEquals("a", FreeformResolver.firstLoadable(loader, listOf("a", "b")))
    }

    @Test
    fun returnsNullWhenNothingLoadable() {
        val loader = FakeLoader("x")
        assertNull(FreeformResolver.firstLoadable(loader, listOf("a", "b")))
    }

    @Test
    fun emptyCandidatesReturnNull() {
        assertNull(FreeformResolver.firstLoadable(FakeLoader("a"), emptyList()))
    }

    @Test
    fun android17OrderPicksMovedClassWhenPresent() {
        val a17Loader = FakeLoader("com.android.server.wm.AppCompatSandboxingPolicy\$ConfigOverrideHint")
        assertEquals(
            "com.android.server.wm.AppCompatSandboxingPolicy\$ConfigOverrideHint",
            FreeformResolver.firstLoadable(a17Loader, FreeformPolicyAndroid17.configOverrideHintCandidates)
        )
    }

    @Test
    fun android17OrderFallsBackOnOldLayout() {
        val oldLoader = FakeLoader("com.android.server.wm.TaskFragment\$ConfigOverrideHint")
        assertEquals(
            "com.android.server.wm.TaskFragment\$ConfigOverrideHint",
            FreeformResolver.firstLoadable(oldLoader, FreeformPolicyAndroid17.configOverrideHintCandidates)
        )
    }
}
