package com.buildsession.betterYAMF.common.model

import android.graphics.ParcelableColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer

/** One owned buffer reference. Transport uses framework parcelables, never module parcelables. */
class EntrySnapshot(
    val buffer: HardwareBuffer,
    val colorSpace: ParcelableColorSpace,
    val bounds: Rect,
    val cornerRadius: Float,
    val velocityX: Float,
    val velocityY: Float,
    val capturedAtNanos: Long
) : AutoCloseable {
    override fun close() = buffer.close()
}
