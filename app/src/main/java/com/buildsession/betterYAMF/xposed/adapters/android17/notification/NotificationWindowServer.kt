package com.buildsession.betterYAMF.xposed.adapters.android17.notification

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import com.buildsession.betterYAMF.ipc.INotificationWindowBridge
import com.buildsession.betterYAMF.ipc.INotificationWindowCallback
import com.buildsession.betterYAMF.window.api.CommandStatus
import com.buildsession.betterYAMF.window.host.WindowHost
import com.buildsession.betterYAMF.window.runtime.WindowRuntimes
import com.buildsession.betterYAMF.xposed.adapters.common.organizer.NotificationTaskStage
import com.buildsession.betterYAMF.xposed.adapters.common.organizer.NotificationTaskSelection
import com.buildsession.betterYAMF.xposed.adapters.common.organizer.SmoothTaskOrganizer
import com.buildsession.betterYAMF.xposed.adapters.common.system.ShellOwnershipServer
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookAfter
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookBefore
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** LOS 24 notification AD. One-way authenticated ingress, unique native launch correlation and
 * real scene-completion egress. This owns launch resources, not geometry/mode/animation policy.
 */
internal object NotificationWindowServer {
    private const val TAG = "reYAMF_Notification"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val sender by lazy { Executors.newSingleThreadExecutor { work -> Thread(work, "yamf-notification-send") } }
    private lateinit var native: NotificationServerBindings
    @Volatile private var notificationService: Any? = null
    @Volatile private var installed = false
    @Volatile private var generationAvailable = true
    private val pending = ConcurrentHashMap<IBinder, Launch>()
    private val suppressCancellation = ThreadLocal<Launch?>()
    val capability: IBinder? get() = bridge.takeIf { installed && generationAvailable && notificationService != null }

    private class Launch(val id: Long, val uid: Int, val pid: Int, val callback: INotificationWindowCallback,
        val record: NotificationRecordLease, val notification: StatusBarNotification, val rank: Int, val count: Int) {
        val cookie: IBinder = Binder()
        var host: WindowHost? = null
        var stage: NotificationTaskStage? = null
        @Volatile var sent = false
        @Volatile var sending = false
        @Volatile var finished = false
        var sendCompleted = false
        var selectedTask: NotificationTaskSelection? = null
        @Volatile var claimed = false
        var reusedHost = false
        var death: IBinder.DeathRecipient? = null
        var deadline: Runnable? = null
    }

    fun install(loader: ClassLoader) {
        if (installed) return
        val bindings = NotificationServerBindings(loader)
        val hooks = mutableListOf<NativeHooks.Registration>()
        try {
            hooks += bindings.newRecord.hookAfter { call -> if (call.throwable == null) {
                runCatching { NotificationGeneration.stamp(bindings.getSbn.invoke(call.thisObject) as StatusBarNotification) }
                    .onFailure { generationAvailable = false; logWarning(TAG, "Notification update generation unavailable", it) }
            } }
            hooks += bindings.onStart.hookAfter { call -> if (call.throwable == null) notificationService = call.thisObject }
            hooks += bindings.cancel.hookBefore { call ->
                val launch = suppressCancellation.get() ?: return@hookBefore
                val sbn = launch.notification
                if (call.thisObject === notificationService && call.args[2] == sbn.packageName &&
                    call.argument(3) == sbn.tag && call.args[4] == sbn.id && call.args[8] == sbn.userId &&
                    call.args[9] == 1) call.result = null
            }
            hooks += bindings.taskInfo.hookAfter { call ->
                if (call.throwable != null || pending.isEmpty() || bindings.leafTask.invoke(call.thisObject) != true) return@hookAfter
                // WM calls fillTaskInfo under its own lock. Only copy immutable correlation facts;
                // the serial host/IPC/notification lock is never entered from that call stack.
                val info = call.args[0] as android.app.TaskInfo
                if (bindings.taskDisplay.invoke(call.thisObject) != 0) return@hookAfter
                val cookies = bindings.cookies.get(info) as List<*>
                cookies.mapNotNull { pending[it]?.takeIf { launch -> !launch.finished && !launch.claimed } }.forEach { launch ->
                    val selection = NotificationTaskSelection(info.taskId, call.thisObject, bindings.taskToken.get(info)!!)
                    main.post { if (!launch.finished && !launch.claimed) { launch.selectedTask = selection; claim(launch) } }
                }
            }
            native = bindings
            installed = true
            log(TAG) { "LOS 24 notification server hooks installed" }
        } catch (error: Throwable) {
            hooks.asReversed().forEach { hook -> runCatching { hook.unhook() }
                .onFailure { logWarning(TAG, "notification server hook rollback failed", it) } }
            throw error
        }
    }

