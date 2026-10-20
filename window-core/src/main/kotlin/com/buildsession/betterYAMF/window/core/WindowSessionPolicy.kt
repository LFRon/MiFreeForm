package com.buildsession.betterYAMF.window.core

/** One visible host per task; replacement transfers ownership without fullscreen release. */
object WindowSessionPolicy {
    fun replaces(requestedTaskId: Int, hostedTaskId: Int): Boolean =
        requestedTaskId > 0 && requestedTaskId == hostedTaskId
}
