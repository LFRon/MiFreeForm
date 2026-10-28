package com.buildsession.betterYAMF.xposed.adapters.android17.notification

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Pinned to LOS 24.0 frameworks/base 5c6d4b35. Resolve before installing any notification hook;
 * failure withdraws only this optional capability, never Shell isolation or Recents entry.
 */
@android.annotation.SuppressLint("PrivateApi") // Injected SystemUI AD; exact source/optimized-APK signatures are preflighted.
internal class NotificationUiBindings(loader: ClassLoader) {
    val starter = loader.loadClass("com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter")
    val entry = loader.loadClass("com.android.systemui.statusbar.notification.collection.NotificationEntry")
    val row = loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow")
    val bodyClick = loader.loadClass("com.android.systemui.statusbar.notification.NotificationClicker")
        .method("onClick", android.view.View::class.java)
    val dismissAction = loader.loadClass("com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter\$OnKeyguardDismissedAction")
    val afterKeyguard = starter.method("performActionAfterKeyguardDismissed", entry, dismissAction)
    val onDismiss = dismissAction.getMethod("onDismiss", PendingIntent::class.java,
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    val removeHun = starter.method("removeHunAfterClick", row)
    val sbn = entry.field("mSbn")
    val ranking = entry.field("mRanking")
    val canBubble = android.service.notification.NotificationListenerService.Ranking::class.java.getMethod("canBubble")
    val remoteText = entry.field("remoteInputText")
    private val lastActionUpField = loader.loadClass("com.android.systemui.statusbar.notification.row.ActivatableNotificationView")
        .let { view ->
            runCatching { view.field("mLastActionUpTime") }.getOrElse { view.field("mLastActionUpTimeNs") }
        }
    private val lastActionUpNanos = lastActionUpField.name.endsWith("Ns")
    val context = starter.field("mContext")
    val executor = starter.field("mUiBgExecutor")
    val remoteInput = starter.field("mRemoteInputManager")
    val remoteController = remoteInput.type.field("mRemoteInputController")
    val spinning = remoteController.type.field("mSpinning")
    val shade = starter.field("mShadeController")
    val keyguard = starter.field("mKeyguardManager")
    val keyguardState = starter.field("mKeyguardStateController")
    val keyguardShowing = loader.loadClass("com.android.systemui.statusbar.policy.KeyguardStateControllerImpl").field("mShowing")
    val panel = starter.field("mPanelExpansionInteractor")
    val isFullyCollapsed = panel.type.getMethod("isFullyCollapsed")
    val visibility = starter.field("mVisibilityProvider")
    val clickNotifier = starter.field("mClickNotifier")
    val notifierService = clickNotifier.type.field("barService")
    // R8 removes the always-true argument and the render-interface method on the reviewed APK.
    // These are two pinned LOS build shapes, selected once at capability initialization.
    val getVisibility = try {
        visibility.type.getMethod("obtain", entry, Boolean::class.javaPrimitiveType)
    } catch (_: NoSuchMethodException) {
        loader.loadClass("com.android.systemui.statusbar.notification.collection.provider.NotificationVisibilityProviderImpl")
            .method("obtain", entry)
    }
    val notifyClick = clickNotifier.type.method("onNotificationClick", String::class.java, getVisibility.returnType)
    val remoteClick = loader.loadClass("com.android.internal.statusbar.IStatusBarService\$Stub\$Proxy")
        .method("onNotificationClick", String::class.java, getVisibility.returnType)
    val isDeviceLocked = android.app.KeyguardManager::class.java.method("isDeviceLocked", Int::class.javaPrimitiveType!!)
    val userIdentifier = android.os.UserHandle::class.java.method("getIdentifier")
    val isMedia = android.app.Notification::class.java.method("isMediaNotification")
    val userJobFlag = android.app.Notification::class.java.getDeclaredField("FLAG_USER_INITIATED_JOB").getInt(null)
    // Google 17 removed collapseOnMainThread; its reviewed ShadeController exposes the same
    // main-thread collapse operation as animateCollapseShadeForcedDelayed(void).
    val collapseShade = loader.loadClass("com.android.systemui.shade.ShadeController").let { type ->
        runCatching { type.getMethod("collapseOnMainThread") }
            .getOrElse { type.getMethod("animateCollapseShadeForcedDelayed") }
    }
    // Optional: Google's SystemUIGoogle.apk does not define com.android.systemui.assist.AssistManager
    // (it is only referenced), so hiding an assist session there is unavailable. It is never part
    // of the click capture itself; a missing binding only skips that cosmetic step.
    private val assistBinding: AssistBinding? = runCatching {
        val assist = starter.field("mAssistManagerLazy")
        val assistUtils = loader.loadClass("com.android.systemui.assist.AssistManager").field("mAssistUtils")
        AssistBinding(assist, assistUtils, assistUtils.type.method("hideCurrentSession"))
    }.getOrNull()

    class AssistBinding internal constructor(
        internal val manager: Field, internal val utils: Field, internal val hide: Method
    )

    fun hideAssist(starter: Any, lazy: Method) {
        val binding = assistBinding ?: return
        binding.hide.invoke(binding.utils.get(lazy.invoke(binding.manager.get(starter))))
    }
    val lazyGet = loader.loadClass("dagger.Lazy").getMethod("get")
    val visibilityRank = getVisibility.returnType.getField("rank")
    val visibilityCount = getVisibility.returnType.getField("count")
    val recycleVisibility = getVisibility.returnType.getMethod("recycle")
    val callerDisplay = ActivityOptions::class.java.method("setCallerDisplayId", Int::class.javaPrimitiveType!!)
    val sourceInfo = ActivityOptions::class.java.method("setSourceInfo", Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!)
    val sourceNotification = loader.loadClass("android.app.ActivityOptions\$SourceInfo").getField("TYPE_NOTIFICATION").getInt(null)
    val splashStyle = ActivityOptions::class.java.method("setSplashScreenStyle", Int::class.javaPrimitiveType!!)

    fun visibilityFor(entry: Any, starter: Any): Any = if (getVisibility.parameterCount == 2)
        getVisibility.invoke(visibility.get(starter), entry, true)!! else getVisibility.invoke(visibility.get(starter), entry)!!

    /** Native stores either uptime millis (LOS) or uptime nanos (AOSP 17). */
    fun lastActionUpMs(row: Any): Long {
        val value = lastActionUpField.getLong(row)
        return if (lastActionUpNanos) value / 1_000_000L else value
    }

    fun clearLastActionUp(row: Any) = lastActionUpField.setLong(row, 0L)

    fun restoreLastActionUp(row: Any, eventTimeMs: Long) {
        lastActionUpField.setLong(row, if (lastActionUpNanos) eventTimeMs * 1_000_000L else eventTimeMs)
    }
}

@android.annotation.SuppressLint("PrivateApi") // system_server AD, not an application hidden-API bypass or a generic SDK match.
internal class NotificationServerBindings(loader: ClassLoader) {
    val nms = loader.loadClass("com.android.server.notification.NotificationManagerService")
    val record = loader.loadClass("com.android.server.notification.NotificationRecord")
    val newRecord = record.getDeclaredConstructor(android.content.Context::class.java,
        android.service.notification.StatusBarNotification::class.java, android.app.NotificationChannel::class.java)
    val onStart = nms.getDeclaredMethod("onStart")
    val lock = nms.field("mNotificationLock")
    val records = nms.field("mNotificationsByKey")
    val enqueued = nms.field("mEnqueuedNotifications")
    val worker = nms.field("mHandler")
    val delegate = nms.field("mNotificationDelegate")
    val getSbn = record.getDeclaredMethod("getSbn").apply { isAccessible = true }
    val visibility = loader.loadClass("com.android.internal.statusbar.NotificationVisibility")
    val obtainVisibility = visibility.getMethod("obtain", String::class.java, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    val click = loader.loadClass("com.android.server.notification.NotificationDelegate")
        .getMethod("onNotificationClick", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java, visibility)
    val cancel = nms.method("cancelNotification", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
        String::class.java, String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
        loader.loadClass("com.android.server.notification.NotificationManagerService\$FlagChecker"), Boolean::class.javaPrimitiveType!!,
        Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
        loader.loadClass("com.android.server.notification.ManagedServices\$ManagedServiceInfo"))
    val remove = nms.method("removeFromNotificationListsLocked", record)
    val cancelLocked = nms.method("cancelNotificationLocked", record, Boolean::class.javaPrimitiveType!!,
        Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
        Boolean::class.javaPrimitiveType!!, String::class.java, Long::class.javaPrimitiveType!!)
    val usage = nms.field("mUsageStats")
    val registerClick = loader.loadClass("com.android.server.notification.NotificationUsageStats").method("registerClickedByUser", record)
    val attention = nms.field("mAttentionHelper")
    val updateLights = attention.type.method("updateLightsLocked")
    val shortcut = nms.field("mShortcutHelper")
    val updateShortcut = shortcut.type.method("maybeListenForShortcutChangesForBubbles", record, Boolean::class.javaPrimitiveType!!)
    val taskInfo = loader.loadClass("com.android.server.wm.Task").method("fillTaskInfo", android.app.TaskInfo::class.java,
        Boolean::class.javaPrimitiveType!!, loader.loadClass("com.android.server.wm.TaskDisplayArea"))
    val cookies = android.app.TaskInfo::class.java.getField("launchCookies")
    val taskToken = android.app.TaskInfo::class.java.getField("token")
    val leafTask = loader.loadClass("com.android.server.wm.Task").method("isLeafTask")
    val taskDisplay = loader.loadClass("com.android.server.wm.TaskFragment").method("getDisplayId")
    val optionsFromBundle = ActivityOptions::class.java.method("fromBundle", Bundle::class.java)
    val launchRoot = ActivityOptions::class.java.method("setLaunchRootTask", loader.loadClass("android.window.WindowContainerToken"))
    val launchCookie = ActivityOptions::class.java.method("setLaunchCookie", IBinder::class.java)
    val launchMode = ActivityOptions::class.java.method("setLaunchWindowingMode", Int::class.javaPrimitiveType!!)
    val launchBounds = ActivityOptions::class.java.getMethod("setLaunchBounds", android.graphics.Rect::class.java)
    val sendPending = PendingIntent::class.java.method("sendAndReturnResult", android.content.Context::class.java,
        Int::class.javaPrimitiveType!!, Intent::class.java, PendingIntent.OnFinished::class.java,
        android.os.Handler::class.java, String::class.java, Bundle::class.java)
    val activityManagerService = loader.loadClass("android.app.ActivityManager").method("getService")
    val resumeSwitches = loader.loadClass("android.app.IActivityManager").getMethod("resumeAppSwitches")
    val startSucceeded = android.app.ActivityManager::class.java.method("isStartResultSuccessful", Int::class.javaPrimitiveType!!)
}

private fun Class<*>.field(name: String): Field = getDeclaredField(name).apply { isAccessible = true }
private fun Class<*>.method(name: String, vararg params: Class<*>): Method =
    getDeclaredMethod(name, *params).apply { isAccessible = true }