    private val bridge = object : INotificationWindowBridge.Stub() {
        override fun launch(requestId: Long, notification: StatusBarNotification, original: PendingIntent,
            fillInIntent: Intent?, nativeOptions: Bundle, rank: Int, count: Int, callback: INotificationWindowCallback) {
            val uid = Binder.getCallingUid(); val pid = Binder.getCallingPid()
            if (!installed || !ShellOwnershipServer.ready || !ShellOwnershipServer.matchesCaller(uid, pid)) return
            val options = Bundle(nativeOptions)
            main.post {
                var operation: Launch? = null
                try {
                    val metrics = com.buildsession.betterYAMF.xposed.adapters.common.system.SystemBarMetrics.sceneMetrics()
                    if (!generationAvailable || !WindowRuntimes.acceptsNotificationDisplay(metrics) || !SmoothTaskOrganizer.entryReady) {
                        callback.onResult(requestId, -1)
                        return@post
                    }
                    check(requestId > 0L && callback.asBinder().isBinderAlive && SmoothTaskOrganizer.entryReady)
                    check(original.isActivity && notification.notification.contentIntent == original)
                    // Validate against the currently posted server object before retaining its identity.
                    val record = captureRecord(notification, original) ?: run {
                        log(TAG) { "notification replaced before staging request=$requestId" }
                        callback.onResult(requestId, -1)
                        return@post
                    }
                    check(pending.values.none { it.id == requestId && it.pid == pid })
                    val launch = Launch(requestId, uid, pid, callback, record, notification, rank, count)
                    operation = launch
                    pending[launch.cookie] = launch
                    val death = IBinder.DeathRecipient { main.post { finish(launch, if (launch.sending) -2 else -1) } }
                    launch.death = death
                    callback.asBinder().linkToDeath(death, 0)
                    val timeout = Runnable { logWarning(TAG, "notification launch timed out request=$requestId sent=${launch.sending}"); finish(launch, if (launch.sending) -2 else -1) }
                    launch.deadline = timeout
                    main.postDelayed(timeout, 8000L) // Fault bound only. No delay controls normal startup.
                    val host = YAMFManager.prepareNotificationHost()
                    launch.host = host
                    val layout = host.normalLayout()
                    val stage = SmoothTaskOrganizer.createNotificationStage(layout)
                    launch.stage = stage
                    val activityOptions = native.optionsFromBundle.invoke(null, options) as ActivityOptions
                    native.launchRoot.invoke(activityOptions, stage.token)
                    native.launchCookie.invoke(activityOptions, launch.cookie)
                    native.launchMode.invoke(activityOptions, 5)
                    val v = layout.logicalViewport
                    native.launchBounds.invoke(activityOptions, android.graphics.Rect(v.left, v.top, v.right, v.bottom))
                    val finalOptions = activityOptions.toBundle()
                    log(TAG) { "notification staged request=$requestId stage=${stage.taskId} viewport=$v" }
                    launch.sending = true // Any exception/timeout from now on must NEVER resend the PendingIntent.
                    sender.execute {
                        val result = runCatching {
                            check(!launch.finished) { "Launch was cancelled before send" }
                            native.resumeSwitches.invoke(native.activityManagerService.invoke(null))
                            native.sendPending.invoke(original, Instances.systemContext, 0, fillInIntent, null, null, null, finalOptions) as Int
                        }
                        main.post {
                            launch.sendCompleted = true
                            if (launch.finished) {
                                cleanup(launch, -2)
                                return@post
                            }
                            result.fold({ code ->
                                if (native.startSucceeded.invoke(null, code) != true) finish(launch, -2)
                                else {
                                    launch.sent = true
                                    deliver(launch, 0)
                                    log(TAG) { "notification intent accepted request=$requestId result=$code" }
                                    claim(launch)
                                }
                            }, { error -> logWarning(TAG, "notification intent failed request=$requestId", error); finish(launch, -2) })
                        }
                    }
                } catch (error: Throwable) {
                    logWarning(TAG, "notification staging failed request=$requestId", error)
                    operation?.let { finish(it, if (it.sending) -2 else -1) }
                        ?: runCatching { callback.onResult(requestId, -1) }
                }
            }
        }

        override fun cancel(requestId: Long) {
            val uid = Binder.getCallingUid(); val pid = Binder.getCallingPid()
            if (!ShellOwnershipServer.matchesCaller(uid, pid)) return
            main.post { pending.values.toList().filter { it.id == requestId && it.pid == pid }
                .forEach { finish(it, if (it.sending) -3 else -1) } }
        }
    }

    private fun claim(launch: Launch) {
        val task = launch.selectedTask ?: return
        if (launch.finished || launch.claimed || !launch.sent || task.taskId <= 0) return
        launch.claimed = true
        try {
            val host = checkNotNull(launch.host)
            val stage = checkNotNull(launch.stage)
            val selected = SmoothTaskOrganizer.withNotificationTaskSelection(task) {
                SmoothTaskOrganizer.captureNotificationRestoration(stage, task.taskId)
                YAMFManager.claimNotificationTask(host, task.taskId, launch.id, { owner ->
                    // Publish before activation: a rejected/failed callback may be synchronous.
                    launch.host = owner
                    launch.reusedHost = owner !== host
                }) { status ->
                    if (!launch.finished) {
                        if (status == CommandStatus.DONE) runCatching { completeNotification(launch) }
                            .onFailure { logWarning(TAG, "notification completion dispatch failed request=${launch.id}", it) }
                        finish(launch, when (status) { CommandStatus.DONE -> 1; CommandStatus.SUPERSEDED -> -3; CommandStatus.FAILED -> -2 })
                    }
                }
            }
            launch.host = selected
            // The task has left the temporary container, which must not survive as a restore parent.
            if (!launch.reusedHost) SmoothTaskOrganizer.disposeNotificationStage(stage)
        } catch (error: Throwable) { logWarning(TAG, "notification task claim failed request=${launch.id}", error); finish(launch, -2) }
    }

