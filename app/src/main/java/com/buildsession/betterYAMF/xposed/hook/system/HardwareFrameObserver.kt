package com.buildsession.betterYAMF.xposed.hook.system

import android.annotation.SuppressLint
import android.view.View
import java.lang.reflect.Proxy

/** LoS HardwareRenderer:1097/1125 exposes didProduceBuffer; ViewTreeObserver's public frame
 * commit callback discards it (ViewRootImpl:5891). Add an independent RT observer rather than
 * replace ViewRoot's callbacks. Reflection is selected once, never guessed in a frame loop.
 */
// This adapter is used inside the privileged hook process. Instrumentation explicitly disables
// hidden-API checks for its isolated platform fixture; no ordinary app execution path uses it.
@SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
internal class HardwareFrameObserver {
    private val drawing = Class.forName("android.graphics.HardwareRenderer\$FrameDrawingCallback")
    private val commit = Class.forName("android.graphics.HardwareRenderer\$FrameCommitCallback")
    private val renderer = View::class.java.getDeclaredMethod("getThreadedRenderer").apply { isAccessible = true }
    private val register = Class.forName("android.view.ThreadedRenderer")
        .getDeclaredMethod("registerRtFrameCallback", drawing).apply { isAccessible = true }
    private val hardware = Class.forName("android.graphics.HardwareRenderer")
    private val noDrawMask = hardware.getDeclaredField("SYNC_LOST_SURFACE_REWARD_IF_FOUND")
        .apply { isAccessible = true }.getInt(null) or
        hardware.getDeclaredField("SYNC_CONTEXT_IS_STOPPED").apply { isAccessible = true }.getInt(null)

    init {
        drawing.getMethod("onFrameDraw", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        commit.getMethod("onFrameCommit", Boolean::class.javaPrimitiveType)
    }

    fun observe(view: View, drawn: (Boolean) -> Unit) {
        val finished = Proxy.newProxyInstance(commit.classLoader, arrayOf(commit)) { proxy, method, args ->
            when (method.name) {
                "onFrameCommit" -> { drawn(args!![0] as Boolean); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "MiWindowDrawCommit"
                else -> null
            }
        }
        val callback = Proxy.newProxyInstance(drawing.classLoader, arrayOf(drawing)) { proxy, method, args ->
            when (method.name) {
                "onFrameDraw" -> if (method.parameterCount != 2) null
                    else if ((args!![0] as Int) and noDrawMask != 0) {
                        // LoS ViewRootImpl:13932: these outcomes have no later commit callback.
                        drawn(false)
                        null
                    } else finished
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "MiWindowFrameDraw"
                else -> null
            }
        }
        register.invoke(checkNotNull(renderer.invoke(view)), callback)
    }
}
