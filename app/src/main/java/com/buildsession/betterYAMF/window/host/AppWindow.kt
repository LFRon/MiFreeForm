package com.buildsession.betterYAMF.window.host

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ITaskStackListenerProxy
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.DISPLAY_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.flingAnimationOf
import androidx.wear.widget.RoundedDrawable
import com.google.android.material.color.MaterialColors
import com.buildsession.betterYAMF.common.getAttr
import com.buildsession.betterYAMF.common.onException
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.common.runMain
import com.buildsession.betterYAMF.common.ui.animateAlpha
import com.buildsession.betterYAMF.common.ui.dpToPx
import com.buildsession.betterYAMF.databinding.WindowAppBinding
import com.buildsession.betterYAMF.window.core.CloseOrigin
import com.buildsession.betterYAMF.window.core.FullscreenOrigin
import com.buildsession.betterYAMF.window.core.RegionId
import com.buildsession.betterYAMF.window.core.WindowCommand
import com.buildsession.betterYAMF.window.core.WindowInputEvent
import com.buildsession.betterYAMF.window.core.WindowRegistration
import com.buildsession.betterYAMF.window.core.WindowShellController
import com.buildsession.betterYAMF.window.core.WindowShellRegistry
import com.buildsession.betterYAMF.window.session.WindowSessions
import com.buildsession.betterYAMF.common.model.resolveWindowSizePx
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.services.YAMFManager.config
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.RunMainThreadQueue
import com.buildsession.betterYAMF.xposed.utils.TipUtil
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.getActivityInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sign


