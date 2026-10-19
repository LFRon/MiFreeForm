package com.buildsession.betterYAMF.xposed.hook.system

import com.buildsession.betterYAMF.window.core.CommandStatus
import org.junit.Assert.*
import org.junit.Test

class RenderedFrameCommitTest {
    @Test fun commitBeforeHwuiCannotAcknowledgeAnUndrawnImage() {
        val fence = RenderedFrameCommit()
        assertNull(fence.committed())
        assertEquals(CommandStatus.DONE, fence.drawn(true))
        assertNull(fence.drawn(true))
        assertNull(fence.committed())
    }

    @Test fun hwuiBeforeCommitStillWaitsForTheSceneTransaction() {
        val fence = RenderedFrameCommit()
        assertNull(fence.drawn(true))
        assertEquals(CommandStatus.DONE, fence.committed())
    }

    @Test fun committedMetadataWithoutABufferMustRetryRatherThanReleasePixels() {
        val fence = RenderedFrameCommit()
        assertNull(fence.committed())
        assertEquals(CommandStatus.SUPERSEDED, fence.drawn(false))
        assertNull(fence.committed())
        assertNull(fence.drawn(true))
    }

    @Test fun aFailedDrawCannotBeMadeSuccessfulByALateCommit() {
        val fence = RenderedFrameCommit()
        assertEquals(CommandStatus.SUPERSEDED, fence.drawn(false))
        assertNull(fence.committed())
    }
}