    private fun deliver(launch: Launch, result: Int) {
        runCatching { launch.callback.onResult(launch.id, result) }
            .onFailure { logWarning(TAG, "notification result peer unavailable request=${launch.id}", it) }
    }

    private fun finish(launch: Launch, result: Int) {
        if (launch.finished) return
        launch.finished = true
        launch.deadline?.let(main::removeCallbacks)
        launch.death?.let { runCatching { launch.callback.asBinder().unlinkToDeath(it, 0) } }
        // Cancellation cannot undo an in-flight native send. Keep its container alive until that
        // call returns, then clean any late child in the background instead of falling fullscreen.
        if (!launch.sending || launch.sendCompleted) cleanup(launch, result)
        else if (launch.host?.currentTaskId == -1) { launch.host?.onDestroy(false); launch.host = null }
        deliver(launch, result)
        log(TAG) { "notification finished request=${launch.id} task=${launch.selectedTask?.taskId} result=$result" }
    }

    private fun cleanup(launch: Launch, result: Int) {
        pending.remove(launch.cookie, launch)
        runCatching { launch.stage?.let(SmoothTaskOrganizer::disposeNotificationStage) }
            .onFailure { logWarning(TAG, "notification stage cleanup failed request=${launch.id}", it) }
        if (result != 1) {
            runCatching {
                val host = launch.host ?: return@runCatching
                if (launch.claimed && host.currentTaskId > 0) host.cancelActivation(launch.id)
                else host.onDestroy(false)
            }.onFailure { logWarning(TAG, "notification host cleanup failed request=${launch.id}", it) }
        }
    }

    private fun captureRecord(sbn: StatusBarNotification, original: PendingIntent): NotificationRecordLease? {
        val service = notificationService ?: return null
        return synchronized(checkNotNull(native.lock.get(service))) {
            val record = (native.records.get(service) as Map<*, *>)[sbn.key] ?: return null
            val current = native.getSbn.invoke(record) as StatusBarNotification
            val generation = NotificationGeneration.read(sbn)
            if (generation <= 0L || NotificationGeneration.read(current) != generation ||
                current.notification.contentIntent != original || !current.isClearable || current.isOngoing)
                null else NotificationRecordLease(sbn.key, record)
        }
    }

    private fun completeNotification(launch: Launch) {
        val service = notificationService ?: return
        val handler = native.worker.get(service) as Handler
        handler.post {
            runCatching {
                synchronized(checkNotNull(native.lock.get(service))) {
                    val consumed = launch.record.consume({ (native.records.get(service) as Map<*, *>)[launch.notification.key] }, {
                        // removeFromNotificationListsLocked also removes ENQUEUED records by key.
                        // An update waiting for ranking/posting must survive this click as well.
                        val current = (native.records.get(service) as Map<*, *>)[launch.notification.key]
                        (native.enqueued.get(service) as Iterable<*>).any { queued -> queued != null && queued !== current &&
                            (native.getSbn.invoke(queued) as StatusBarNotification).key == launch.notification.key }
                    }) { record ->
                        val current = native.getSbn.invoke(record) as StatusBarNotification
                        if (NotificationGeneration.read(current) != NotificationGeneration.read(launch.notification) ||
                            !current.isClearable || current.isOngoing || current.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return@consume
                        val visibility = native.obtainVisibility.invoke(null, current.key, launch.rank, launch.count, true)
                        // Reuse native click metrics/listeners, but suppress its unguarded, by-key
                        // asynchronous AUTO_CANCEL. Cancellation below checks identity at execution.
                        suppressCancellation.set(launch)
                        try { native.click.invoke(native.delegate.get(service), launch.uid, launch.pid, current.key, visibility) }
                        finally { suppressCancellation.remove() }
                        native.registerClick.invoke(native.usage.get(service), record)
                        val wasPosted = native.remove.invoke(service, record) as Boolean
                        // Same primitives as CancelNotificationRunnable, under the same native lock.
                        // Never cascade to group children or remove a replacement with the same key.
                        native.cancelLocked.invoke(service, record, false, 1 /* REASON_CLICK */, launch.rank,
                            launch.count, wasPosted, null, SystemClock.elapsedRealtime())
                        native.updateLights.invoke(native.attention.get(service))
                        native.shortcut.get(service)?.let { native.updateShortcut.invoke(it, record, true) }
                    }
                    log(TAG) { "notification cancellation checked request=${launch.id} consumed=$consumed" }
                }
            }.onFailure { logWarning(TAG, "notification completion failed request=${launch.id}", it) }
        }
    }
}
