package com.buildsession.betterYAMF.xposed.hook.bridge

import com.buildsession.betterYAMF.xposed.utils.logWarning
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * Framework bridge, confined to AD. Neither the JVM API nor native mechanism callbacks expose
 * libxposed types.
 *
 * The legacy `XC_MethodHook` contract (mutable arguments, `result` that short-circuits the original
 * call, `throwable` observed by after-callbacks, per-call extras) is reproduced on top of the
 * interceptor chain so the adapters keep their shape. After-callbacks run even when a before-callback
 * short-circuited the original, exactly like the legacy framework did.
 */
internal object NativeHooks {
    private const val TAG = "reYAMF_Adapters"

    fun interface Registration {
        fun unhook()
    }

    class Call internal constructor(private val chain: XposedInterface.Chain) {
        private val arguments: Array<Any?> = Array(chain.args.size) { index -> chain.args[index] }
        private val extras = HashMap<String, Any?>()
        private var resultAssigned = false
        private var resultValue: Any? = null

        var throwable: Throwable? = null
            internal set

        val thisObject: Any get() = chain.thisObject

        @Suppress("UNCHECKED_CAST")
        val args: Array<Any> get() = arguments as Array<Any>

        /** Java signatures such as setTaskOrganizer(null, ...) carry nullable arguments. */
        fun argument(index: Int): Any? = arguments.getOrNull(index)

        val method: Member get() = chain.executable

        var result: Any?
            get() = resultValue
            set(value) {
                resultAssigned = true
                resultValue = value
            }

        fun setObjectExtra(key: String, value: Any?) {
            extras[key] = value
        }

        fun getObjectExtra(key: String): Any? = extras[key]

        internal val hasResult: Boolean get() = resultAssigned

        /** Mirrors legacy `param.result`: after-callbacks observe the real return value. */
        internal fun publishResult(value: Any?) {
            if (!resultAssigned) resultValue = value
        }

        internal fun proceed(): Any? = chain.proceed(arguments)
    }

    abstract class Callback {
        open fun beforeHookedMethod(param: Call) {}
        open fun afterHookedMethod(param: Call) {}
    }

    fun hookMethod(method: Member, callback: Callback): Registration {
        val executable = method as? Executable
            ?: throw IllegalArgumentException("${method.name} is not an executable member")
        val handle = Framework.api.hook(executable).intercept { chain ->
            val call = Call(chain)
            try {
                callback.beforeHookedMethod(call)
            } catch (error: Throwable) {
                logWarning(TAG, "before hook failed for ${executable.name}", error)
                return@intercept chain.proceed()
            }
            if (call.hasResult) {
                runAfter(callback, call, executable)
                return@intercept call.result
            }
            val value = try {
                call.proceed()
            } catch (error: Throwable) {
                call.throwable = error
                runAfter(callback, call, executable)
                if (call.hasResult) return@intercept call.result
                throw error
            }
            call.publishResult(value)
            runAfter(callback, call, executable)
            return@intercept if (call.hasResult) call.result else value
        }
        return Registration { handle.unhook() }
    }

    fun invokeOriginalMethod(method: Member, receiver: Any?, arguments: Array<out Any?>): Any? =
        when (method) {
            is Method -> Framework.api.getInvoker(method)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .invoke(receiver, *arguments)

            is Constructor<*> -> Framework.api.getInvoker(method)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .newInstance(*arguments)

            else -> throw IllegalArgumentException("${method.name} is not invokable")
        }

    private fun runAfter(callback: Callback, call: Call, executable: Executable) {
        try {
            callback.afterHookedMethod(call)
        } catch (error: Throwable) {
            logWarning(TAG, "after hook failed for ${executable.name}", error)
        }
    }

    fun Member.hookBefore(work: (Call) -> Unit): Registration = hookMethod(this, object : Callback() {
        override fun beforeHookedMethod(param: Call) = work(param)
    })

    fun Member.hookAfter(work: (Call) -> Unit): Registration = hookMethod(this, object : Callback() {
        override fun afterHookedMethod(param: Call) = work(param)
    })
}
