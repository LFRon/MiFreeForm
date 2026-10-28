package com.buildsession.betterYAMF.xposed.adapters

/** AD metadata, never a CORE policy or a claim that an arbitrary ROM satisfies the ports. */
internal enum class AdapterEvidence { DEVICE_BASELINE, SOURCE_REVIEWED, PENDING }

internal data class AdapterIdentity(
    val id: String,
    val sdk: Int,
    val launcherPackage: String,
    val evidence: AdapterEvidence,
    val referenceRom: String,
    val launcherVersionCode: Long? = null
)

/** Current user's Home and the configured Quickstep host are separate platform facts.
 * A ROM label or an installed (but unused) Launcher cannot substitute for either fact.
 */
internal data class LauncherRuntimeIdentity(
    val sdk: Int,
    val packageName: String,
    val processName: String,
    val launcherVersionCode: Long?,
    val defaultHomePackage: String?,
    val recentsPackage: String?
)

/** A concrete Android-major + Launcher implementation. install executes ordinary Hook code. */
internal interface LauncherAdapter {
    val identity: AdapterIdentity
    fun install(classLoader: ClassLoader)
}

/** Public Build labels are available in both injected processes. A missing label is not proof of
 * ROM compatibility; referenceRom records the actual audit baseline. Private ro.* version props
 * cannot be used for eligibility: this device's SELinux denies them even to system_server.
 */
internal data class PlatformIdentity(val product: String = "", val fingerprint: String = "",
    val display: String = "") {
    /** Space-joined public labels; distribution profiles match against this and nothing else. */
    val labels: String get() = "$product $fingerprint $display"

    val excluded: Boolean get() = EXCLUDED_ROM.containsMatchIn(labels)

    private companion object {
        val EXCLUDED_ROM = Regex("(?i)(?:^|[^a-z0-9])(?:avium(?:ui)?|(?:project[ _-]*)?infinity(?:[ _-]*x)?)(?:$|[^a-z0-9])")
    }
}

internal sealed interface LauncherSelection {
    data class Selected(val adapter: LauncherAdapter) : LauncherSelection
    data class Unavailable(val reason: String) : LauncherSelection
}
