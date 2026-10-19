package com.buildsession.betterYAMF.xposed.hook.launcher

import android.view.InputEvent
import de.robv.android.xposed.XposedHelpers

interface LauncherInputAnchor {
    val displayName: String
    val className: String
    fun gestureStateOf(handler: Any, event: InputEvent): Any?
    fun inputConsumerOf(handler: Any, event: InputEvent): Any?
    fun animationControllerOf(handler: Any, displayId: Int): Any?
}

object LauncherInputAnchors {
    private const val TOUCH_INTERACTION_HANDLER = "com.android.quickstep.TouchInteractionHandler"
    private const val TOUCH_INTERACTION_SERVICE = "com.android.quickstep.TouchInteractionService"

    fun resolve(classLoader: ClassLoader): LauncherInputAnchor? {
        val hasHandler = runCatching { classLoader.loadClass(TOUCH_INTERACTION_HANDLER) }.isSuccess
        if (hasHandler) return TouchInteractionHandlerAnchor
        val hasService = runCatching { classLoader.loadClass(TOUCH_INTERACTION_SERVICE) }.isSuccess
        if (hasService) return TouchInteractionServiceAnchor
        return null
    }

    object TouchInteractionHandlerAnchor : LauncherInputAnchor {
        override val displayName: String = "A17 TouchInteractionHandler"
        override val className: String = TOUCH_INTERACTION_HANDLER

        override fun gestureStateOf(handler: Any, event: InputEvent): Any? = runCatching {
            val displayId = XposedHelpers.callMethod(event, "getDisplayId") as Int
            val inputResource = XposedHelpers.callMethod(handler, "getInputResource", displayId)
            XposedHelpers.getObjectField(inputResource, "gestureState")
        }.getOrNull()

        override fun inputConsumerOf(handler: Any, event: InputEvent): Any? = runCatching {
            val displayId = XposedHelpers.callMethod(event, "getDisplayId") as Int
            val resource = XposedHelpers.callMethod(handler, "getInputResource", displayId)
            XposedHelpers.getObjectField(resource, "consumer")
        }.getOrNull()

        override fun animationControllerOf(handler: Any, displayId: Int): Any? = runCatching {
            val repository = XposedHelpers.getObjectField(handler, "mTaskAnimationManagerRepository")
            val manager = XposedHelpers.callMethod(repository, "get", displayId)
            XposedHelpers.getObjectField(manager, "mController")
        }.getOrNull()
    }

    object TouchInteractionServiceAnchor : LauncherInputAnchor {
        override val displayName: String = "legacy TouchInteractionService"
        override val className: String = TOUCH_INTERACTION_SERVICE

        override fun gestureStateOf(handler: Any, event: InputEvent): Any? =
            runCatching { XposedHelpers.getObjectField(handler, "mGestureState") }.getOrNull()

        override fun inputConsumerOf(handler: Any, event: InputEvent): Any? =
            runCatching { XposedHelpers.getObjectField(handler, "mConsumer") }.getOrNull()

        override fun animationControllerOf(handler: Any, displayId: Int): Any? = runCatching {
            val manager = XposedHelpers.getObjectField(handler, "mTaskAnimationManager")
            XposedHelpers.getObjectField(manager, "mController")
        }.getOrNull()
    }
}
