package com.buildsession.betterYAMF.xposed.adapters.common.system

/** Separate from Launcher v2: this handshake authorizes Shell isolation before mode-5 creation.
 * The Binder marker identifies this system_server owner, never a reusable task id.
 */
internal object ShellOwnershipProtocol {
    const val QUERY = "com.buildsession.betterYAMF.action.QUERY_SHELL_OWNER"
    const val OWNER = "com.buildsession.betterYAMF.action.SHELL_OWNER"
    const val READY = "com.buildsession.betterYAMF.action.SHELL_READY"
    const val COOKIE = "shellOwnerCookie"
    /** Optional, independently installed notification launch capability; not Launcher v2. */
    const val NOTIFICATION_BRIDGE = "notificationWindowBridge"
    const val PEER = "shellPeerLifetime"
    const val VERSION = "shellIsolationVersion"
    const val BUILD = "shellIsolationBuild"
    const val AVAILABLE = "shellIsolationAvailable"
    /** Optional diagnostic from an authenticated peer; never used to grant capability. */
    const val FAILURE = "shellIsolationFailure"
    const val PID = "shellIsolationPid"
    const val CURRENT = 1
    const val PACKAGE = "com.android.systemui"
}
