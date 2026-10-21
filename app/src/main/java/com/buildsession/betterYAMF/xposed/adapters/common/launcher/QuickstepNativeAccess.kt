package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.graphics.PointF
import android.os.Bundle
import android.view.InputEvent
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Reviewed A17 display-scoped Quickstep mechanism. A concrete adapter chooses this explicitly;
 * finding a Handler class never causes an automatic fallback between input generations.
 * All fixed members are resolved before the first Hook is installed. Resource identities remain
 * gesture-scoped and are read afresh; no controller or Activity is cached across handoffs.
 */
internal class QuickstepNativeAccess(private val loader: ClassLoader, signature: LauncherSourceRetirement.Signature) {
    private fun type(name: String) = loader.loadClass(name)
    private val eventDisplay = InputEvent::class.java.getMethod("getDisplayId").accessible()
    private val handler = type("com.android.quickstep.TouchInteractionHandler")
    private val service = type("com.android.quickstep.TouchInteractionService")
    private val serviceHandler = service.getDeclaredField("handler").accessible()
    val onCreate: Method = service.getDeclaredMethod("onCreate").accessible()
    val onDestroy: Method = service.getDeclaredMethod("onDestroy").accessible()
    val onInput: Method = handler.getDeclaredMethod("onInputEvent", InputEvent::class.java).accessible()
    val launcherOnCreate: Method = type("com.android.launcher3.Launcher")
        .getDeclaredMethod("onCreate", Bundle::class.java).accessible()

    private val inputResource = handler.getDeclaredMethod("getInputResource", Int::class.javaPrimitiveType).accessible()
    private val gesture = inputResource.returnType.getDeclaredField("gestureState").accessible()
    private val consumer = inputResource.returnType.getDeclaredField("consumer").accessible()
    private val repository = handler.getDeclaredField("mTaskAnimationManagerRepository").accessible()
    private val managerGet = repository.type.getMethod("get", Int::class.javaPrimitiveType).accessible()
    private val controller = type("com.android.quickstep.TaskAnimationManager").getDeclaredField("mController").accessible()
    private val animations = checkNotNull(CapturedRecentsAnimation.resolve(controller.type)) {
        "No reviewed recents controller completion ABI"
    }.also { check(it.canForceFinish) { "No bounded recents force-finish ABI" } }
    private val activeConsumer = type("com.android.quickstep.InputConsumer").getMethod("getActiveConsumerInHierarchy").accessible()
    private val consumerCleanup = type("com.android.quickstep.InputConsumer").getMethod("onConsumerAboutToBeSwitched").accessible()
    private val otherConsumer = type("com.android.quickstep.inputconsumers.OtherActivityInputConsumer")
    private val interaction = otherConsumer.getDeclaredField("mInteractionHandler").accessible()
    private val consumerGesture = otherConsumer.getDeclaredField("mGestureState").accessible()

    private val gestureType = type("com.android.quickstep.GestureState")
    private val topTask = gestureType.getMethod("getTopRunningTaskId").accessible()
    private val runningTask = gestureType.getMethod("getRunningTask").accessible()
    private val home = runningTask.returnType.getMethod("isHomeTask").accessible()
    private val containerInterface = gestureType.getMethod("getContainerInterface").accessible()
    private val createdContainer = containerInterface.returnType.getMethod("getCreatedContainer").accessible()
    private val containerType = type("com.android.quickstep.views.RecentsViewContainer")
    private val overview = containerType.getMethod("getOverviewPanel").accessible()
    private val stateManager = type("com.android.launcher3.statemanager.StatefulContainer")
        .getMethod("getStateManager").accessible()
    private val retirement = LauncherSourceRetirement.resolve(stateManager.returnType, signature)
    private val recents = type("com.android.quickstep.views.RecentsView")
    private val recentsController = recents.getMethod("getRecentsAnimationController").accessible()
    private val orientedState = recents.getMethod("getPagedViewOrientedState").accessible()
    private val rotation = orientedState.returnType.getMethod("getRecentsActivityRotation").accessible()
    private val swipeHandler = type("com.android.quickstep.AbsSwipeUpHandler")
    // Pixel 907 and LoS 24.0: onGestureStarted sets this after native slop/pilfer. This does
    // not assert onRecentsAnimationStart or pixel readiness, which have separate barriers.
    private val gestureStarted = swipeHandler.getDeclaredField("mGestureStarted").accessible()
    val gestureEnd: Method = swipeHandler
        .getDeclaredMethod("onGestureEnded", Float::class.javaPrimitiveType,
            PointF::class.java, Boolean::class.javaPrimitiveType).accessible()
    val sources = QuickstepSourceAccess(loader)
    val taskFilter = QuickstepTaskFilterAccess(loader)

    init {
        check(onInput.returnType == Void.TYPE && gestureEnd.returnType == Void.TYPE)
        check(topTask.returnType == Int::class.javaPrimitiveType && home.returnType == Boolean::class.javaPrimitiveType)
        check(serviceHandler.type == handler && consumerGesture.type == gestureType)
    }

    /** TIS.onCreate obtains this handler from its current SysUIConnectionComponent in both
     * reviewed builds. Late callbacks from a destroyed component must not drive a new service.
     */
    fun inputOwnerOf(service: Any): Any = checkNotNull(serviceHandler.get(service))
    fun consumerGestureOf(consumer: Any?): Any? =
        consumer?.takeIf(otherConsumer::isInstance)?.let(consumerGesture::get)

    fun displayIdOf(event: InputEvent): Int = eventDisplay.invoke(event) as Int

    fun gestureStateOf(owner: Any, event: InputEvent): Any? =
        inputResource.invoke(owner, displayIdOf(event))?.let { gesture.get(it) }

    fun activeConsumerOf(owner: Any, event: InputEvent): Any? {
        val resource = inputResource.invoke(owner, displayIdOf(event)) ?: return null
        val current = consumer.get(resource) ?: return null
        return activeConsumer.invoke(current) ?: current
    }

    fun swipeOf(currentConsumer: Any): Any? =
        if (otherConsumer.isInstance(currentConsumer)) interaction.get(currentConsumer) else null

    fun isTracking(swipe: Any): Boolean = gestureStarted.getBoolean(swipe)

    fun animationControllerOf(owner: Any, displayId: Int): Any? =
        managerGet.invoke(repository.get(owner), displayId)?.let { controller.get(it) }

    fun captureController(controller: Any?): CapturedRecentsAnimation? = controller?.let(animations::bind)
    fun cleanupConsumer(consumer: Any) { consumerCleanup.invoke(consumer) }

    fun containerOf(gesture: Any): Any? = containerInterface.invoke(gesture)?.let { createdContainer.invoke(it) }
    fun overviewOf(container: Any): Any? = overview.invoke(container)
    fun retirementOf(container: Any): LauncherSourceRetirement = retirement.bind(checkNotNull(stateManager.invoke(container)))
    fun taskId(gesture: Any): Int = topTask.invoke(gesture) as Int
    fun isHome(gesture: Any): Boolean = runningTask.invoke(gesture)?.let { home.invoke(it) == true } == true
    fun controllerOf(recentsView: Any): Any? = recentsController.invoke(recentsView)
    fun homeRotation(recentsView: Any): Int = (rotation.invoke(orientedState.invoke(recentsView)) as Int).also { check(it in 0..3) }
}

private fun Method.accessible(): Method = apply { isAccessible = true }
private fun Field.accessible(): Field = apply { isAccessible = true }
