package com.buildsession.betterYAMF.xposed.adapters.android17.notification

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.DisplayMetrics
import android.view.View
import android.window.SplashScreen
import com.buildsession.betterYAMF.ipc.INotificationWindowBridge
import com.buildsession.betterYAMF.ipc.INotificationWindowCallback
import com.buildsession.betterYAMF.window.api.NotificationEntryFacts
import com.buildsession.betterYAMF.window.runtime.WindowRuntimes
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookBefore
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** LOS 24 SystemUI input AD. Preserve NotificationClicker's row/menu/guts checks and the native
 * unlock/remote-input/work-profile path. Wrap its retained post-unlock interface, rather than
 * private start/send helpers which R8 removes. ActivityOptions match LOS's unanimated branch;
 * CORE owns window placement/fade and the server owns identity-checked completion/cancellation.
 */
internal object NotificationWindowAdapter {
    private const val TAG = "reYAMF_Notification"
    private const val ROW_SCOPE = "yamf.notification.row.previous"
    private const val ENTERED_SCOPE = "yamf.notification.row.entered"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val requests = IdentityHashMap<Any, Click>() // Main thread only.
    private val rowScope = ThreadLocal<View?>()
    private class LocalReport(val service: Any, val key: String)
    private val localReport = ThreadLocal<LocalReport?>()
    private val ids = AtomicLong(android.os.SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L))
    @Volatile private var bridge: INotificationWindowBridge? = null
    private lateinit var native: NotificationUiBindings
    private var installed = false

    private class Click(val id: Long, val starter: Any, val entry: Any, val row: View,
        val originalAction: Any, val sbn: StatusBarNotification, val eventTime: Long,
        val peer: INotificationWindowBridge) {
        val generation = NotificationGeneration.read(sbn)
        var forwarded = false
        var finished = false
        var timeout: Runnable? = null
    }

    fun connect(capability: IBinder?) {
        bridge = capability?.takeIf { it.isBinderAlive }?.let(INotificationWindowBridge.Stub::asInterface)
    }

    fun install(loader: ClassLoader) {
        if (installed) return
        val bindings = NotificationUiBindings(loader)
        val hooks = mutableListOf<NativeHooks.Registration>()
        native = bindings
        try {
            hooks += NativeHooks.hookMethod(bindings.bodyClick, object : NativeHooks.Callback() {
                override fun beforeHookedMethod(param: NativeHooks.Call) {
                    if (Looper.myLooper() != Looper.getMainLooper() || bridge == null ||
                        !bindings.row.isInstance(param.argument(0))) return
                    param.setObjectExtra(ROW_SCOPE, rowScope.get())
                    param.setObjectExtra(ENTERED_SCOPE, true)
                    rowScope.set(param.args[0] as View)
                }
                override fun afterHookedMethod(param: NativeHooks.Call) {
                    if (param.getObjectExtra(ENTERED_SCOPE) != true) return
                    val previous = param.getObjectExtra(ROW_SCOPE) as? View
                    if (previous == null) rowScope.remove() else rowScope.set(previous)
                }
            })
            hooks += bindings.afterKeyguard.hookBefore { call ->
                val row = rowScope.get() ?: return@hookBefore
                if (bridge == null) return@hookBefore
                val entry = call.args[0]
                val starter = call.thisObject
                val action = call.args[1]
                // Native row checks and native keyguard dismissal continue unchanged. Only this
                // body click's eventual interface callback is wrapped, including delayed unlock.
                call.args[1] = Proxy.newProxyInstance(loader, arrayOf(bindings.dismissAction)) { proxy, method, args ->
                    when {
                        method == bindings.onDismiss -> {
                            val originalArgs = args ?: emptyArray()
                            var takenOver = false
                            val taken = try { capture(starter, entry, row, action, originalArgs) { takenOver = true } }
                            catch (error: Throwable) {
                                logWarning(TAG, "notification click adapter failed takenOver=$takenOver", error)
                                if (takenOver) {
                                    requests[entry]?.let { click ->
                                        runCatching { click.peer.cancel(click.id) }
                                        runCatching { finish(click, -2) }
                                    }
                                    false
                                } else null
                            }
                            taken ?: invokeAction(action, originalArgs)
                        }
                        method.name == "equals" -> proxy === args?.getOrNull(0)
                        method.name == "hashCode" -> System.identityHashCode(proxy)
                        method.name == "toString" -> "YAMF native notification action"
                        else -> throw UnsupportedOperationException(method.name)
                    }
                }
            }
            // NMS already received the identity-guarded native report. Keep the notifier's real
            // in-process listeners, suppressing only our synchronous duplicate remote report.
            hooks += bindings.remoteClick.hookBefore { call ->
                localReport.get()?.let { if (it.service === call.thisObject && it.key == call.argument(0)) call.result = null }
            }
            installed = true
            log(TAG) { "LOS 24 notification body-click/post-unlock hooks installed" }
        } catch (error: Throwable) {
            hooks.asReversed().forEach { hook -> runCatching { hook.unhook() }
                .onFailure { logWarning(TAG, "notification UI hook rollback failed", it) } }
            throw error
        }
    }

    /** null delegates to the unchanged native action. Non-null is its keyguard-defer result. */
    private fun capture(starter: Any, entry: Any, row: View, action: Any, args: Array<out Any?>,
        takeover: () -> Unit): Boolean? {
        if (Looper.myLooper() != Looper.getMainLooper() || row.display?.displayId != 0) return null
        val peer = bridge?.takeIf { it.asBinder().isBinderAlive } ?: return null
        val intent = args.getOrNull(0) as? PendingIntent ?: return null
        val sbn = native.sbn.get(entry) as StatusBarNotification
        if (args.getOrNull(3) == true || !eligible(starter, entry, sbn, intent, args.getOrNull(1) == true)) return null
        requests[entry]?.let { previous ->
            if (previous.generation == NotificationGeneration.read(sbn)) {
                takeover()
                return !fullyCollapsed(starter)
            }
            runCatching { previous.peer.cancel(previous.id) }
            finish(previous, -3) // A new message in the same row is a different click.
        }
        val eventTime = native.lastActionUpMs(row)
        val options = launchOptions(eventTime)
        val controller = native.remoteController.get(native.remoteInput.get(starter))
        val spinning = controller != null && (native.spinning.get(controller) as Map<*, *>).containsKey(sbn.key)
        val draft = native.remoteText.get(entry) as? CharSequence
        val fillIn = draft?.takeIf { it.isNotEmpty() && !spinning }
            ?.let { Intent().putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT, it.toString()) }
        val visibility = native.visibilityFor(entry, starter)
        val rank: Int; val count: Int
        try { rank = native.visibilityRank.getInt(visibility); count = native.visibilityCount.getInt(visibility) }
        finally { native.recycleVisibility.invoke(visibility) }
        val click = Click(ids.getAndIncrement(), starter, entry, row, action, sbn, eventTime, peer)
        // Finish all fallible fact/option extraction before taking over. From here an uncertain
        // Binder delivery must preserve the notification and must NEVER resend the PendingIntent.
        takeover()
        requests[entry] = click
        try {
            native.clearLastActionUp(row)
            val timeout = Runnable {
                if (!click.finished) {
                    logWarning(TAG, "notification click timed out request=${click.id}")
                    runCatching { click.peer.cancel(click.id) }
                    runCatching { finish(click, -2) }
                        .onFailure { logWarning(TAG, "notification timeout cleanup failed request=${click.id}", it) }
                }
            }
            click.timeout = timeout
            main.postDelayed(timeout, 10000L) // Fault bound, never a normal animation gate.
            // There is no native fullscreen animator to finish keyguard dismissal. Start the real
            // shade collapse and return only its actual defer state, never native animate=true.
            native.collapseShade.invoke(native.shade.get(starter))
            click.forwarded = true
            peer.launch(click.id, sbn, intent, fillIn, options, rank, count, object : INotificationWindowCallback.Stub() {
                override fun onResult(requestId: Long, result: Int) {
                    main.post {
                        if (requestId != click.id || click.finished || requests[entry] !== click) return@post
                        runCatching { if (result == 0) sent(click) else finish(click, result) }
                            .onFailure { logWarning(TAG, "notification result failed request=${click.id}", it); finish(click, -2) }
                    }
                }
            })
            log(TAG) { "notification click captured request=${click.id}" }
        } catch (error: Throwable) {
            logWarning(TAG, "notification bridge send failed request=${click.id}", error)
            finish(click, if (click.forwarded) -2 else -1)
        }
        return runCatching { !fullyCollapsed(starter) }.getOrDefault(false)
    }

    private fun invokeAction(action: Any, args: Array<out Any?>): Any? = try {
        native.onDismiss.invoke(action, *args)
    } catch (error: InvocationTargetException) { throw error.targetException }

    private fun fullyCollapsed(starter: Any) = native.isFullyCollapsed.invoke(native.panel.get(starter)) == true

    private fun eligible(starter: Any, entry: Any, sbn: StatusBarNotification, pending: PendingIntent, activity: Boolean): Boolean {
        if (NotificationGeneration.read(sbn) <= 0L) return false
        val context = native.context.get(starter) as Context
        val metrics = DisplayMetrics()
        context.getSystemService(DisplayManager::class.java).getDisplay(0)?.getRealMetrics(metrics) ?: return false
        val n = sbn.notification
        val profileUnlocked = native.isDeviceLocked.invoke(native.keyguard.get(starter), native.userIdentifier.invoke(pending.creatorUserHandle)) != true &&
            !native.keyguardShowing.getBoolean(native.keyguardState.get(starter))
        return WindowRuntimes.acceptsNotificationEntry(NotificationEntryFacts(metrics.widthPixels > metrics.heightPixels,
            activity && pending.isActivity && pending == n.contentIntent, sbn.isClearable, sbn.isOngoing,
            native.canBubble.invoke(native.ranking.get(entry)) == true, n.fullScreenIntent != null, n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            profileUnlocked, native.isMedia.invoke(n) == true ||
                n.flags and (Notification.FLAG_FOREGROUND_SERVICE or native.userJobFlag) != 0 ||
                n.category in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_TRANSPORT,
                    Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE, Notification.CATEGORY_ALARM)))
    }

    /** ActivityStarterUtils.kt createActivityOptions(displayId, null, ...), without a system
     * expansion animator. The server adds the unique launch cookie/root and CORE's task layout.
     */
    @android.annotation.SuppressLint("NewApi", "InlinedApi") // HookShell's exact SDK-37 check gates this AD.
    private fun launchOptions(eventTime: Long): Bundle = ActivityOptions.makeBasic().apply {
        native.callerDisplay.invoke(this, 0)
        native.splashStyle.invoke(this, SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR)
        setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        if (eventTime > 0L) native.sourceInfo.invoke(this, native.sourceNotification, eventTime)
    }.toBundle()

    private fun sent(click: Click) {
        // The native send can finish after this row has acquired another message. Never remove
        // that newer HUN, or collapse a shade the user reopened while the send was in flight.
        if (isCurrent(click)) native.removeHun.invoke(click.starter, click.row)
        (native.executor.get(click.starter) as Executor).execute {
            runCatching { native.hideAssist(click.starter, native.lazyGet) }
                .onFailure { logWarning(TAG, "hide assist after notification click failed", it) }
        }
    }

    private fun finish(click: Click, result: Int) {
        if (click.finished) return
        click.finished = true
        click.timeout?.let(main::removeCallbacks)
        if (requests[click.entry] === click) requests.remove(click.entry)
        val unchanged = isCurrent(click)
        if (result == -1 && unchanged) {
            // Explicit never-sent rejection may delegate once. Re-run native lock/remote-input
            // checks, since the phone/profile may have locked while staging. No capture scope.
            if (native.lastActionUpMs(click.row) == 0L) native.restoreLastActionUp(click.row, click.eventTime)
            // A local pre-send failure can reach here synchronously inside NotificationClicker.
            // Clear its scope so native fallback cannot wrap/capture itself recursively.
            val previous = rowScope.get()
            rowScope.remove()
            try { native.afterKeyguard.invoke(click.starter, click.entry, click.originalAction) }
            finally { if (previous != null) rowScope.set(previous) }
        } else if (result == 1 && unchanged) {
            val visibility = native.visibilityFor(click.entry, click.starter)
            val notifier = native.clickNotifier.get(click.starter)!!
            val previous = localReport.get()
            localReport.set(LocalReport(native.notifierService.get(notifier)!!, click.sbn.key))
            try { native.notifyClick.invoke(notifier, click.sbn.key, visibility) }
            finally {
                if (previous == null) localReport.remove() else localReport.set(previous)
                native.recycleVisibility.invoke(visibility)
            }
        }
        log(TAG) { "notification click complete request=${click.id} result=$result unchanged=$unchanged" }
    }

    private fun isCurrent(click: Click): Boolean {
        val current = native.sbn.get(click.entry) as StatusBarNotification
        return current.key == click.sbn.key && NotificationGeneration.read(current) == click.generation
    }
}
