package com.buildsession.betterYAMF.xposed.adapters.android17.system

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class AutomaticTaskFocusScopeTest {
    @Test fun explicitAndLaterLifecycleFocusNeverInheritAnAutomaticRequest() {
        val scope = AutomaticTaskFocusScope()
        val service = Any()
        assertNull(scope.consume(service))
        val request = scope.enter(service, 3)
        assertSame(request, scope.consume(service))
        assertEquals(3, request.displayId)
        assertNull(scope.consume(service))
        scope.leave(request)
        assertNull(scope.consume(service))
    }

    @Test fun nestedRequestsRetainTheirOwnDisplayAndOuterRequest() {
        val scope = AutomaticTaskFocusScope()
        val service = Any()
        val outer = scope.enter(service, 0)
        val nested = scope.enter(service, 2)
        assertSame(nested, scope.consume(service))
        scope.leave(nested)
        assertSame(outer, scope.consume(service))
        scope.leave(outer)
        assertNull(scope.consume(service))
    }

    @Test fun throwingNativePermissionOrSelectionCannotContaminateTheNextFocusCall() {
        val scope = AutomaticTaskFocusScope()
        val service = Any()
        val request = scope.enter(service, 0)
        assertThrows(SecurityException::class.java) {
            try { throw SecurityException("native permission rejected") }
            finally { scope.leave(request) }
        }
        assertNull(scope.consume(service))
        val next = scope.enter(service, 1)
        assertSame(next, scope.consume(service))
        scope.leave(next)
    }

    @Test fun anotherServiceOrBinderThreadCannotConsumeOrRedirectTheRequest() {
        val scope = AutomaticTaskFocusScope()
        val service = Any()
        val request = scope.enter(service, 0)
        assertNull(scope.consume(Any()))
        val other = AtomicReference<AutomaticTaskFocusScope.Request?>()
        val thread = Thread { other.set(scope.consume(service)) }
        thread.start(); thread.join(5000)
        assertFalse(thread.isAlive)
        assertNull(other.get())
        assertSame(request, scope.consume(service))
        scope.leave(request)
    }
}
