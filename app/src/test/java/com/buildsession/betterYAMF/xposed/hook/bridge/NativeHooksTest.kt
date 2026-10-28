package com.buildsession.betterYAMF.xposed.hook.bridge

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * Locks the legacy `XC_MethodHook` contract the adapters are written against: mutable arguments,
 * `result` that short-circuits the original, `result` observed by after-callbacks, `throwable`
 * visible after a failure, and per-call extras shared between before and after.
 */
class NativeHooksTest {
    private lateinit var framework: FakeFramework

    @Before
    fun bindFramework() {
        framework = FakeFramework()
        val entry = object : XposedModule() {}
        entry.attachFramework(framework, Runnable {})
        Framework.bind(entry)
    }

    @Test
    fun beforeCallbackCanShortCircuitAndAfterStillRuns() {
        val target = Subject()
        var afterSaw: Any? = "unset"
        NativeHooks.hookMethod(method("work"), object : NativeHooks.Callback() {
            override fun beforeHookedMethod(param: NativeHooks.Call) {
                param.result = "short"
            }

            override fun afterHookedMethod(param: NativeHooks.Call) {
                afterSaw = param.result
            }
        })

        val result = run(target, "work", arrayOf<Any?>(1)) { error("original must not run") }

        assertEquals("short", result)
        assertEquals("short", afterSaw)
        assertEquals(0, target.calls)
    }

    @Test
    fun afterCallbackObservesTheRealReturnValue() {
        NativeHooks.hookMethod(method("work"), object : NativeHooks.Callback() {
            override fun afterHookedMethod(param: NativeHooks.Call) {
                assertEquals("done:2", param.result)
                param.result = "overridden"
            }
        })

        assertEquals("overridden", run(Subject(), "work", arrayOf<Any?>(2)) { "done:$it" })
    }

    @Test
    fun argumentsWrittenByBeforeReachTheOriginal() {
        val target = Subject()
        NativeHooks.hookMethod(method("work"), object : NativeHooks.Callback() {
            override fun beforeHookedMethod(param: NativeHooks.Call) {
                param.args[0] = 41
            }
        })

        val seen = run(target, "work", arrayOf<Any?>(1))
        assertEquals("done:41", seen)
        assertEquals(41, target.lastArgument)
    }

    @Test
    fun throwableIsVisibleToAfterAndPropagates() {
        var observed: Throwable? = null
        NativeHooks.hookMethod(method("work"), object : NativeHooks.Callback() {
            override fun afterHookedMethod(param: NativeHooks.Call) {
                observed = param.throwable
            }
        })
        val failure = IllegalStateException("boom")
        val thrown = runCatching { run(Subject(), "work", arrayOf<Any?>(1)) { throw failure } }

        assertSame(failure, thrown.exceptionOrNull())
        assertSame(failure, observed)
    }

    @Test
    fun extrasAreSharedBetweenBeforeAndAfter() {
        var seen: Any? = null
        NativeHooks.hookMethod(method("work"), object : NativeHooks.Callback() {
            override fun beforeHookedMethod(param: NativeHooks.Call) {
                param.setObjectExtra("token", "value")
            }

            override fun afterHookedMethod(param: NativeHooks.Call) {
                seen = param.getObjectExtra("token")
            }
        })

        run(Subject(), "work", arrayOf<Any?>(1)) { null }
        assertEquals("value", seen)
    }

    @Test
    fun originalInvocationBypassesTheHook() {
        NativeHooks.hookMethod(method("work"), object : NativeHooks.Callback() {})
        val target = Subject()
        val direct = NativeHooks.invokeOriginalMethod(method("work"), target, arrayOf<Any?>(7))
        assertEquals("done:7", direct)
        assertTrue(target.calls == 1)
    }

    private fun method(name: String): Method =
        Subject::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType)

    private fun run(
        target: Subject,
        name: String,
        args: Array<Any?>,
        body: ((Int) -> Any?)? = null,
    ): Any? {
        val hooker = checkNotNull(framework.hooker) { "hook was not installed" }
        val executable = method(name)
        val chain = FakeChain(executable, target, args) { values ->
            body?.invoke(values[0] as Int) ?: executable.invoke(target, *values)
        }
        return hooker.intercept(chain)
    }

    private class Subject {
        var calls = 0
        var lastArgument: Int? = null

        @Suppress("unused")
        fun work(value: Int): Any? {
            calls++
            lastArgument = value
            return "done:$value"
        }
    }

    private class FakeChain(
        private val executable: Method,
        private val receiver: Any,
        private var args: Array<Any?>,
        private val body: (Array<Any?>) -> Any?,
    ) : XposedInterface.Chain {
        override fun getExecutable(): Executable = executable

        override fun getThisObject(): Any = receiver

        override fun getArgs(): List<Any?> = args.toList()

        override fun getArg(index: Int): Any? = args[index]

        override fun proceed(): Any? = body(args)

        override fun proceed(args: Array<out Any?>): Any? {
            this.args = Array(args.size) { args[it] }
            return body(this.args)
        }

        override fun proceedWith(thisObject: Any): Any? = body(args)

        override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? = proceed(args)
    }

    private class FakeFramework : XposedInterface {
        var hooker: XposedInterface.Hooker? = null

        override fun getFrameworkName(): String = "fake"

        override fun getFrameworkVersion(): String = "0"

        override fun getFrameworkVersionCode(): Long = 0

        override fun getFrameworkProperties(): Long = 0

        override fun hook(origin: Executable): XposedInterface.HookBuilder =
            object : XposedInterface.HookBuilder {
                override fun setPriority(priority: Int) = this

                override fun setExceptionMode(mode: XposedInterface.ExceptionMode) = this

                override fun setId(id: String?) = this

                override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
                    this@FakeFramework.hooker = hooker
                    return object : XposedInterface.HookHandle {
                        override fun getExecutable(): Executable = origin
                        override fun getId(): String? = null
                        override fun unhook() {}
                        override fun replaceHook(hooker: XposedInterface.Hooker) = this
                    }
                }
            }

        override fun hookClassInitializer(origin: Class<*>): XposedInterface.HookBuilder = TODO()

        override fun deoptimize(executable: Executable): Boolean = true

        override fun getInvoker(method: Method): XposedInterface.Invoker<*, Method> =
            FakeInvoker(method)

        override fun <T> getInvoker(constructor: Constructor<T>): XposedInterface.CtorInvoker<T> = TODO()

        override fun log(priority: Int, tag: String?, msg: String) {}

        override fun log(priority: Int, tag: String?, msg: String, tr: Throwable?) {}

        override fun getModuleApplicationInfo(): ApplicationInfo = TODO()

        override fun getRemotePreferences(group: String): SharedPreferences = TODO()

        override fun listRemoteFiles(): Array<String> = emptyArray()

        private class FakeInvoker(private val method: Method) : XposedInterface.Invoker<FakeInvoker, Method> {
            override fun setType(type: XposedInterface.Invoker.Type): FakeInvoker = this

            override fun invoke(thisObject: Any?, vararg args: Any?): Any? =
                method.invoke(thisObject, *args)

            override fun invokeSpecial(thisObject: Any, vararg args: Any?): Any? =
                method.invoke(thisObject, *args)
        }

        override fun openRemoteFile(name: String): ParcelFileDescriptor = TODO()
    }
}
