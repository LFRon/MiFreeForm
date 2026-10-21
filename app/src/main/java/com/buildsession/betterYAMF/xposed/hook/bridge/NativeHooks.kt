package com.buildsession.betterYAMF.xposed.hook.bridge

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Member

/** Framework bridge, confined to AD. Neither the JVM API nor native mechanism callbacks expose XC types. */
internal object NativeHooks {
    fun interface Registration { fun unhook() }
    class Call internal constructor(private val raw: XC_MethodHook.MethodHookParam) {
        val thisObject: Any get() = raw.thisObject
        @Suppress("UNCHECKED_CAST")
        val args: Array<Any> get() = raw.args as Array<Any>
        /** Java signatures such as setTaskOrganizer(null, ...) carry nullable arguments. */
        fun argument(index: Int): Any? = raw.args[index]
        val method: Member get() = raw.method
        val throwable: Throwable? get() = raw.throwable
        var result: Any?
            get() = raw.result
            set(value) { raw.result = value }
        fun setObjectExtra(key: String, value: Any?) = raw.setObjectExtra(key, value)
        fun getObjectExtra(key: String): Any? = raw.getObjectExtra(key)
    }
    abstract class Callback {
        open fun beforeHookedMethod(param: Call) {}
        open fun afterHookedMethod(param: Call) {}
    }

    private fun adapt(callback: Callback) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) = callback.beforeHookedMethod(Call(param))
        override fun afterHookedMethod(param: MethodHookParam) = callback.afterHookedMethod(Call(param))
    }
    fun hookMethod(method: Member, callback: Callback): Registration {
        val registration = XposedBridge.hookMethod(method, adapt(callback))
        return Registration { registration.unhook() }
    }
    fun hookAllMethods(type: Class<*>, name: String, callback: Callback): List<Registration> =
        XposedBridge.hookAllMethods(type, name, adapt(callback)).map { Registration { it.unhook() } }

    fun invokeOriginalMethod(method: Member, receiver: Any, arguments: Array<out Any?>): Any? =
        XposedBridge.invokeOriginalMethod(method, receiver, arguments)

    fun findAndHookMethod(type: Class<*>, name: String, vararg signature: Any): Registration {
        val callback = signature.last() as Callback
        val parameters = signature.dropLast(1).toTypedArray() + adapt(callback)
        val registration = XposedHelpers.findAndHookMethod(type, name, *parameters)
        return Registration { registration.unhook() }
    }

    fun Member.hookBefore(work: (Call) -> Unit): Registration = hookMethod(this, object : Callback() {
        override fun beforeHookedMethod(param: Call) = work(param)
    })
    fun Member.hookAfter(work: (Call) -> Unit): Registration = hookMethod(this, object : Callback() {
        override fun afterHookedMethod(param: Call) = work(param)
    })
    fun Member.hookReplace(work: (Call) -> Any?): Registration {
        val registration = XposedBridge.hookMethod(this, object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Any? = work(Call(param))
        })
        return Registration { registration.unhook() }
    }
}
