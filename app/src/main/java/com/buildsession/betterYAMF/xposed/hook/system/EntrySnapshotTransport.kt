package com.buildsession.betterYAMF.xposed.hook.system

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ParcelableColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.common.model.EntrySnapshot

/**
 * LoS BroadcastController:1961 rejects Intent file descriptors. Broadcasts carry only this Binder
 * capability; the HardwareBuffer is transferred by a one-shot Binder reply, never by AMS extras.
 * The source retains its reference until bridge commit/cancellation; the receiver owns a duplicate.
 */
internal object EntrySnapshotTransport {
    private const val DESCRIPTOR = "com.buildsession.betterYAMF.EntrySnapshot"
    private const val READ = IBinder.FIRST_CALL_TRANSACTION

    class Lease(private var frame: EntrySnapshot?) : Binder(), AutoCloseable {
        private var consumed = false

        fun attachTo(intent: Intent) {
            val snapshot = checkNotNull(frame)
            intent.putExtra(Protocol.EXTRA_SOURCE_BOUNDS, snapshot.bounds)
            intent.putExtras(Bundle().apply { putBinder(Protocol.EXTRA_ENTRY_SNAPSHOT_TOKEN, this@Lease) })
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            if (code != READ) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            checkNotNull(reply)
            val caller = getCallingUid()
            check(caller == Process.SYSTEM_UID || caller == Process.myUid()) { "Entry pixels belong to the system destination" }
            synchronized(this) {
                reply.writeNoException()
                val snapshot = frame
                val available = !consumed && snapshot != null && !snapshot.buffer.isClosed
                reply.writeBoolean(available)
                if (available && snapshot != null) {
                    consumed = true
                    reply.writeTypedObject(snapshot.buffer, 0)
                    reply.writeTypedObject(snapshot.colorSpace, 0)
                    reply.writeTypedObject(snapshot.bounds, 0)
                    reply.writeFloat(snapshot.cornerRadius)
                    reply.writeFloat(snapshot.velocityX)
                    reply.writeFloat(snapshot.velocityY)
                    reply.writeLong(snapshot.capturedAtNanos)
                }
            }
            return true
        }

        override fun close() = synchronized(this) {
            frame?.close()
            frame = null
        }
    }

    fun read(intent: Intent): EntrySnapshot? {
        val token = intent.extras?.getBinder(Protocol.EXTRA_ENTRY_SNAPSHOT_TOKEN) ?: return null
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        var buffer: HardwareBuffer? = null
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            if (!token.transact(READ, request, reply, 0)) return null
            reply.readException()
            if (!reply.readBoolean()) return null
            buffer = requireNotNull(reply.readTypedObject(HardwareBuffer.CREATOR))
            val color = requireNotNull(reply.readTypedObject(ParcelableColorSpace.CREATOR))
            val bounds = requireNotNull(reply.readTypedObject(Rect.CREATOR))
            check(!buffer.isClosed && !bounds.isEmpty && buffer.width > 1 && buffer.height > 1)
            return EntrySnapshot(buffer, color, bounds, reply.readFloat(), reply.readFloat(),
                reply.readFloat(), reply.readLong()).also { buffer = null }
        } finally {
            buffer?.close()
            request.recycle()
            reply.recycle()
        }
    }

    /** wrapHardwareBuffer retains its native reference; only the transport reference closes. */
    fun consumeBitmap(frame: EntrySnapshot): Bitmap = try {
        requireNotNull(Bitmap.wrapHardwareBuffer(frame.buffer, frame.colorSpace.colorSpace))
    } finally {
        frame.close()
    }
}
