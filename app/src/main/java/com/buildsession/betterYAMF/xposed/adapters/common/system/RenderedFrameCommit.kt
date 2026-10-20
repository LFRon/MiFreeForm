package com.buildsession.betterYAMF.xposed.adapters.common.system

import com.buildsession.betterYAMF.window.api.CommandStatus

/** HWUI production and SF transaction commit can arrive in either order. Both are required. */
internal class RenderedFrameCommit {
    private var produced: Boolean? = null
    private var committed = false
    private var finished = false

    fun drawn(didProduceBuffer: Boolean): CommandStatus? {
        produced = didProduceBuffer
        return result()
    }

    fun committed(): CommandStatus? {
        committed = true
        return result()
    }

    private fun result(): CommandStatus? {
        if (finished) return null
        val status = when {
            produced == false -> CommandStatus.SUPERSEDED
            produced == true && committed -> CommandStatus.DONE
            else -> return null
        }
        finished = true
        return status
    }
}
