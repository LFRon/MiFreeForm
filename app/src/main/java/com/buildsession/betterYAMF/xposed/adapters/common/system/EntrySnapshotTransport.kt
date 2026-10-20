package com.buildsession.betterYAMF.xposed.adapters.common.system

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ParcelableColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Process
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.common.model.EntrySnapshot
import com.buildsession.betterYAMF.window.api.WindowOperation
import com.buildsession.betterYAMF.xposed.utils.logError
import com.buildsession.betterYAMF.xposed.utils.logWarning

/**
 * LoS BroadcastController:1961 rejects Intent file descriptors. Broadcasts carry only this Binder
 * capability; the HardwareBuffer is transferred by Binder, never by AMS extras. LoS Binder:205
 * requires system_server's outgoing result requests to be oneway with an oneway callback.
 * The source retains its reference until bridge commit/cancellation; the receiver owns a duplicate.
 */
internal object EntrySnapshotTransport {
    private const val DESCRIPTOR = "com.buildsession.betterYAMF.EntrySnapshot"
    private const val READ = IBinder.FIRST_CALL_TRANSACTION
    private const val READ_ASYNC = READ + 1
    private const val RESULT_DESCRIPTOR = "$DESCRIPTOR.Result"
    private const val DELIVER = IBinder.FIRST_CALL_TRANSACTION
    private const val TAG = "reYAMF_EntryV2"

    sealed interface Result {
        data class Ready(val frame: EntrySnapshot) : Result
        data class Failed(val error: Throwable? = null) : Result
        data object Superseded : Result
    }

    class Lease(private var frame: EntrySnapshot?) : Binder(), AutoCloseable {
        private var consumed = false

        fun attachTo(intent: Intent) {
            val snapshot = checkNotNull(frame)
            intent.putExtra(Protocol.EXTRA_SOURCE_BOUNDS, snapshot.bounds)
            intent.putExtra(Protocol.EXTRA_ASYNC_SNAPSHOT, true)
            intent.putExtras(Bundle().apply { putBinder(Protocol.EXTRA_ENTRY_SNAPSHOT_TOKEN, this@Lease) })
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            if (code != READ && code != READ_ASYNC) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            val caller = getCallingUid()
            check(caller == Process.SYSTEM_UID || caller == Process.myUid()) { "Entry pixels belong to the system destination" }
            if (code == READ_ASYNC) {
                check(flags and IBinder.FLAG_ONEWAY != 0) { "Pixel requests must be oneway" }
                val callback = requireNotNull(data.readStrongBinder())
                val response = Parcel.obtain()
                try {
                    response.writeInterfaceToken(RESULT_DESCRIPTOR)
                    writeFrame(response)
                    check(callback.transact(DELIVER, response, null, IBinder.FLAG_ONEWAY))
                } catch (error: Throwable) {
                    logError(TAG, "asynchronous snapshot delivery failed", error)
                } finally { response.recycle() }
            } else {
                // Existing v2 receivers retain their exact synchronous parcel layout. New
                // system receivers never select this operation, even for an old source lease.
                requireNotNull(reply).writeNoException()
                writeFrame(reply)
            }
            return true
        }

