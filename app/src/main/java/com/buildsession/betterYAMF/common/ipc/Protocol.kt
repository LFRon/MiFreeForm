package com.buildsession.betterYAMF.common.ipc

/**
 * 模块 ↔ 应用 ↔ Launcher 注入侧的常量协议（广播动作 / extras / 来源 / 窗口契约）。
 *
 * 分属中立包：流程（YAMFManager）、宿主（AppWindow）、实现（FreeformBackend）、
 * Launcher 组件（HookLauncher/ManagedTaskFilter）与 app 侧接收器共引，
 * 不再挂靠任何具体实现类（协议值保持历史不变）。
 */
object Protocol {

    // —— 广播动作 ——
    const val ACTION_GET_LAUNCHER_CONFIG = "com.buildsession.betterYAMF.ACTION_GET_LAUNCHER_CONFIG"
    const val ACTION_RECEIVE_LAUNCHER_CONFIG = "com.buildsession.betterYAMF.ACTION_RECEIVE_LAUNCHER_CONFIG"
    const val ACTION_OPEN_APP = "com.buildsession.betterYAMF.action.OPEN_APP"
    const val ACTION_CURRENT_TO_WINDOW = "com.buildsession.betterYAMF.action.CURRENT_TO_WINDOW"
    const val ACTION_OPEN_APP_LIST = "com.buildsession.betterYAMF.action.OPEN_APP_LIST"
    const val ACTION_OPEN_IN_YAMF = "com.buildsession.betterYAMF.ACTION_OPEN_IN_YAMF"
    const val ACTION_RECENTS_GESTURE = "com.buildsession.betterYAMF.action.RECENTS_GESTURE"
    const val ACTION_MANAGED_TASKS = "com.buildsession.betterYAMF.action.MANAGED_TASKS"
    const val ACTION_QUERY_MANAGED_TASKS = "com.buildsession.betterYAMF.action.QUERY_MANAGED_TASKS"
    const val ACTION_DUMP_GEOMETRY = "com.buildsession.betterYAMF.action.DUMP_GEOMETRY"
    const val ACTION_RESET_ALL_WINDOW = "com.buildsession.betterYAMF.ui.window.action.ACTION_RESET_ALL_WINDOW"
    const val ACTION_WINDOW_PRESENTED = "com.buildsession.betterYAMF.action.WINDOW_PRESENTED"
    const val ACTION_SOURCE_RELEASED = "com.buildsession.betterYAMF.action.SOURCE_RELEASED"
    const val ACTION_CANCEL_RECENTS_ENTRY = "com.buildsession.betterYAMF.action.CANCEL_RECENTS_ENTRY"
    const val ACTION_QUERY_ENTRY_CAPABILITIES = "com.buildsession.betterYAMF.action.QUERY_ENTRY_CAPABILITIES"
    const val ACTION_ENTRY_CAPABILITIES = "com.buildsession.betterYAMF.action.ENTRY_CAPABILITIES"
    const val EXTRA_ENTRY_PROTOCOL = "entryProtocol"
    const val EXTRA_ENTRY_SNAPSHOT_TOKEN = "entrySnapshotToken"
    const val ENTRY_PROTOCOL = 2
    // Optional v2 negotiation fields. No existing action, status or parcel order is changed.
    const val EXTRA_SOURCE_EPOCH = "entrySourceEpoch"
    const val EXTRA_DESTINATION_EPOCH = "entryDestinationEpoch"
    const val EXTRA_CAPABILITY_QUERY_ID = "entryCapabilityQueryId"
    const val EXTRA_ASYNC_SNAPSHOT = "entryAsyncSnapshot"
    const val EXTRA_LEGACY_ENTRY = "entryLegacyAfterNative"

    // —— extras ——
    const val EXTRA_COMPONENT_NAME = "componentName"
    const val EXTRA_USER_ID = "userId"
    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_SUPPRESS_MS = "suppressMs"
    const val EXTRA_FROM_DROP_ZONE = "fromDropZone"
    const val EXTRA_DROP_X = "dropX"
    const val EXTRA_DROP_Y = "dropY"
    const val EXTRA_LAUNCHER_ROTATION = "launcherRotation"
    const val EXTRA_SOURCE_BOUNDS = "sourceBounds"
    const val EXTRA_RECENTS_HANDOFF_COMPLETE = "recentsHandoffComplete"
    const val EXTRA_SOURCE_PACKAGE = "sourcePackage"
    const val EXTRA_HANDOFF_ID = "handoffId"
    const val EXTRA_COMMAND_STATUS = "commandStatus"
    const val EXTRA_MANAGED_TASK_IDS = "managedTaskIds"
    const val EXTRA_HOOK_RECENT = "hookRecent"
    const val EXTRA_HOOK_TASKBAR = "hookTaskbar"

    // —— 打开来源 ——
    const val SOURCE_UNSPECIFIED = 0
    const val SOURCE_RECENT = 1
    const val SOURCE_TASKBAR = 2
    const val SOURCE_POPUP = 3

    // —— 窗口契约（宿主写标题，实现按标题匹配触摸洞） ——
    const val CHROME_WINDOW_TITLE = "reYAMF_chrome"
}