@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class AppWindow(
    val context: Context,
    private val onWindowInit: (WindowHost, Int) -> Unit
) : WindowHost {
    companion object {
        const val TAG = "reYAMF_AppWindow"
        private const val HOME_REFRONT_DELAY_MS = 0L
        private const val MINI_START_DELAY_MS = 500L
        private val shellRegistry = WindowShellRegistry()
        private val windowIdGen = java.util.concurrent.atomic.AtomicInteger(1)
    }

    override val windowId = windowIdGen.getAndIncrement()
    lateinit var binding: WindowAppBinding
    private lateinit var shell: WindowShellController

    override var currentTaskId = -1
    private var isDestroyed = false
    private var openAsMiniPending = false
    private var miniRequested = false

    private var originalWidth: Int = 0
    private var originalHeight: Int = 0
    private var isResize: Boolean = true
    private var orientation = 0
    private var params = WindowManager.LayoutParams()
    private var keepInScreenAnimator: ValueAnimator? = null

    // ==== M7：几何唯一事实源——内容矩形动画（chrome 与 leash 逐帧同源） ====
    /** 当前正在运行的内容矩形动画器（同一时刻只允许一个几何动画）。 */
    private var boundsAnimator: ValueAnimator? = null
    private var animDeltaW = 0
    private var animDeltaH = 0
    /** 打开后等待内容就绪的淡入（消除"孤钮/空白卡"帧）。 */
    private var pendingReveal = false
    @Volatile
    private var chromeRevealed = false
    private val revealRunnable = Runnable { revealChrome() }
    /** M7：进 mini 前的内容矩形（位置记忆）；退出 mini 回到此位置（见视频规范 §2.2） */
    private var preMiniRect: android.graphics.Rect? = null
    private var flingXAnimation: FlingAnimation? = null
    private var flingYAnimation: FlingAnimation? = null
    private var dragAnchorX = 0
    private var dragAnchorY = 0
    private var gestureRegion: RegionId? = null
    private var lastGestureRawX = 0f
    private var lastGestureRawY = 0f
    private var last2GestureRawX = 0f
    private var last2GestureRawY = 0f
    private var flingRefX = 0f
    private var flingRefY = 0f
    private var resizeBeginWidth = 0
    private var resizeBeginHeight = 0
    private var cornerDropZoneView: CornerDropZoneView? = null
    private var frameOffsetX = 0
    private var frameOffsetY = 0
    private var frameOffsetKnown = false
    private var contentTouchThrough = true
    private val frameOffsetLayoutListener =
        android.view.ViewTreeObserver.OnGlobalLayoutListener { refreshFrameOffset() }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Protocol.ACTION_RESET_ALL_WINDOW) {
                val lp = binding.root.layoutParams as WindowManager.LayoutParams
                val dm = context.resources.displayMetrics
                val width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200F, context.resources.displayMetrics).toInt()
                val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 300F, context.resources.displayMetrics).toInt()
                
                lp.apply {
                    x = (dm.widthPixels - width) / 2
                    y = (dm.heightPixels - height) / 2
                }
                Instances.windowManager.updateViewLayout(binding.root, lp)
                
                binding.vSizePreviewer.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
            }
        }
    }

    init {
        runCatching {
            binding = WindowAppBinding.inflate(LayoutInflater.from(context))
        }.onException { e ->
            Log.e(TAG, "Failed to create new window, did you reboot?", e)
            TipUtil.showToast("Failed to create new window, did you reboot?")
        }.onSuccess {
            doInit()
        }
    }

    private fun doInit() {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        )
        params.setTitle(Protocol.CHROME_WINDOW_TITLE)

        val displayManager = context.getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0
        
        val dm = context.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val (windowWidth, windowHeight) = config.resolveWindowSizePx(screenWidth, screenHeight)

        params.apply {
            gravity = Gravity.TOP or Gravity.START
            
            when (rotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 -> {
                    orientation = 0
                    binding.rlBarControllerSide.isVisible = false
                    // Center horizontally, use config.portraitY if set, else center vertically with offset
                    x = (screenWidth - windowWidth) / 2
                    y = if (config.portraitY != 0) config.portraitY else (screenHeight - windowHeight) / 2 - 80.dpToPx().toInt()
                }
                Surface.ROTATION_90, Surface.ROTATION_270 -> {
                    orientation = 1
                    binding.rlBarControllerBottom.isVisible = false
                    // Center horizontally, use config.landscapeY if set, else center vertically
                    x = (screenWidth - windowWidth) / 2
                    y = if (config.landscapeY != 0) config.landscapeY else (screenHeight - windowHeight) / 2
                }
                else -> {
                    x = 0
                    y = 0
                }
            }
        }

        // M7：打开即隐藏，等内容就绪（onOrganizerLeash/超时）再淡入——避免空卡闪帧
        pendingReveal = true
        binding.root.alpha = 0f

        binding.root.let { layout ->
            Instances.windowManager.addView(layout, params)
        }

        // 布局完成后缓存"窗口帧 - params"偏移（如状态栏 130px），
        // 之后 updateSmoothBounds 用 params + 缓存偏移确定性推导，不依赖会滞后/失效的视图坐标 API。
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(frameOffsetLayoutListener)

        binding.rootClickMask.setOnTouchListener { _, event ->
            translateMoveGesture(RegionId.BODY_MASK, event)
            true
        }

        binding.cvBarClickMask.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(windowId, System.currentTimeMillis(), RegionId.BAR_BOTTOM))
        }

        binding.cvBarSideClickMask.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(windowId, System.currentTimeMillis(), RegionId.BAR_SIDE))
        }

        binding.ibSuper.setOnTouchListener { _, event ->
            translateMoveGesture(RegionId.HANDLE, event)
            false
        }

        binding.cvBarClickMask.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                translateBarGesture(RegionId.BAR_BOTTOM, event)
                return when (event.action) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        })

        binding.cvBarSideClickMask.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                translateBarGesture(RegionId.BAR_SIDE, event)
                return when (event.action) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        })

        rightResize(binding.ibRightResize)

        shell = shellRegistry.register(
            WindowRegistration(windowId, Resources.getSystem().displayMetrics.density)
        )
        updateShellScreenMetrics()
        
        context.registerReceiver(broadcastReceiver, IntentFilter(Protocol.ACTION_RESET_ALL_WINDOW), Context.RECEIVER_EXPORTED)
        binding.vSizePreviewer.updateLayoutParams {
            this.width = windowWidth
            this.height = windowHeight
        }
        onWindowInit(this, windowId)

        isResize = false
        binding.cvBackground.post {
            originalWidth = binding.cvBackground.width
            originalHeight = binding.cvBackground.height
            binding.cvBackground.visibility = View.VISIBLE

            binding.cvBackground.radius = config.windowRoundedCorner.dpToPx()


            binding.cvParent.radius = (config.windowRoundedCorner+2).dpToPx()
            // 圆角裁剪在系统侧完成：会话期 SmoothTaskOrganizer 关闭"客户端自绘圆角"优化
            // （toggleClientDrawnRoundedCornersOpt(false)），SF 直接对 leash 做圆角裁剪，
            // 应用侧零圆角状态 → 无镜像、无残留；宿主侧不再需要遮罩视图。
            originalWidth = binding.cvParent.width
            originalHeight = binding.cvParent.height
            binding.cvParent.visibility = View.VISIBLE

            // M7：内容就绪前整卡不可见（消除打开时的"孤钮/空白卡"帧）。
            // 就绪信号 = onOrganizerLeash（任务 leash 到手并摆放）；另有超时兜底。
            // 若 reveal 已先行（内容先到、布局后到），这里不再回退隐藏。
            if (!chromeRevealed) {
                pendingReveal = true
                binding.root.alpha = 0f
                runMain { binding.root.postDelayed(revealRunnable, 700) }
            }
        }

    }

    override fun onDestroy(releaseTask: Boolean) {
        if (isDestroyed) return
        isDestroyed = true

        // M7：几何动画/淡入兜底清理
        boundsAnimator?.cancel()
        boundsAnimator = null
        pendingReveal = false
        runMain { runCatching { binding.root.removeCallbacks(revealRunnable) } }

        // 关闭小窗：任务复位为全屏并下沉到原父级栈底（后台、留在最近任务，不抢前台，
        // 表面重建黑帧被上层遮挡）——"关闭"≠"最大化"。下拉全屏路径此前已显式置顶释放，
        // 此处二次调用为 no-op；任务移交新壳（releaseTask=false）时静默销毁。
        if (releaseTask) {
            runCatching {
                if (currentTaskId > 0) YAMFManager.switchTaskToFullscreen(currentTaskId, toFront = false)
            }.onFailure {
                log(TAG, "release task to background failed", it)
            }
        }
        
        runCatching { context.unregisterReceiver(broadcastReceiver) }
        
        // 流程收口：窗口登记 / 待认领表 / 会话表 / 几何缓存 / 打开计数 统一由流程撤销
        YAMFManager.unregisterHost(this)
        if (::shell.isInitialized) {
            shellRegistry.unregister(shell)
        }
        
        runMain {
            // 移除前先隐身：任何延迟都不留残影（白点回归的兜底）
            runCatching { binding.root.visibility = View.INVISIBLE }
            if (binding.root.isAttachedToWindow) {
                runCatching { Instances.windowManager.removeView(binding.root) }
            }
            cornerDropZoneView?.let {
                if (it.isAttachedToWindow) {
                    runCatching { Instances.windowManager.removeView(it) }
                }
                cornerDropZoneView = null
            }
        }
    }

    private fun moveToTop() {
        if (binding.root.isAttachedToWindow) {
            Instances.windowManager.removeView(binding.root)
        }
        Instances.windowManager.addView(binding.root, binding.root.layoutParams)
        // 重挂会更换 ViewRoot，布局监听需要重新注册
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(frameOffsetLayoutListener)
    }

    private fun dispatchInput(event: WindowInputEvent) {
        shell.onInput(event).forEach(::execute)
    }

    private fun execute(command: WindowCommand) {
        if (command !is WindowCommand.MoveBy && command !is WindowCommand.FlingBy &&
            command !is WindowCommand.DropHover && command !is WindowCommand.DropZoneShow) {
            log(TAG, "exec ${command::class.simpleName} window=$windowId")
        }
        when (command) {
            is WindowCommand.MoveBy -> execMoveBy(command)
            is WindowCommand.FlingBy -> execFlingBy(command)
            is WindowCommand.ClampToScreen -> execClampToScreen(command)
            is WindowCommand.DropZoneShow -> showCornerDropZone()
            is WindowCommand.DropHover -> execDropHover(command)
            is WindowCommand.DropZoneHide -> hideCornerDropZone()
            is WindowCommand.VerticalDragPreview -> execVerticalDragPreview(command)
            is WindowCommand.ClearVerticalDragPreview -> execResetWindowScale()
            is WindowCommand.ResizePreviewStart -> execResizePreviewStart()
            is WindowCommand.ResizePreviewUpdate -> execResizePreviewUpdate(command)
            is WindowCommand.ResizePreviewCommit -> execResizePreviewCommit()
            is WindowCommand.EnterMini -> execEnterMini(command.corner)
            is WindowCommand.ExitMini -> execExitMini()
            is WindowCommand.RequestClose -> execRequestClose(command.origin)
            is WindowCommand.RequestFullscreen -> execRequestFullscreen(command.origin)
            is WindowCommand.RaiseWindow -> moveToTop()
        }
    }

    private fun updateShellScreenMetrics() {
        val dm = DisplayMetrics()
        context.display?.getRealMetrics(dm)
        shell.onScreenMetricsChanged(dm.widthPixels, dm.heightPixels)
    }

    private fun translateMoveGesture(region: RegionId, event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_DOWN) {
            // 触下即接管几何：取消惯性/夹取/内容矩形动画（修复"框自己跳走"）
            cancelGeometryAnimators()
            gestureRegion = region
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            dragAnchorX = params.x
            dragAnchorY = params.y
        }
        moveGestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN ->
                dispatchInput(WindowInputEvent.Press(windowId, event.eventTime, region, event.rawX, event.rawY))
            MotionEvent.ACTION_UP ->
                dispatchInput(WindowInputEvent.Up(windowId, event.eventTime, region, event.rawX, event.rawY))
            MotionEvent.ACTION_CANCEL ->
                dispatchInput(WindowInputEvent.Cancel(windowId, event.eventTime, region, event.rawX, event.rawY))
        }
        updateSmoothBounds()
    }

    private fun translateBarGesture(region: RegionId, event: MotionEvent) {
        updateSmoothBounds()
        when (event.action) {
            MotionEvent.ACTION_DOWN ->
                dispatchInput(WindowInputEvent.Press(windowId, event.eventTime, region, event.rawX, event.rawY))
            MotionEvent.ACTION_MOVE ->
                dispatchInput(WindowInputEvent.Move(windowId, event.eventTime, region, event.rawX, event.rawY))
            MotionEvent.ACTION_UP ->
                dispatchInput(WindowInputEvent.Up(windowId, event.eventTime, region, event.rawX, event.rawY))
            MotionEvent.ACTION_CANCEL ->
                dispatchInput(WindowInputEvent.Cancel(windowId, event.eventTime, region, event.rawX, event.rawY))
        }
    }

    private fun execMoveBy(command: WindowCommand.MoveBy) {
        val params = binding.root.layoutParams as WindowManager.LayoutParams
        params.x = (dragAnchorX + command.dx).toInt()
        params.y = (dragAnchorY + command.dy).toInt()
        Instances.windowManager.updateViewLayout(binding.root, params)
        updateSmoothBounds()
    }

    private fun execFlingBy(command: WindowCommand.FlingBy) {
        // 落点收缩动画运行期间丢弃手势惯性：几何由落点动画独占（修复"框自己跳走"）
        if (boundsAnimator?.isRunning == true) return
        cancelGeometryAnimators(includeBounds = false)
        val params = binding.root.layoutParams as WindowManager.LayoutParams
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val windowWidth = binding.root.width
        val windowHeight = binding.root.height

        val minX = 0f
        val maxX = (screenWidth - windowWidth - frameOffsetX).toFloat()
        val minY = 0f
        val maxY = (screenHeight - windowHeight - frameOffsetY).toFloat()

        runCatching {
            if (sign(command.velocityX) != sign(flingRefX - last2GestureRawX)) return@runCatching
            flingXAnimation = flingAnimationOf({
                params.x = it.toInt()
                runCatching {
                    Instances.windowManager.updateViewLayout(binding.root, params)
                    updateSmoothBounds()
                }
            }, {
                params.x.toFloat()
            })
                .setStartVelocity(command.velocityX)
                .apply {
                    if (params.x >= minX && params.x <= maxX) {
                        setMinValue(minX)
                        setMaxValue(maxX)
                    }
                }
            flingXAnimation?.addEndListener { _, canceled, _, _ ->
                if (!canceled) keepInScreen()
            }
            flingXAnimation?.start()
        }
        runCatching {
            if (sign(command.velocityY) != sign(flingRefY - last2GestureRawY)) return@runCatching
            flingYAnimation = flingAnimationOf({
                params.y = it.toInt()
                runCatching {
                    Instances.windowManager.updateViewLayout(binding.root, params)
                    updateSmoothBounds()
                }
            }, {
                params.y.toFloat()
            })
                .setStartVelocity(command.velocityY)
                .apply {
                    if (params.y >= minY && params.y <= maxY) {
                        setMinValue(minY)
                        setMaxValue(maxY)
                    }
                }
            flingYAnimation?.addEndListener { _, canceled, _, _ ->
                if (!canceled) keepInScreen()
            }
            flingYAnimation?.start()
        }
    }

    private fun execClampToScreen(command: WindowCommand.ClampToScreen) {
        keepInScreen(command.animated)
    }

    private fun execDropHover(command: WindowCommand.DropHover) {
        cornerDropZoneView?.render(command.hover, command.active)
    }

    private fun execVerticalDragPreview(command: WindowCommand.VerticalDragPreview) {
        val progress = command.progress
        if (!command.downward) {
            val scale = 1f - (progress * 0.3f)
            val alpha = 1f - progress
            binding.cvParent.scaleX = scale
            binding.cvParent.scaleY = scale
            binding.cvParent.alpha = alpha

            binding.viewFullScreenMask.visibility = View.GONE
            binding.tvFullScreenPrompt.visibility = View.GONE
        } else {
            val scale = 1f + (progress * 0.2f)
            binding.cvParent.scaleX = scale
            binding.cvParent.scaleY = scale
            binding.cvParent.alpha = 1f

            if (command.showFullscreenHint) {
                binding.viewFullScreenMask.visibility = View.VISIBLE
                binding.tvFullScreenPrompt.visibility = View.VISIBLE
            } else {
                binding.viewFullScreenMask.visibility = View.GONE
                binding.tvFullScreenPrompt.visibility = View.GONE
            }
        }
    }

    private fun execResetWindowScale() {
        binding.cvParent.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(200)
            .start()
        binding.viewFullScreenMask.visibility = View.GONE
        binding.tvFullScreenPrompt.visibility = View.GONE
    }

    private fun execRequestClose(origin: CloseOrigin) {
        // 关闭前刷新最近任务快照（应用此刻仍完整可见）：
        // 否则系统在任务后台化时刻抓到的快照是收缩/隐藏后的空帧 → 最近任务卡片白屏。
        if (currentTaskId > 0) {
            runCatching { YAMFManager.windowBackend.requestSnapshotRefresh(currentTaskId) }
        }
        when (origin) {
            CloseOrigin.DRAG_UP_BAR -> {
                animateWindowShrinkAndClose()
            }
            CloseOrigin.DOUBLE_TAP_BAR -> {
                isResize = false

                if (orientation == 0) {
                    animateAlpha(binding.rlBarControllerBottom, 1f, 0f) {
                        binding.rlBarControllerBottom.visibility = View.GONE
                    }
                } else {
                    animateAlpha(binding.rlBarControllerSide, 1f, 0f) {
                        binding.rlBarControllerSide.visibility = View.GONE
                    }
                }

                CoroutineScope(Dispatchers.IO).launch {
                    delay(200)

                    withContext(Dispatchers.Main) {
                        animateWindowShrinkAndClose()
                    }
                }
            }
        }
    }

    /**
     * M7：关闭 = "框 + 内容"按同一矩形收缩到中心后再释放任务。
     * 消除旧实现"边框缩走、内容僵持/抢先回全屏"的错位；收尾的 releaseTask 由 onDestroy 执行。
     */
    private fun animateWindowShrinkAndClose() {
        val from = currentContentRect()
        if (from == null) {
            onDestroy(releaseTask = true)
            return
        }
        // 关停收尾：控制条/缩放手柄立即撤出——它们不属于收缩动画，
        // 且会被 wrap 根挤压变形留下"白色胶囊"残影（真机实证）。
        binding.rlBarControllerBottom.visibility = View.GONE
        binding.rlBarControllerSide.visibility = View.GONE
        binding.ibRightResize.visibility = View.GONE
        // 上滑预览（VerticalDragPreview）遗留的缩放/透明度复位：
        // 关闭分支不会收到 ClearVerticalDragPreview，不复位则收缩动画自带 0.7×/半透明
        binding.cvParent.scaleX = 1f
        binding.cvParent.scaleY = 1f
        binding.cvParent.alpha = 1f
        binding.viewFullScreenMask.visibility = View.GONE
        binding.tvFullScreenPrompt.visibility = View.GONE
        val cx = (from.left + from.right) / 2
        val cy = (from.top + from.bottom) / 2
        val to = android.graphics.Rect(cx - 2, cy - 2, cx + 2, cy + 2)
        animateContentRect(from, to, geometryAnimDuration(220L)) {
            // 动画结束先整壳隐身，再做释放/移除：即使移除被延迟也不留"白点"
            binding.root.visibility = View.INVISIBLE
            onDestroy(releaseTask = true)
        }
    }

    private fun execRequestFullscreen(origin: FullscreenOrigin) {
        if (currentTaskId == -1) return
        when (origin) {
            FullscreenOrigin.DRAG_DOWN_BAR -> {
                runCatching {
                    YAMFManager.switchTaskToFullscreen(currentTaskId)
                    YAMFManager.windowBackend.bringTaskToFront(currentTaskId)
                }.onSuccess {
                    onDestroy(releaseTask = true)
                }.onFailure {
                    execResetWindowScale()
                }
            }
        }
    }

    private fun refreshFrameOffset() {
        runCatching {
            val loc = IntArray(2)
            binding.root.getLocationOnScreen(loc)
            val p = binding.root.layoutParams as? WindowManager.LayoutParams ?: return@runCatching
            val offX = loc[0] - p.x
            val offY = loc[1] - p.y
            if (!frameOffsetKnown || offX != frameOffsetX || offY != frameOffsetY) {
                frameOffsetX = offX
                frameOffsetY = offY
                frameOffsetKnown = true
                log(TAG, "frameOffset=($offX,$offY) loc=(${loc[0]},${loc[1]}) params=(${p.x},${p.y})")
                updateSmoothBounds()
            }
        }
    }

    private fun updateSmoothBounds() {
        if (currentTaskId == -1) return

        // 矩形从窗口 params 确定性推导（+ 首次布局后缓存的帧偏移；偏移未知时按 0 计，
        // 布局完成后会再纠正一次）。视图的 getLocationOnScreen 在 relayout 完成前会返回旧值，
        // 不再作为主路径。
        val params = binding.root.layoutParams as? WindowManager.LayoutParams
        val left: Int
        val top: Int
        if (params != null) {
            left = params.x + (if (frameOffsetKnown) frameOffsetX else 0) + binding.cvParent.left
            top = params.y + (if (frameOffsetKnown) frameOffsetY else 0) + binding.cvParent.top
        } else {
            val location = IntArray(2)
            binding.root.getLocationOnScreen(location)
            left = location[0] + binding.cvParent.left
            top = location[1] + binding.cvParent.top
        }
        if (binding.cvParent.width <= 0 || binding.cvParent.height <= 0) {
            // 首帧布局未完成：矩形无效，等 OnGlobalLayout 后的下一次同步
            return
        }
        val rect = android.graphics.Rect(
            left,
            top,
            left + binding.cvParent.width,
            top + binding.cvParent.height
        )
        // 一致性守卫：卡片矩形不可能大于外壳窗口内容（root 视图）本身。
        // 实测曾出现可疑的全屏+帧偏移矩形进入几何链路；这里拒绝并留证。
        if (binding.root.width > 0 && binding.root.height > 0 &&
            (rect.width() > binding.root.width || rect.height() > binding.root.height)
        ) {
            log(
                TAG,
                "SUSPICIOUS geometry window=$windowId task=$currentTaskId rect=$rect " +
                    "root=${binding.root.width}x${binding.root.height} " +
                    "params=(${params?.x},${params?.y}) offset=($frameOffsetX,$frameOffsetY) " +
                    "cv=(${binding.cvParent.left},${binding.cvParent.top},${binding.cvParent.width}x${binding.cvParent.height})"
            )
            return
        }

        applySmoothBounds(rect)
    }

    /**
     * 显式矩形入口（M7 几何动画逐帧调用）：跳过 params/布局推导，其余语义与推导路径一致。
     * 不做 "rect 不得大于 root" 守卫——那是推导路径的异常检测；动画中的目标矩形由我们
     * 自己计算，root 的布局尺寸可能滞后一帧，守卫会误伤动画帧。
     */
    private fun updateSmoothBounds(rect: android.graphics.Rect) {
        if (currentTaskId == -1) return
        if (rect.width() <= 0 || rect.height() <= 0) return
        applySmoothBounds(rect)
    }

    /** 几何落地共享尾部（两条入口必须一致）：触摸洞 + 圆角 + 后端应用。 */
    private fun applySmoothBounds(rect: android.graphics.Rect) {
        if (contentTouchThrough) {
            val handleHeight = binding.ibSuper.height.takeIf { it > 0 } ?: 25.dpToPx().toInt()
            WindowSessions.chromeTouchHoles[windowId] = android.graphics.Rect(
                rect.left,
                rect.top + handleHeight,
                rect.right,
                rect.bottom
            )
        } else {
            WindowSessions.chromeTouchHoles.remove(windowId)
        }

        if (YAMFManager.windowBackend.currentGeometry(currentTaskId) != rect) {
            // 系统窗口后端：几何落地（leash 变换或兼容路径由实现决定，宿主不感知）
            YAMFManager.windowBackend.applyGeometry(currentTaskId, rect)
        }
    }

    /** 系统窗口后端把任务 leash 交给我们（onTaskAdopted 回灌）后，按当前几何立即摆放 */
    override fun onOrganizerLeash() {
        runMain {
            runCatching {
                // leash 刚到手：按当前几何立即摆一次位（首帧布局未完成时 updateSmoothBounds 会
                // 自行跳过，布局完成后由 OnGlobalLayout 再同步；不再等待 frameOffset 已知）。
                updateSmoothBounds()
                val taskId = currentTaskId
                val backend = YAMFManager.windowBackend
                if (taskId > 0 && backend.isTaskManaged(taskId)) {
                    if (backend.currentGeometry(taskId) != null) {
                        backend.reapplyGeometry(taskId)
                    } else {
                        // 几何未知（首帧布局尚未完成）：先保证任务表面可见，避免后端迁移后
                        // 任务表面处于隐藏等待状态
                        backend.ensureTaskVisible(taskId)
                    }
                }
            }.onFailure { log(TAG, "onOrganizerLeash failed", it) }
            // M7：内容已就绪 → 打开淡入（与首帧摆放同帧完成，杜绝空卡帧）
            revealChrome()
        }
    }

    private fun updateTask(taskInfo: ActivityManager.RunningTaskInfo) {
        currentTaskId = taskInfo.taskId
        RunMainThreadQueue.add {
            if (taskInfo.isVisible.not()) {
                delay(500) // fixme: use a method that directly determines visibility
            }

            var backgroundColor = 0
            var navigationBarColor = 0
            var taskDescription: ActivityManager.TaskDescription?

            if (Build.VERSION.SDK_INT < 35) {
                taskDescription = Instances.activityTaskManager.getTaskDescription(taskInfo.taskId) ?: return@add

                backgroundColor = taskDescription.backgroundColor
                navigationBarColor = taskDescription.backgroundColor
            } else {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningTasks = activityManager.getRunningTasks(5)

                for (task in runningTasks) {
                    if (task.taskId == taskInfo.taskId) {
                        try {
                            backgroundColor = task.taskDescription!!.backgroundColor
                            navigationBarColor = task.taskDescription!!.backgroundColor
                        } catch (e: PackageManager.NameNotFoundException) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            if (config.coloredController) {
                binding.background.setBackgroundColor(navigationBarColor)

                val onNavigationBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(navigationBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
                } else {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
                }

                binding.ibRightResize.imageTintList = ColorStateList.valueOf(onNavigationBar)
            }
        }
    }

    override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
        val isHome = YAMFManager.windowBackend.isHomeTask(taskInfo)
        log(TAG, "onTaskMovedToFront task=${taskInfo.taskId} isHome=$isHome current=$currentTaskId")
        if (isHome) {
            keepAboveHome()
            return
        }

        if (taskInfo.taskId == currentTaskId) {
            updateTask(taskInfo)
            maybeRequestMini()
            return
        }

        // 其他应用到前台：本窗作为浮窗保持在最前（全局覆盖语义）。
        // 另一个小窗（受管任务）到前台则允许压在上一层，不抢。
        if (!WindowSessions.hostedTasks.contains(taskInfo.taskId) && !isDestroyed) {
            keepAboveHome()
        }
    }

    override fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        if (!taskInfo.isVisible) {
            return
        }

        if (YAMFManager.windowBackend.isHomeTask(taskInfo)) {
            keepAboveHome()
            return
        }

        if (taskInfo.taskId == currentTaskId) {
            updateTask(taskInfo)
        }
    }

    override fun markOpenAsMini() {
        openAsMiniPending = true
    }

    private fun maybeRequestMini() {
        if (!openAsMiniPending || miniRequested || isDestroyed || currentTaskId <= 0) return
        openAsMiniPending = false
        miniRequested = true
        log(TAG, "requestMiniAtCorner task=$currentTaskId")
        runMain {
            binding.root.postDelayed({
                requestMiniAtCorner()
            }, MINI_START_DELAY_MS)
        }
    }

    private fun requestMiniAtCorner() {
        if (isDestroyed || currentTaskId <= 0) return
        runCatching {
            // 核心事件（而非合成手势）：NORMAL→MINI 迁移与 EnterMini(CORNER_TR) 由核心状态机决策
            dispatchInput(WindowInputEvent.RequestMini(windowId, System.currentTimeMillis()))
        }.onFailure {
            log(TAG, "requestMiniAtCorner failed", it)
        }
    }

    private var pendingHide: Runnable? = null
    private var reFrontScheduled = false

    override fun onTaskVisibilityChanged(visible: Boolean) {
        if (isDestroyed) return
        runMain {
            runCatching {
                pendingHide?.let { binding.root.removeCallbacks(it) }
                pendingHide = null
                if (visible) {
                    if (binding.root.visibility != View.VISIBLE) {
                        log(TAG, "onTaskVisibilityChanged visible=true set=VISIBLE")
                        binding.root.visibility = View.VISIBLE
                    }
                    // M7 热修：可见性恢复时同步修正透明度（淡入曾被 View 动画回落到 0 的兜底）
                    if (chromeRevealed && binding.root.alpha < 1f) {
                        binding.root.alpha = 1f
                    }
                } else {
                    // 250ms 宽限：浮窗回置顶/过渡期间不要闪隐
                    val hide = Runnable {
                        pendingHide = null
                        runCatching {
                            if (binding.root.visibility != View.INVISIBLE) {
                                log(TAG, "onTaskVisibilityChanged visible=false set=INVISIBLE(delayed)")
                                binding.root.visibility = View.INVISIBLE
                            }
                        }
                    }
                    pendingHide = hide
                    binding.root.postDelayed(hide, 250)
                }
            }.onFailure {
                log(TAG, "onTaskVisibilityChanged failed visible=$visible", it)
            }
        }
    }

    override fun onHomeTop() {
        keepAboveHome()
    }

    private fun keepAboveHome() {
        if (isDestroyed) return
        // 置顶身份（承载根 always-on-top）生效后，层序由系统排序规则保证：
        // 此时任何"回置顶"（bringTaskToFront/reorder）都是多余的抢层动作，正是与
        // recents 手势/过渡互相打断的来源 → 直接让路。
        // 仅保留"浮窗不持有焦点"的既定语义（否则可能抢走 home/下方应用的焦点）。
        if (YAMFManager.windowBackend.isAlwaysOnTopActive()) {
            log(TAG, "keepAboveHome skip (always-on-top) task=$currentTaskId")
            val managedTaskId = currentTaskId
            if (managedTaskId > 0) {
                runCatching { YAMFManager.windowBackend.setTaskFocusable(managedTaskId, false) }
            }
            return
        }
        val taskId = currentTaskId
        if (taskId <= 0) return
        if (reFrontScheduled) return
        reFrontScheduled = true
        val now = android.os.SystemClock.uptimeMillis()
        val suppressUntil = YAMFManager.windowBackend.recentsGestureSuppressUntilMs()
        val delay = if (suppressUntil > now) suppressUntil - now + 60L else HOME_REFRONT_DELAY_MS
        log(TAG, "keepAboveHome schedule task=$taskId delay=$delay")
        runMain {
            binding.root.postDelayed({ reFrontWhenTouchIdle(taskId, 0) }, delay)
        }
    }

    /**
     * 只在"没有触摸进行中"时回置顶：任何正在进行的滑动（QQ 滚动、上滑手势等）
     * 都先让路，手指抬起后才把浮窗拉回最上层，避免中途改窗口栈把手势抢到小窗。
     */
    private fun reFrontWhenTouchIdle(taskId: Int, attempts: Int) {
        if (isDestroyed) {
            reFrontScheduled = false
            return
        }
        if (YAMFManager.windowBackend.isTouchActive() && attempts < 40) {
            binding.root.postDelayed({ reFrontWhenTouchIdle(taskId, attempts + 1) }, 150)
            return
        }
        reFrontScheduled = false
        val result = YAMFManager.windowBackend.bringTaskToFront(taskId)
        log(TAG, "keepAboveHome re-front task=$taskId result=$result attempts=$attempts")
        // 浮窗保持在最上，但不持有焦点：返回/键盘等导航事件交给下方正在使用的应用
        runCatching { YAMFManager.windowBackend.setTaskFocusable(taskId, false) }
        onTaskVisibilityChanged(true)
        // HOME/应用切换的入场过渡可能在本次重排之后才落定（实测：根被压到 home 之下、
        // 子任务 moveTaskToFront 抬不起根）→ 稍后重试，保证浮窗最终在系统壳之上（有界，最多 3 次）。
        if (attempts < 2) {
            reFrontScheduled = true
            binding.root.postDelayed({ reFrontWhenTouchIdle(taskId, attempts + 1) }, 400)
        }
    }

    /**
     * 几何动画所有权（修复"框自己跳走"）：同一时刻只允许一个几何写者。
     * 惯性动画（onFling）先于 Up 触发、其结束回调又会以"收缩中的过时 root 宽度"
     * 夹取——与落点收缩动画并发时逐帧互抢 params/leash。任何一方开始前先取消其余写者。
     */
    private fun cancelGeometryAnimators(includeBounds: Boolean = true) {
        flingXAnimation?.cancel()
        flingXAnimation = null
        flingYAnimation?.cancel()
        flingYAnimation = null
        keepInScreenAnimator?.cancel()
        keepInScreenAnimator = null
        if (includeBounds) {
            boundsAnimator?.cancel()
            boundsAnimator = null
        }
    }

    private fun keepInScreen(animate: Boolean = true) {
        binding.root.post {
            // 几何动画（收缩/惯性）运行期间不改写几何：动画自行收敛，惯性结束回调会再触发夹取。
            if (boundsAnimator?.isRunning == true ||
                flingXAnimation?.isRunning == true ||
                flingYAnimation?.isRunning == true
            ) {
                return@post
            }
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val windowWidth = binding.root.width
            val windowHeight = binding.root.height

            val minX = 0
            val minY = 0
            val maxX = screenWidth - windowWidth - frameOffsetX
            val maxY = screenHeight - windowHeight - frameOffsetY

            val targetX = params.x.coerceIn(minX, maxX)
            val targetY = params.y.coerceIn(minY, maxY)

            if (targetX == params.x && targetY == params.y) return@post

            keepInScreenAnimator?.cancel()

            if (animate) {
                val startX = params.x
                val startY = params.y
                keepInScreenAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 300
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { animation ->
                        val fraction = animation.animatedValue as Float
                        params.x = (startX + (targetX - startX) * fraction).toInt()
                        params.y = (startY + (targetY - startY) * fraction).toInt()
                        runCatching {
                            Instances.windowManager.updateViewLayout(binding.root, params)
                            updateSmoothBounds()
                        }
                    }
                    start()
                }
            } else {
                params.x = targetX
                params.y = targetY
                runCatching {
                    Instances.windowManager.updateViewLayout(binding.root, params)
                    updateSmoothBounds()
                }
            }
        }
    }

    // ==== M7：几何唯一事实源——内容矩形动画（逐帧同时驱动 chrome 与内容 leash） ====

    /** 当前内容矩形（与 updateSmoothBounds 推导一致；布局未就绪返回 null）。 */
    private fun currentContentRect(): android.graphics.Rect? {
        val params = binding.root.layoutParams as? WindowManager.LayoutParams ?: return null
        if (binding.cvParent.width <= 0 || binding.cvParent.height <= 0) return null
        val left = params.x + (if (frameOffsetKnown) frameOffsetX else 0) + binding.cvParent.left
        val top = params.y + (if (frameOffsetKnown) frameOffsetY else 0) + binding.cvParent.top
        return android.graphics.Rect(left, top, left + binding.cvParent.width, top + binding.cvParent.height)
    }

    /**
     * 屏内夹取（安全边距）：任何"自动定位"都必须保证窗口四边可见——
     * 贴屏会让圆角/边框/阴影被屏幕边界裁掉（"边缘消失"回归的根因）。
     */
    private fun clampContentRect(leftIn: Int, topIn: Int, w: Int, h: Int): android.graphics.Rect {
        val dm = context.resources.displayMetrics
        val margin = 8.dpToPx().toInt()
        val maxLeft = (dm.widthPixels - w - margin).coerceAtLeast(margin)
        val maxTop = (dm.heightPixels - h - margin).coerceAtLeast(margin)
        val left = leftIn.coerceIn(margin, maxLeft)
        val top = topIn.coerceIn(margin, maxTop)
        return android.graphics.Rect(left, top, left + w, top + h)
    }

    /** 逐帧应用内容矩形：窗口位置 + 卡片尺寸 + leash 一次到位（同一几何事实源）。 */
    private fun applyContentRectFrame(rect: android.graphics.Rect) {
        val params = binding.root.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = rect.left - (if (frameOffsetKnown) frameOffsetX else 0) - binding.cvParent.left
        params.y = rect.top - (if (frameOffsetKnown) frameOffsetY else 0) - binding.cvParent.top
        binding.cvBackground.updateLayoutParams {
            width = (rect.width() - animDeltaW).coerceAtLeast(1)
            height = (rect.height() - animDeltaH).coerceAtLeast(1)
        }
        runCatching { Instances.windowManager.updateViewLayout(binding.root, params) }
        updateSmoothBounds(rect)
    }

    /**
     * 内容矩形动画：from → to，逐帧驱动 [applyContentRectFrame]。
     * 时长为 0（用户关闭动画/配置为 0）时直接到终态（leash 同步一次到位）。
     */
    private fun animateContentRect(
        from: android.graphics.Rect,
        to: android.graphics.Rect,
        durationMs: Long,
        onEnd: (() -> Unit)? = null
    ) {
        // 内容矩形动画独占几何：取消惯性/夹取/上一次几何动画（修复"框自己跳走"）
        cancelGeometryAnimators()
        animDeltaW = (binding.cvParent.width - binding.cvBackground.width).coerceAtLeast(0)
        animDeltaH = (binding.cvParent.height - binding.cvBackground.height).coerceAtLeast(0)
        if (durationMs <= 0L || from == to) {
            applyContentRectFrame(to)
            onEnd?.invoke()
            return
        }
        boundsAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                applyContentRectFrame(
                    android.graphics.Rect(
                        (from.left + (to.left - from.left) * f).toInt(),
                        (from.top + (to.top - from.top) * f).toInt(),
                        (from.right + (to.right - from.right) * f).toInt(),
                        (from.bottom + (to.bottom - from.bottom) * f).toInt()
                    )
                )
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (boundsAnimator === animation) boundsAnimator = null
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    /** 几何动画时长：尊重开发者选项动画缩放；缩放为 0 时立即完成；跟随模块动画速度配置。 */
    private fun geometryAnimDuration(base: Long = 260L): Long {
        val scale = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
            )
        }.getOrDefault(1f)
        if (scale <= 0f) return 0L
        val configured = if (config.animationSpeed < 5100) config.animationSpeed.toLong() else base
        return (configured * scale).toLong()
    }

    /** 几何诊断：输出本窗口几何/可见性/透明度全景（adb: am broadcast -a com.buildsession.betterYAMF.action.DUMP_GEOMETRY） */
    override fun dumpGeometry() {
        runMain {
            runCatching {
                val p = binding.root.layoutParams as? WindowManager.LayoutParams
                val loc = IntArray(2)
                binding.root.getLocationOnScreen(loc)
                log(
                    TAG,
                    "GEOM window=$windowId task=$currentTaskId destroyed=$isDestroyed " +
                        "params=(${p?.x},${p?.y}) loc=(${loc[0]},${loc[1]}) " +
                        "root=${binding.root.width}x${binding.root.height} alpha=${binding.root.alpha} vis=${binding.root.visibility} " +
                        "revealed=$chromeRevealed pendingReveal=$pendingReveal " +
                        "cvParent=(l=${binding.cvParent.left},t=${binding.cvParent.top}," +
                        "${binding.cvParent.width}x${binding.cvParent.height},vis=${binding.cvParent.visibility}) " +
                        "cvBackground=(l=${binding.cvBackground.left},t=${binding.cvBackground.top}," +
                        "${binding.cvBackground.width}x${binding.cvBackground.height},vis=${binding.cvBackground.visibility}) " +
                        "ibSuper=(l=${binding.ibSuper.left},t=${binding.ibSuper.top}," +
                        "${binding.ibSuper.width}x${binding.ibSuper.height},vis=${binding.ibSuper.visibility}) " +
                        "offset=(${frameOffsetX},${frameOffsetY},known=$frameOffsetKnown)"
                )
            }.onFailure { log(TAG, "dumpGeometry failed", it) }
        }
    }

    /** 打开后的内容就绪淡入（消除"孤钮/空白卡"帧）。 */
    private fun revealChrome() {
        if (!pendingReveal || isDestroyed) return
        pendingReveal = false
        chromeRevealed = true
        runMain {
            runCatching {
                binding.root.removeCallbacks(revealRunnable)
                // M7 热修：属性驱动的淡入。View 动画（AlphaAnimation）只在动画期间生效，
                // 结束后会回落到 View.alpha 属性——打开时属性=0 则动画结束又变透明
                // （"小窗边框消失"回归的根因）。这里直接动画属性本身并把终态钉在 1f。
                val fromAlpha = binding.root.alpha.coerceIn(0f, 1f)
                if (fromAlpha < 1f) {
                    ValueAnimator.ofFloat(fromAlpha, 1f).apply {
                        duration = 250
                        addUpdateListener { anim -> binding.root.alpha = anim.animatedValue as Float }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                binding.root.alpha = 1f
                            }
                        })
                        start()
                    }
                } else {
                    binding.root.alpha = 1f
                }
                setBackgroundWrapContent()

                CoroutineScope(Dispatchers.Main).launch {
                    delay(200)
                    binding.cvParent.strokeWidth = 2.dpToPx().toInt()
                }

                if (binding.ibRightResize.visibility == View.VISIBLE) {
                    isResize = true
                }
            }.onFailure { log(TAG, "revealChrome failed", it) }
        }
    }

    private fun execEnterMini(corner: com.buildsession.betterYAMF.window.core.DropKind) {
        if (originalWidth <= 0 || originalHeight <= 0) {
            log(TAG, "execEnterMini skipped: size not measured")
            return
        }

        isResize = false

        binding.rootClickMask.visibility = View.VISIBLE
        binding.rlBarControllerBottom.visibility = View.GONE
        binding.rlBarControllerSide.visibility = View.GONE
        binding.ibRightResize.visibility = View.GONE

        // mini 状态下整卡可点（点任意位置放大回正常窗口），关闭内容透传洞
        contentTouchThrough = false
        WindowSessions.chromeTouchHoles.remove(windowId)

        // 侧边最小化比角落 mini 再小一档（1/3），角落为 1/2
        val isSide = corner == com.buildsession.betterYAMF.window.core.DropKind.SIDE_LEFT ||
                corner == com.buildsession.betterYAMF.window.core.DropKind.SIDE_RIGHT
        val miniDivisor = if (isSide) 3 else 2
        val w = originalWidth / miniDivisor
        val h = originalHeight / miniDivisor

        val dm = context.resources.displayMetrics
        val margin = 8.dpToPx().toInt()
        val topInset = 140
        val bottomInset = 120
        val from = currentContentRect()
        // 位置记忆：记住进 mini 前的位置（重复进入不覆盖；退出 mini 回此位置）
        if (preMiniRect == null) {
            preMiniRect = from
        }
        // M7：内容矩形动画（chrome 与 leash 同源）——收缩与贴角在一个动画里完成
        val to = when (corner) {
            com.buildsession.betterYAMF.window.core.DropKind.CORNER_TL ->
                android.graphics.Rect(margin, topInset + margin, margin + w, topInset + margin + h)
            com.buildsession.betterYAMF.window.core.DropKind.CORNER_BL ->
                android.graphics.Rect(margin, dm.heightPixels - bottomInset - margin - h,
                    margin + w, dm.heightPixels - bottomInset - margin)
            com.buildsession.betterYAMF.window.core.DropKind.CORNER_BR ->
                android.graphics.Rect(dm.widthPixels - margin - w, dm.heightPixels - bottomInset - margin - h,
                    dm.widthPixels - margin, dm.heightPixels - bottomInset - margin)
            com.buildsession.betterYAMF.window.core.DropKind.SIDE_LEFT -> {
                val y = (from?.top ?: 0).coerceIn(margin, (dm.heightPixels - h - margin).coerceAtLeast(margin))
                android.graphics.Rect(margin, y, margin + w, y + h)
            }
            com.buildsession.betterYAMF.window.core.DropKind.SIDE_RIGHT -> {
                val y = (from?.top ?: 0).coerceIn(margin, (dm.heightPixels - h - margin).coerceAtLeast(margin))
                android.graphics.Rect(dm.widthPixels - margin - w, y, dm.widthPixels - margin, y + h)
            }
            else -> android.graphics.Rect(dm.widthPixels - margin - w, topInset + margin,
                dm.widthPixels - margin, topInset + margin + h)
        }
        animateContentRect(from ?: to, to, geometryAnimDuration())
    }

    private fun execExitMini() {
        isResize = true
        contentTouchThrough = true
        binding.rootClickMask.visibility = View.GONE
        binding.ibRightResize.visibility = View.VISIBLE
        if (orientation == 0) {
            binding.rlBarControllerBottom.visibility = View.VISIBLE
        } else {
            binding.rlBarControllerSide.visibility = View.VISIBLE
        }

        val from = currentContentRect() ?: return
        // M7：位置记忆——回到进 mini 前的矩形；无记忆则围绕当前中心展开。
        // 两者都经安全边距夹取（贴屏会让圆角/边框/阴影出屏 = "边缘消失"缺陷）。
        val remembered = preMiniRect
        preMiniRect = null
        val to = if (remembered != null) {
            clampContentRect(remembered.left, remembered.top, originalWidth, originalHeight)
        } else {
            val cx = (from.left + from.right) / 2
            val cy = (from.top + from.bottom) / 2
            clampContentRect(cx - originalWidth / 2, cy - originalHeight / 2, originalWidth, originalHeight)
        }
        animateContentRect(from, to, geometryAnimDuration()) {
            // 终态恢复 wrap-content（保持既有布局约定，后续测量与吸附基于自然尺寸）
            setBackgroundWrapContent()
            setParrentWrapContent()
            binding.root.post(::updateSmoothBounds)
        }
    }

    private fun setBackgroundWrapContent() {
        val layoutParams = binding.cvBackground.layoutParams
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.cvBackground.layoutParams = layoutParams
    }

    private fun setParrentWrapContent() {
        val layoutParams = binding.cvParent.layoutParams
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.cvParent.layoutParams = layoutParams
    }

    private fun rightResize(ibResize: ImageButton) {
        ibResize.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when(event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // 触下即接管几何：取消惯性/夹取/内容矩形动画（同拖拽路径）
                        cancelGeometryAnimators()
                        dispatchInput(WindowInputEvent.Press(windowId, event.eventTime, RegionId.RESIZE_HANDLE, event.rawX, event.rawY))
                    }
                    MotionEvent.ACTION_MOVE -> {
                        dispatchInput(WindowInputEvent.Move(windowId, event.eventTime, RegionId.RESIZE_HANDLE, event.rawX, event.rawY))
                        // 等本帧布局应用后再同步任务矩形，缩放时内容与外壳连续对齐
                        binding.root.post { updateSmoothBounds() }
                    }
                    MotionEvent.ACTION_UP -> {
                        dispatchInput(WindowInputEvent.Up(windowId, event.eventTime, RegionId.RESIZE_HANDLE, event.rawX, event.rawY))
                        updateSmoothBounds()
                    }
                }
                return true
            }
        })
    }

    private fun execResizePreviewStart() {
        binding.vSizePreviewer.layoutParams.let {
            resizeBeginWidth = it.width
            resizeBeginHeight = it.height
        }
        binding.vSizePreviewer.visibility = View.VISIBLE
        binding.cvParent.strokeWidth = 0
    }

    private fun execResizePreviewUpdate(command: WindowCommand.ResizePreviewUpdate) {
        binding.vSizePreviewer.updateLayoutParams {
            val targetWidth = resizeBeginWidth + command.dx.toInt()
            if (targetWidth > 0)
                width = targetWidth
            val targetHeight = resizeBeginHeight + command.dy.toInt()
            if (targetHeight > 0)
                height = targetHeight
        }
    }

    private fun execResizePreviewCommit() {
        binding.vSizePreviewer.visibility = View.GONE
        binding.cvParent.strokeWidth = 2.dpToPx().toInt()
        keepInScreen()
    }

    private val moveGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            e1 ?: return false
            last2GestureRawX = lastGestureRawX
            last2GestureRawY = lastGestureRawY
            lastGestureRawX = e2.rawX
            lastGestureRawY = e2.rawY
            updateShellScreenMetrics()
            dispatchInput(
                WindowInputEvent.Move(
                    windowId,
                    e2.eventTime,
                    gestureRegion ?: RegionId.BODY_MASK,
                    e2.rawX,
                    e2.rawY
                )
            )
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            e1 ?: return false
            if (e1.source == InputDevice.SOURCE_MOUSE) return false
            flingRefX = e2.rawX
            flingRefY = e2.rawY
            dispatchInput(WindowInputEvent.Fling(windowId, e2.eventTime, velocityX, velocityY))
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            dispatchInput(
                WindowInputEvent.TapConfirmed(
                    windowId,
                    e.eventTime,
                    gestureRegion ?: RegionId.BODY_MASK,
                    e.rawX,
                    e.rawY
                )
            )
            return true
        }
    })

    private fun showCornerDropZone() {
        if (cornerDropZoneView == null) {
            cornerDropZoneView = CornerDropZoneView(context)
            val lp = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
            }
            Instances.windowManager.addView(cornerDropZoneView, lp)
        }
        cornerDropZoneView?.visibility = View.VISIBLE
    }

    private fun hideCornerDropZone() {
        cornerDropZoneView?.visibility = View.GONE
    }
}

