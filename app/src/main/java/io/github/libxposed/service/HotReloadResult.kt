package io.github.libxposed.service

/**
 * Dex compatibility shim for `io.github.libxposed:service:102.0.0`.
 *
 * Upstream declares this type as a Java record. ART only supports records natively from API 34, and
 * AGP's external-library dexer refuses to desugar them for a lower `minSdk`
 * ("Attempt to create a global synthetic for 'Record desugaring' without a global-synthetics
 * consumer"). The published `classes.jar` therefore carries a class this app cannot package, so the
 * record is removed there and this class reproduces its public surface — constructor, `status()`,
 * `message()`, `Status`, and the package-private `from(int, String)` factory used by
 * [XposedService]'s hot reload callback.
 *
 * This module does not use hot reload; the type only has to exist and behave like the record.
 */
class HotReloadResult(val status: Status, val message: String?) {
    enum class Status { SUCCEEDED, FAILED, UNSUPPORTED, IN_PROGRESS, PROCESS_DIED }

    fun status(): Status = status

    fun message(): String? = message

    override fun equals(other: Any?): Boolean =
        this === other || (other is HotReloadResult && status == other.status && message == other.message)

    override fun hashCode(): Int = 31 * status.hashCode() + (message?.hashCode() ?: 0)

    override fun toString(): String = "HotReloadResult[status=$status, message=$message]"

    companion object {
        /** Package-private in the upstream record; public here because Kotlin cannot express it. */
        @JvmStatic
        fun from(code: Int, message: String?): HotReloadResult {
            val status = when (code) {
                IXposedService.HOT_RELOAD_SUCCEEDED -> Status.SUCCEEDED
                IXposedService.HOT_RELOAD_FAILED -> Status.FAILED
                IXposedService.HOT_RELOAD_UNSUPPORTED -> Status.UNSUPPORTED
                IXposedService.HOT_RELOAD_IN_PROGRESS -> Status.IN_PROGRESS
                IXposedService.HOT_RELOAD_PROCESS_DIED -> Status.PROCESS_DIED
                else -> throw XposedService.ServiceException("Invalid hot reload status code: $code")
            }
            return HotReloadResult(status, message)
        }
    }
}
