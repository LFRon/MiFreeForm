package com.buildsession.betterYAMF.xposed.adapters.common.system

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ParcelableColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Parcel
import android.os.Binder
import android.os.IBinder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.buildsession.betterYAMF.common.ipc.Protocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buildsession.betterYAMF.common.model.EntrySnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android Parcel/GraphicBuffer tests; JVM stubs cannot verify FD transport or ownership. */
@RunWith(AndroidJUnit4::class)
class EntrySnapshotTransportTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
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

    @Test fun asynchronousReadUsesOnewayBothWaysAndPreservesBufferOwnership() {
        val source = source()
        EntrySnapshotTransport.Lease(source).use { lease ->
            var requested = false
            val broker = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    assertEquals(IBinder.FIRST_CALL_TRANSACTION + 1, code)
                    assertNull(reply)
                    assertEquals(IBinder.FLAG_ONEWAY, flags and IBinder.FLAG_ONEWAY)
                    requested = true
                    return lease.transact(code, data, reply, flags)
                }
            }
            val intent = Intent("test.entry").also(lease::attachTo).apply {
                putExtras(Bundle().apply { putBinder(Protocol.EXTRA_ENTRY_SNAPSHOT_TOKEN, broker) })
            }
            assertFalse(intent.hasFileDescriptors())
            val completed = CountDownLatch(1)
            var value: EntrySnapshotTransport.Result? = null
            instrumentation.runOnMainSync {
                EntrySnapshotTransport.request(intent, Process.myUid(), Handler(Looper.getMainLooper())) {
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                    value = it; completed.countDown()
                }
                assertNull("Results cannot reenter the request call", value)
            }
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertTrue(requested)
            val frame = (value as EntrySnapshotTransport.Result.Ready).frame
            try {
                assertEquals(source.buffer.id, frame.buffer.id)
                assertEquals(source.bounds, frame.bounds)
                lease.close()
                assertFalse(frame.buffer.isClosed)
            } finally { frame.close() }
        }
    }

    @Test fun cancellationWinsAgainstAnAlreadyQueuedPixelCallbackAndTerminatesOnce() {
        EntrySnapshotTransport.Lease(source()).use { lease ->
            val intent = Intent("test.entry").also(lease::attachTo)
            val values = mutableListOf<EntrySnapshotTransport.Result>()
            instrumentation.runOnMainSync {
                val operation = EntrySnapshotTransport.request(intent, Process.myUid(), Handler(Looper.getMainLooper()), values::add)
                operation.cancel()
                operation.cancel()
            }
            instrumentation.waitForIdleSync()
            assertEquals(listOf(EntrySnapshotTransport.Result.Superseded), values)
        }
    }

    @Test fun unavailablePixelsReturnFailureWithoutCallingTheLegacyRead() {
        val lease = EntrySnapshotTransport.Lease(source())
        val intent = Intent("test.entry").also(lease::attachTo)
        lease.close()
        val completed = CountDownLatch(1)
        var value: EntrySnapshotTransport.Result? = null
        instrumentation.runOnMainSync {
            EntrySnapshotTransport.request(intent, Process.myUid(), Handler(Looper.getMainLooper())) {
                value = it; completed.countDown()
            }
        }
        assertTrue(completed.await(3, TimeUnit.SECONDS))
        assertTrue(value is EntrySnapshotTransport.Result.Failed)
    }

    @Test fun callbackUidMustMatchTheNegotiatedSource() {
        EntrySnapshotTransport.Lease(source()).use { lease ->
            val values = mutableListOf<EntrySnapshotTransport.Result>()
            instrumentation.runOnMainSync {
                val operation = EntrySnapshotTransport.request(Intent("test.entry").also(lease::attachTo),
                    Process.myUid() + 1, Handler(Looper.getMainLooper()), values::add)
                operation.cancel()
            }
            instrumentation.waitForIdleSync()
            assertEquals(listOf(EntrySnapshotTransport.Result.Superseded), values)
        }
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
