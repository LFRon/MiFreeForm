package com.buildsession.betterYAMF.xposed.hook.launcher

import android.view.InputEvent
import de.robv.android.xposed.XposedHelpers

interface LauncherInputAnchor {
    val displayName: String
    val className: String
    fun gestureStateOf(handler: Any, event: InputEvent): Any?
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
    }

    object TouchInteractionServiceAnchor : LauncherInputAnchor {
        override val displayName: String = "legacy TouchInteractionService"
        override val className: String = TOUCH_INTERACTION_SERVICE

        override fun gestureStateOf(handler: Any, event: InputEvent): Any? =
            runCatching { XposedHelpers.getObjectField(handler, "mGestureState") }.getOrNull()
    }
}
