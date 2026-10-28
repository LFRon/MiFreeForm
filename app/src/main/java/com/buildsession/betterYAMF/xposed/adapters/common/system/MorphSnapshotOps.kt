package com.buildsession.betterYAMF.xposed.adapters.common.system

import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.view.SurfaceControl
import android.view.View
import com.buildsession.betterYAMF.common.logging.AppLog
import com.buildsession.betterYAMF.xposed.utils.Reflect

/**
 * 平台像素捕获适配器。最近任务入口用真实任务缓冲区承接源画面，宿主在公共场景中
 * 通过 HWUI 绘制；这里不决定轨迹或交接时序。捕获失败显式返回 null，由入口选择降级。
 */
object MorphSnapshotOps {

    private const val TAG = "reYAMF_MorphSnap"
    private data class CaptureApi(val type: Class<*>, val legacy: Boolean)
    private val captureApis by lazy {
        listOfNotNull(
            runCatching { CaptureApi(Class.forName("android.window.ScreenCaptureInternal"), false) }.getOrNull(),
            runCatching { CaptureApi(Class.forName("android.window.ScreenCapture"), true) }.getOrNull()
        )
    }

    /** 截图结果（buffer 生命周期由调用方管理；用毕 close）。 */
    class Captured(
        val buffer: HardwareBuffer,
        val colorSpace: ColorSpace?,
        val width: Int,
        val height: Int
    )

    /**
     * 截取视图所在窗口表面（整窗）。
     * LoS 24（A17）源码核对：层截图 API 已迁至
     * `android.window.ScreenCaptureInternal#captureLayers(SurfaceControl, Rect, float, int)`
     * （ScreenCaptureInternal.java:144，返回 ScreenshotHardwareBuffer）；
     * 旧代（≤A14 风格）`ScreenCapture$LayerCaptureArgs$Builder` 作为签名容错兜底
     * —— 能力探测/代际差异只允许出现在适配器层。
     */
    fun captureWindow(view: View): Captured? = runCatching {
        val surface = ViewSurfaceOps.windowSurface(view) ?: run {
            AppLog.warn(TAG, "capture: window surface unavailable")
            return null
        }
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            AppLog.warn(TAG, "capture: invalid view size ${w}x$h")
            return null
        }
        val crop = android.graphics.Rect(0, 0, w, h)
        captureLayer(surface, crop)
    }.onFailure { AppLog.error(TAG, "capture failed", it) }.getOrNull()

    /** Captures task CHILDREN in task-buffer coordinates, excluding the recents leash transform.
     * LoS ScreenCaptureInternal.LayerCaptureArgs.Builder defaults childrenOnly to true (:740).
     */
    fun captureLayer(surface: SurfaceControl, crop: android.graphics.Rect): Captured? = runCatching {
        if (!surface.isValid || crop.isEmpty) return null
        for (api in captureApis) {
            captureVia(api.type, surface, crop, api.legacy)?.let { return it }
        }
        AppLog.warn(TAG, "capture: no compatible API produced a task buffer")
        null
    }.onFailure { AppLog.error(TAG, "layer capture failed", it) }.getOrNull()

    /** 单次截图尝试（[legacy]=旧 LayerCaptureArgs 形态）。 */
    private fun captureVia(
        captureClass: Class<*>,
        surface: SurfaceControl,
        crop: android.graphics.Rect,
        legacy: Boolean
    ): Captured? = runCatching {
        val shot: Any? = if (legacy) {
            val builderClass = Class.forName("android.window.ScreenCapture\$LayerCaptureArgs\$Builder")
            val builder = Reflect.newInstance(builderClass, surface)
            Reflect.callMethod(builder, "setSourceCrop", crop)
            val args = Reflect.callMethod(builder, "build")
            Reflect.callStaticMethod(captureClass, "captureLayers", args)
        } else {
            Reflect.callStaticMethod(
                captureClass,
                "captureLayers",
                surface,
                crop,
                1.0f,
                android.graphics.PixelFormat.RGBA_8888
            )
        } ?: return null
        val buffer = Reflect.callMethod(shot, "getHardwareBuffer") as? HardwareBuffer
        if (buffer == null || buffer.isClosed) {
            AppLog.debug(TAG) { "capture: hardware buffer null/closed (legacy=$legacy)" }
            return null
        }
        val colorSpace = runCatching {
            Reflect.callMethod(shot, "getColorSpace") as? ColorSpace
        }.getOrNull()
        Captured(buffer, colorSpace, buffer.width, buffer.height)
    }.onFailure {
        AppLog.debug(TAG) { "capture API attempt failed (legacy=$legacy): $it" }
    }.getOrNull()

    /**
     * 创建承载快照的缓冲表面（容器由后端提供：与 leash 同层、相对层序压其上）。
     * 返回句柄；调用方负责 setBuffer/show/变换/移除。
     */
    fun createSnapshotSurface(
        parent: SurfaceControl,
        name: String,
        width: Int,
        height: Int
    ): SurfaceControl? = runCatching {
        val builder = SurfaceControl.Builder()
        Reflect.callMethod(builder, "setName", name)
        Reflect.callMethod(builder, "setCallsite", "reYAMF#createSnapshotSurface")
        Reflect.callMethod(builder, "setParent", parent)
        Reflect.callMethod(builder, "setBufferSize", width, height)
        Reflect.callMethod(builder, "setFormat", android.graphics.PixelFormat.TRANSLUCENT)
        Reflect.callMethod(builder, "build") as? SurfaceControl
    }.getOrNull()

    /** 把截图写进快照表面（一次事务内）。 */
    fun submitBuffer(
        tx: SurfaceControl.Transaction,
        snapshot: SurfaceControl,
        captured: Captured,
        alpha: Float
    ): Boolean = runCatching {
        Reflect.callMethod(tx, "setBuffer", snapshot, captured.buffer)
        captured.colorSpace?.let { cs ->
            Reflect.callMethod(tx, "setColorSpace", snapshot, cs)
        }
        tx.setAlpha(snapshot, alpha)
        Reflect.callMethod(tx, "show", snapshot)
        true
    }.getOrDefault(false)

    /** 移除并释放快照表面（幂等；buffer 由调用方 close）。 */
    fun destroySnapshotSurface(snapshot: SurfaceControl?) {
        if (snapshot == null) return
        try {
            runCatching {
                SurfaceControl.Transaction().use { tx ->
                    Reflect.callMethod(tx, "remove", snapshot)
                    tx.apply()
                }
            }.onFailure { AppLog.error(TAG, "snapshot surface removal failed", it) }
        } finally {
            snapshot.release()
        }
    }
}
