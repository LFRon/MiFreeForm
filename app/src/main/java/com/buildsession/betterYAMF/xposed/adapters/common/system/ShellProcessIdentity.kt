package com.buildsession.betterYAMF.xposed.adapters.common.system

/** Native process address, confined to AD. SystemUI's android.uid.systemui is allocated by PM;
 * being a privileged system package does not make its UID android.uid.system (1000).
 */
internal data class ShellProcessIdentity(val uid: Int, val pid: Int) {
    init { require(uid >= 0 && pid > 0) { "Invalid Shell process address" } }

    fun matches(callerUid: Int, callerPid: Int): Boolean = uid == callerUid && pid == callerPid

    companion object {
        fun acceptsHost(packageName: String?, processName: String?, isSystemUser: Boolean,
            processUid: Int, applicationUid: Int): Boolean =
            packageName == ShellOwnershipProtocol.PACKAGE && processName == packageName &&
                isSystemUser && processUid >= 0 && processUid == applicationUid

        /** expectedUid must come from PM for the delivery user, after the permission gate.
         * Missing shared identity is rejected here even though Launcher v2 allows older senders.
         */
        fun acceptsSender(userId: Int, actualPackage: String?, actualUid: Int,
            expectedUid: Int): Boolean = userId == 0 &&
            actualPackage == ShellOwnershipProtocol.PACKAGE && actualUid >= 0 && actualUid == expectedUid
    }
}