        private fun writeFrame(reply: Parcel) {
            synchronized(this) {
                val snapshot = frame
                val available = !consumed && snapshot != null && !snapshot.buffer.isClosed
                reply.writeBoolean(available)
                if (available) {
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
        }

        override fun close() = synchronized(this) {
            frame?.close()
            frame = null
        }
    }

    /** Owner-thread cancellation; all terminal results are delivered on that same Handler.
     * Late callbacks still deserialize and close their duplicate; they cannot revive a request.
     * Old peers must negotiate the legacy gesture fallback before native takeover, not block here.
     */
    fun request(intent: Intent, expectedUid: Int, handler: Handler, result: (Result) -> Unit): WindowOperation {
        check(Looper.myLooper() == handler.looper)
        require(expectedUid >= 0)
        val token = requireNotNull(intent.extras?.getBinder(Protocol.EXTRA_ENTRY_SNAPSHOT_TOKEN))
        require(intent.getBooleanExtra(Protocol.EXTRA_ASYNC_SNAPSHOT, false))
        return ReadRequest(token, expectedUid, handler, result).also { it.start() }
    }

    private class ReadRequest(private val token: IBinder, private val expectedUid: Int,
        private val handler: Handler, private val result: (Result) -> Unit) : WindowOperation {
        private var finished = false // Handler-owned; Binder callbacks only post immutable results.
        private var linked = false
        private val timeout = Runnable { finish(Result.Failed(IllegalStateException("Snapshot transfer timed out"))) }
        private val death = IBinder.DeathRecipient { deliver(Result.Failed(IllegalStateException("Snapshot source died"))) }
        private val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(RESULT_DESCRIPTOR); return true }
                if (code != DELIVER) return super.onTransact(code, data, reply, flags)
                if (getCallingUid() != expectedUid || flags and IBinder.FLAG_ONEWAY == 0) {
                    logWarning(TAG, "reject snapshot callback from uid=${getCallingUid()}")
                    return true
                }
                val value = try {
                    data.enforceInterface(RESULT_DESCRIPTOR)
                    readFrame(data)?.let(Result::Ready) ?: Result.Failed()
                } catch (error: Throwable) { Result.Failed(error) }
                deliver(value)
                return true
            }
        }

        fun start() {
            handler.postDelayed(timeout, 5000L) // Fault bound, never an animation delay.
            val data = Parcel.obtain()
            try {
                token.linkToDeath(death, 0)
                linked = true
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeStrongBinder(callback)
                check(token.transact(READ_ASYNC, data, null, IBinder.FLAG_ONEWAY)) { "Async snapshot operation unavailable" }
            } catch (error: Throwable) { deliver(Result.Failed(error)) }
            finally { data.recycle() }
        }

        private fun deliver(value: Result) {
            if (!handler.post { finish(value) } && value is Result.Ready) value.frame.close()
        }

        private fun finish(value: Result) {
            check(Looper.myLooper() == handler.looper)
            if (finished) { if (value is Result.Ready) value.frame.close(); return }
            finished = true
            handler.removeCallbacks(timeout)
            if (linked) {
                linked = false
                runCatching { token.unlinkToDeath(death, 0) }
                    .onFailure { logWarning(TAG, "snapshot death listener cleanup failed", it) }
            }
            try { result(value) } catch (error: Throwable) {
                if (value is Result.Ready) value.frame.close()
                logError(TAG, "snapshot result delivery failed", error)
            }
        }

        override fun cancel() {
            if (Looper.myLooper() == handler.looper) finish(Result.Superseded)
            else deliver(Result.Superseded)
        }
    }

    /** Legacy wire-layout compatibility only. The system receiver always uses request(). */
    fun read(intent: Intent): EntrySnapshot? {
        val token = intent.extras?.getBinder(Protocol.EXTRA_ENTRY_SNAPSHOT_TOKEN) ?: return null
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            if (!token.transact(READ, request, reply, 0)) return null
            reply.readException()
            return readFrame(reply)
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    private fun readFrame(data: Parcel): EntrySnapshot? {
        if (!data.readBoolean()) return null
        var buffer: HardwareBuffer? = null
        try {
            buffer = requireNotNull(data.readTypedObject(HardwareBuffer.CREATOR))
            val color = requireNotNull(data.readTypedObject(ParcelableColorSpace.CREATOR))
            val bounds = requireNotNull(data.readTypedObject(Rect.CREATOR))
            check(!buffer.isClosed && !bounds.isEmpty && buffer.width > 1 && buffer.height > 1)
            val radius = data.readFloat(); val vx = data.readFloat(); val vy = data.readFloat(); val time = data.readLong()
            require(radius.isFinite() && radius >= 0f && vx.isFinite() && vy.isFinite() && time >= 0L)
            return EntrySnapshot(buffer, color, bounds, radius, vx, vy, time).also { buffer = null }
        } finally { buffer?.close() }
    }

    /** wrapHardwareBuffer retains its native reference; only the transport reference closes. */
    fun consumeBitmap(frame: EntrySnapshot): Bitmap = try {
        requireNotNull(Bitmap.wrapHardwareBuffer(frame.buffer, frame.colorSpace.colorSpace))
    } finally {
        frame.close()
    }
}
