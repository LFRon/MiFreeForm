package com.buildsession.betterYAMF.xposed.hook.launcher

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.IdentityHashMap
import java.util.concurrent.Executor

/** LoS OtherActivityInputConsumer:497 updates UP before onGestureEnded. Simulator.apply builds
 * the final transform, while SurfaceTransactionApplier:94 defers applying it until an RT frame.
 * Observe that native-owned transaction, then retain its simulator pose until native retirement.
 * Never apply/close a borrowed transaction or freeze another simulator/display's animation.
 */
internal class RecentsSourceFrame private constructor(
    private val api: Platform, private val simulator: Any, private val params: Any,
    private val surface: SurfaceControl
) : AutoCloseable {
    class Frame internal constructor(val surface: SurfaceControl, val bounds: Rect,
        val crop: Rect, val cornerRadius: Float)

    private val fence = SourceCommitFence()
    private var frame: Frame? = null
    private var onReady: ((Frame) -> Unit)? = null
    private var closed = false

    /** Called only from the native end hook, after final UP displacement and before its decision. */
    fun freeze(onReady: (Frame) -> Unit) {
        check(Looper.myLooper() == main.looper && !closed && !fence.frozen)
        check(surface.isValid)
        check(simulators[simulator] == null && transactions[params] == null)
        this.onReady = onReady
        transactions[params] = this
        // Explicitly use the native producer once: identical UP/MOVE values can skip AnimatedFloat
        // updates entirely. This guarantees an observed final native transaction in that case too.
        api.apply.invoke(simulator, params)
        val bounds = RectF(api.bounds.invoke(simulator) as RectF)
        val crop = RectF(api.crop.invoke(simulator) as RectF)
        val radius = (api.radius.invoke(simulator) as Number).toFloat()
        check(!bounds.isEmpty && !crop.isEmpty && radius.isFinite() &&
            listOf(bounds.left, bounds.top, bounds.right, bounds.bottom,
                crop.left, crop.top, crop.right, crop.bottom).all { it.isFinite() })
        frame = Frame(surface, Rect().also(bounds::roundOut), Rect().also(crop::roundOut), radius)
        check(fence.freeze()) { "Native source produced no transform transaction" }
        simulators[simulator] = this
        deliver()
    }

    private fun submitting(transaction: SurfaceControl.Transaction) {
        if (closed || fence.frozen) return
        val ticket = fence.submitted()
        transaction.addTransactionCommittedListener(executor) {
            fence.committed(ticket)
            deliver()
        }
    }

    private fun deliver() {
        if (closed || !fence.takeReady()) return
        val ready = onReady ?: return
        onReady = null
        // Always leave the native UP/RT transaction stack, including synchronous fixtures.
        val captured = checkNotNull(frame)
        main.post { if (!closed) ready(captured) }
    }

    override fun close() {
        if (closed) return
        closed = true
        fence.close()
        onReady = null
        frame = null
        if (simulators[simulator] === this) simulators.remove(simulator)
        if (transactions[params] === this) transactions.remove(params)
        // All handles belong to Launcher. No borrowed SurfaceControl is released here.
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val executor = Executor { main.post(it) }
        private val simulators = IdentityHashMap<Any, RecentsSourceFrame>()
        private val transactions = IdentityHashMap<Any, RecentsSourceFrame>()
        private val platforms = mutableMapOf<Class<*>, Result<Platform>>()

        @SuppressLint("PrivateApi") // Verified Launcher producer; capability failure is cached.
        fun install(classLoader: ClassLoader) {
            platform(classLoader.loadClass("com.android.quickstep.util.TaskViewSimulator"))
        }

        private fun platform(simulator: Class<*>): Platform =
            platforms.getOrPut(simulator) { runCatching { Platform(simulator) } }.getOrThrow()

        fun probe(simulator: Any, params: Any, surface: SurfaceControl): RecentsSourceFrame {
            val api = platform(simulator.javaClass)
            check(surface.isValid && api.params.isInstance(params))
            return RecentsSourceFrame(api, simulator, params, surface)
        }
    }

    /** Resolve and register the verified native mechanism once per Launcher class loader. */
    @SuppressLint("PrivateApi")
    private class Platform(simulator: Class<*>) {
        val params = simulator.classLoader!!.loadClass("com.android.quickstep.util.TransformParams")
        private val surfaceTransaction = simulator.classLoader!!.loadClass("com.android.quickstep.util.SurfaceTransaction")
        val apply = simulator.getMethod("apply", params)
        val bounds = simulator.getMethod("getCurrentRect")
        val crop = simulator.getMethod("getCurrentCropRect")
        val radius = simulator.getMethod("getScaledCornerRadius")
        private val getTransaction = surfaceTransaction.getMethod("getTransaction")
        private val submit = params.getMethod("applySurfaceParams", surfaceTransaction)

        init {
            val methods = simulator.methods.filter { it.name == "apply" &&
                it.returnType == Void.TYPE && (it.parameterTypes.contentEquals(arrayOf(params)) ||
                it.parameterTypes.contentEquals(arrayOf(params, surfaceTransaction))) }
            check(methods.contains(apply) && getTransaction.returnType == SurfaceControl.Transaction::class.java &&
                submit.returnType == Void.TYPE && bounds.returnType == RectF::class.java &&
                crop.returnType == RectF::class.java && radius.returnType == Float::class.javaPrimitiveType)
            val hooks = mutableListOf<XC_MethodHook.Unhook>()
            try {
                methods.forEach { method ->
                    hooks += XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (simulators[param.thisObject]?.fence?.frozen == true) param.result = null
                        }
                    })
                }
                hooks += XposedBridge.hookMethod(submit, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        transactions[param.thisObject]?.let { source ->
                            source.submitting(getTransaction.invoke(param.args[0]) as SurfaceControl.Transaction)
                        }
                    }
                })
            } catch (error: Throwable) {
                hooks.forEach { it.unhook() }
                throw error
            }
        }
    }
}
