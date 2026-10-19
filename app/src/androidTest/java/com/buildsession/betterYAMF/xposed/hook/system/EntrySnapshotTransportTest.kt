package com.buildsession.betterYAMF.xposed.hook.system

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ParcelableColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buildsession.betterYAMF.common.model.EntrySnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android Parcel/GraphicBuffer tests; JVM stubs cannot verify FD transport or ownership. */
@RunWith(AndroidJUnit4::class)
class EntrySnapshotTransportTest {
    private fun source() = EntrySnapshot(
        HardwareBuffer.create(16, 32, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT),
        ParcelableColorSpace(ColorSpace.get(ColorSpace.Named.DISPLAY_P3)),
        Rect(493, 113, 922, 1055), 29f, 2000f, -350f, 123456789L
    )

    private fun transported(intent: Intent): Intent {
        val parcel = Parcel.obtain()
        return try {
            intent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    @Test fun broadcastContainsOnlyACapabilityAndTransfersPixelsOnce() {
        val source = source()
        EntrySnapshotTransport.Lease(source).use { lease ->
            val intent = transported(Intent("test.entry").also(lease::attachTo))
            assertFalse("AMS rejects any FD in broadcast extras", intent.hasFileDescriptors())
            requireNotNull(EntrySnapshotTransport.read(intent)).use { received ->
                assertEquals(source.buffer.id, received.buffer.id)
                assertEquals(source.bounds, received.bounds)
                assertEquals(source.colorSpace, received.colorSpace)
                assertEquals(29f, received.cornerRadius, 0f)
                assertEquals(2000f, received.velocityX, 0f)
                assertEquals(-350f, received.velocityY, 0f)
                assertEquals(123456789L, received.capturedAtNanos)
            }
            assertNull("A repeated broadcast cannot consume or duplicate the source again", EntrySnapshotTransport.read(intent))
        }
    }

    @Test fun receivedBufferAndHardwareImageOutliveTheSourceLease() {
        val source = source()
        val lease = EntrySnapshotTransport.Lease(source)
        val intent = transported(Intent("test.entry").also(lease::attachTo))
        val received = requireNotNull(EntrySnapshotTransport.read(intent))
        lease.close()
        assertTrue(source.buffer.isClosed)
        assertFalse(received.buffer.isClosed)
        val image = EntrySnapshotTransport.consumeBitmap(received)
        try {
            assertTrue(received.buffer.isClosed)
            assertEquals(Bitmap.Config.HARDWARE, image.config)
            assertEquals(16, image.width)
            assertEquals(32, image.height)
            assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), image.colorSpace)
        } finally { image.recycle() }
    }

    @Test fun cancellationBeforeTheReadReleasesPixelsAndRejectsTheLateReceiver() {
        val source = source()
        val lease = EntrySnapshotTransport.Lease(source)
        val intent = transported(Intent("test.entry").also(lease::attachTo))
        lease.close()
        lease.close()
        assertTrue(source.buffer.isClosed)
        assertNull(EntrySnapshotTransport.read(intent))
    }
}
