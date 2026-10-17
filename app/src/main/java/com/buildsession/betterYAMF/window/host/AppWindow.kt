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
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.graphics.Outline
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.flingAnimationOf
import androidx.wear.widget.RoundedDrawable
import com.google.android.material.color.MaterialColors
import com.buildsession.betterYAMF.R
import com.buildsession.betterYAMF.common.getAttr
import com.buildsession.betterYAMF.common.onException
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.common.runMain
import com.buildsession.betterYAMF.common.ui.animateAlpha
import com.buildsession.betterYAMF.common.ui.dpToPx
import com.buildsession.betterYAMF.databinding.WindowAppBinding
import com.buildsession.betterYAMF.window.core.AnimTimingPolicy
import com.buildsession.betterYAMF.window.core.ClampPolicy
import com.buildsession.betterYAMF.window.core.CloseOrigin
import com.buildsession.betterYAMF.window.core.CommandStatus
import com.buildsession.betterYAMF.window.core.DropEntryPolicy
import com.buildsession.betterYAMF.window.core.DropKind
import com.buildsession.betterYAMF.window.core.FullscreenOrigin
import com.buildsession.betterYAMF.window.core.MiniPolicy
import com.buildsession.betterYAMF.window.core.PositionMemory
import com.buildsession.betterYAMF.window.core.RegionId
import com.buildsession.betterYAMF.window.core.ResizePolicy
import com.buildsession.betterYAMF.window.core.WindowCommand
import com.buildsession.betterYAMF.window.core.WindowInputEvent
import com.buildsession.betterYAMF.window.core.WindowRegistration
import com.buildsession.betterYAMF.window.core.WindowShellController
import com.buildsession.betterYAMF.window.core.WindowShellRegistry
import com.buildsession.betterYAMF.window.session.WindowSessions
import com.buildsession.betterYAMF.common.model.resolveWindowSizePx
import com.buildsession.betterYAMF.xposed.hook.system.SystemBarMetrics
import com.buildsession.betterYAMF.xposed.hook.system.ViewSurfaceOps
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.services.YAMFManager.config
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.RunMainThreadQueue
import com.buildsession.betterYAMF.xposed.utils.TipUtil
import com.buildsession.betterYAMF.xposed.utils.getActivityInfoCompat
import com.buildsession.betterYAMF.xposed.utils.log
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
        /** mini 贴边边框厚度（dp）：对齐澎湃OS小窗装饰的银色边框环。 */
        private const val MINI_BEZEL_DP = 6f
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

    /** 回收区投放的松手点（屏坐标；仅 openAsMini 登场动画使用，一次性）。 */
    private var dropPoint: android.graphics.Point? = null

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
    private val revealRunnable = Runnable { revealChromeOrDropEntry() }
    /** M7：进 mini 前的内容矩形（位置记忆，经核心纯逻辑）；退出 mini 回到此位置（见视频规范 §2.2） */
    private val miniMemory = PositionMemory()
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

    /** 几何飞行期间的刷新率提升状态（进入/退出幂等）。 */
    private var flightFrameRateBoosted = false

    /** mini 化中（外壳装饰 / 内容区几何随模式切换，见 shellInsets 与 applyXCrome）。 */
    private var miniActive = false

    /** 顶栏（ibSuper 白条）透明度动画（进出 mini 时平滑显隐；见 [animateSuperBarAlpha]）。 */
    private var superBarAlphaAnimator: android.animation.ValueAnimator? = null

    /** 进出 mini 动画中外壳厚度的逐帧插值值（null=按当前模式取常量）。 */
    private var animInsets: MiniPolicy.ShellInsets? = null

    /** 手势进行中（移动/侧/底栏拖拽）：此间几何走 leash 缩放，结束再落真实 bounds。 */
    private var dragging = false

    /** 缩放手势进行中（拖拽缩放预览）：此间几何走 leash 缩放，提交时落真实 bounds。 */
    private var resizeGesture = false
    private val frameOffsetLayoutListener =
        android.view.ViewTreeObserver.OnGlobalLayoutListener { refreshFrameOffset() }

    /** 屏幕指标事件化：旋转/尺寸变化经 DisplayListener 入核（不再只靠手势兜底刷新）。 */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && ::shell.isInitialized) dispatchScreenMetrics()
        }
    }

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

        // 顶部拖动栏：整栏可拖（白底 + 居中三点；模式与窗口移动语义由核心决策，
        // 宿主只把整栏的触摸事件接入 HANDLE 手势输入）
        binding.ibSuper.setOnTouchListener { _, event ->
            translateMoveGesture(RegionId.HANDLE, event)
            true
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
        dispatchScreenMetrics()
        runCatching {
            (context.getSystemService(DISPLAY_SERVICE) as DisplayManager)
                .registerDisplayListener(displayListener, null)
        }
        
        context.registerReceiver(broadcastReceiver, IntentFilter(Protocol.ACTION_RESET_ALL_WINDOW), Context.RECEIVER_EXPORTED)
        binding.vSizePreviewer.updateLayoutParams {
            this.width = windowWidth
            this.height = windowHeight
        }
        onWindowInit(this, windowId)

        isResize = false
        binding.cvBackground.post {
            binding.cvBackground.visibility = View.VISIBLE
            binding.cvParent.visibility = View.VISIBLE
            // 应用本体永不自绘圆角（会话期 toggleClientDrawnRoundedCornersOpt(false) +
            // leash 侧不再写圆角）：可见圆角/边框一律由外壳装饰提供（点击态=方角；
            // mini=贴边边框盖住应用方角，与澎湃OS"装饰提供圆角"模型一致）。
            applyNormalChrome()
            applyHandleBarChrome()
            // 记录窗口卡的自然尺寸（退出 mini 的展开/夹取以卡片为基准；分除基准见 shellInsets）
            originalWidth = binding.cvParent.width
            originalHeight = binding.cvParent.height

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
        // 刷新率提升清理（幂等）
        runCatching { setFlightFrameRateBoost(false) }
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
        runCatching {
            (context.getSystemService(DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(displayListener)
        }
        
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
        // 销毁后不再分发：关闭收缩动画被同一手势 Cancel 时会先触发销毁，
        // 事件处理器后半程继续执行不得再驱动已摘除的窗口（updateViewLayout 会抛错）
        if (isDestroyed) return
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
            is WindowCommand.EnterMini -> execEnterMini(command.corner, command.cmdId)
            is WindowCommand.ExitMini -> execExitMini(command.cmdId)
            is WindowCommand.RequestClose -> execRequestClose(command.origin)
            is WindowCommand.RequestFullscreen -> execRequestFullscreen(command.origin)
            is WindowCommand.RaiseWindow -> moveToTop()
        }
    }

    /** 屏幕指标采集（事件化入核；DisplayListener 与手势兜底共同驱动）。 */
    private fun dispatchScreenMetrics() {
        val dm = DisplayMetrics()
        context.display?.getRealMetrics(dm)
        dispatchInput(
            WindowInputEvent.ScreenMetricsChanged(
                windowId, System.currentTimeMillis(), dm.widthPixels, dm.heightPixels
            )
        )
    }

    /** 命令结果回灌（闭环不变量 2）：Failed/Superseded 供核心收敛模式状态。 */
    private fun reportCommandResult(commandId: Long, status: CommandStatus) {
        if (commandId <= 0L) return
        dispatchInput(
            WindowInputEvent.CommandResult(windowId, System.currentTimeMillis(), commandId, status)
        )
    }

    private fun translateMoveGesture(region: RegionId, event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_DOWN) {
            // 触下即接管几何：取消惯性/夹取/内容矩形动画（修复"框自己跳走"）
            cancelGeometryAnimators()
            gestureRegion = region
            dragging = true
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            dragAnchorX = params.x
            dragAnchorY = params.y
        } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            // 手势结束：后续推导即落真实 bounds（应用按窗口尺寸重排）
            dragging = false
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
        animateContentRect(from, to, geometryAnimDuration(220L)) { _ ->
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

    /**
     * 顶部安全带（px）= 当前真实状态栏高度（适配器动态读取，随旋转/异形屏自适应；不写死）。
     * 适配器全线失败时退回本窗口实测帧偏移（同源观测量：NO_LIMITS 覆盖窗帘的 frame 偏移
     * 即状态栏高度）；再失败按 0（核心策略自动退化为仅 margin 夹取）。
     */
    private fun topSafeInsetPx(): Int =
        SystemBarMetrics.statusBarHeightPx().takeIf { it > 0 }
            ?: frameOffsetY.takeIf { it > 0 }
            ?: 0

    /** 底部安全带（px）= 当前真实导航栏高度（同上，动态读取；不写死）。 */
    private fun bottomSafeInsetPx(): Int =
        SystemBarMetrics.navigationBarHeightPx().takeIf { it > 0 } ?: 0

    private fun updateSmoothBounds() {
        if (isDestroyed || currentTaskId == -1) return

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

        applySmoothBounds(rect, animated = false)
    }

    /**
     * 显式矩形入口（M7 几何动画逐帧调用）：跳过 params/布局推导，其余语义与推导路径一致。
     * 不做 "rect 不得大于 root" 守卫——那是推导路径的异常检测；动画中的目标矩形由我们
     * 自己计算，root 的布局尺寸可能滞后一帧，守卫会误伤动画帧。
     */
    private fun updateSmoothBounds(rect: android.graphics.Rect) {
        if (currentTaskId == -1) return
        if (rect.width() <= 0 || rect.height() <= 0) return
        applySmoothBounds(rect, animated = true)
    }

    /**
     * 几何落地共享尾部（两条入口）：触摸洞 + 内容盒 + 后端应用。
     * [animated]（或 mini/手势/惯性/夹取进行中）= 过渡态：leash 缩放保持流畅；
     * 静止态走后端结算：[WindowBackend.settleGeometry]——**首次结算塑形虚拟尺寸**
     *（近全屏宽 × 窗口比例、等比缩小显示：UI 元素为全屏版缩放、内容更密——
     * 对齐 ColorOS/澎湃OS3 小窗观感），**此后一切尺寸变化一律纯表面缩放**
     *（虚拟尺寸恒定、应用零重排、无损放大缩小）。
     */
    private fun applySmoothBounds(rect: android.graphics.Rect, animated: Boolean) {
        // rect 为窗口卡（外壳）矩形；内容盒 = 应用内容区 = 卡内沿去掉固定外壳。
        val contentBox = contentBoxOf(rect)

        // 触摸洞 = 应用内容区精确矩形（正常态：内容交互直通应用；外壳条/边距由 chrome 自持）
        if (contentTouchThrough) {
            WindowSessions.chromeTouchHoles[windowId] = android.graphics.Rect(contentBox)
        } else {
            WindowSessions.chromeTouchHoles.remove(windowId)
        }

        val settling = !animated && !miniActive && !resizeGesture && !dragging &&
            flingXAnimation?.isRunning != true &&
            flingYAnimation?.isRunning != true &&
            keepInScreenAnimator?.isRunning != true
        if (settling) {
            // 静止结算：首次塑形虚拟尺寸（近全屏宽、应用按全屏布局一次）；此后 = 纯缩放（零重排）
            YAMFManager.windowBackend.settleGeometry(currentTaskId, contentBox)
        } else if (YAMFManager.windowBackend.currentGeometry(currentTaskId) != contentBox) {
            // 系统窗口后端：几何落地（leash 变换或兼容路径由实现决定，宿主不感知）
            YAMFManager.windowBackend.applyGeometry(currentTaskId, contentBox)
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
            revealChromeOrDropEntry()
        }
    }

    private fun updateTask(taskInfo: ActivityManager.RunningTaskInfo) {
        currentTaskId = taskInfo.taskId
        RunMainThreadQueue.add {
            if (taskInfo.isVisible.not()) {
                delay(500) // fixme: use a method that directly determines visibility
            }

            // 任务描述色：TaskInfo 公开字段（系统 fillTaskInfo 恒填充）——无需额外服务查询/反射
            val backgroundColor = taskInfo.taskDescription?.backgroundColor ?: 0
            val navigationBarColor = backgroundColor

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

    override fun markOpenAsMini(dropX: Int, dropY: Int) {
        openAsMiniPending = true
        if (dropX >= 0 && dropY >= 0) {
            dropPoint = android.graphics.Point(dropX, dropY)
        }
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
            // 焦点："浮窗不持焦"由外壳窗口自身的 FLAG_NOT_FOCUSABLE 在**窗口级**保证。
            // 不得再对宿主任务 setFocusable(false)——源码实锤（WindowContainer.isFocusable
            // 父链判定 / TaskFragment.canBeResumed 要求 isTopActivityFocusable /
            // TaskDisplayArea.getFocusedRootTask 只取 isFocusableAndVisible 的根）：
            // 任务级失焦会让活动/承载根失去焦点资格，活动被永久晾在 PAUSED
            // （依赖 resume 的应用如微信会一直加载），并与 openTask 的置焦赛跑。
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
        // 焦点不在此处置 false："浮窗不持焦"由外壳窗口的 FLAG_NOT_FOCUSABLE（窗口级）保证；
        // 任务级 setFocusable(false) 会经父链使活动失去焦点资格（无法 resume，源码实锤）。
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
        // 刷新率提升随动画取消一并撤销
        setFlightFrameRateBoost(false)
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

            // 夹取数学 = 核心策略（逻辑层只管策略，宿主只读平台值并执行）
            val offset = ClampPolicy.clampOffset(
                x = params.x,
                y = params.y,
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                frameOffsetX = frameOffsetX,
                frameOffsetY = frameOffsetY
            )
            val targetX = offset.x
            val targetY = offset.y

            if (targetX == params.x && targetY == params.y) {
                // 已就位：直接落真实 bounds（惯性/夹取链的静止收尾）
                updateSmoothBounds()
                return@post
            }

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
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            // 夹取结束：落真实 bounds（应用按窗口尺寸/位置重排）
                            updateSmoothBounds()
                        }
                    })
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
     * 外壳固定厚度：窗口卡内沿与应用内容区之差。应用内容区（= 用户设定的"窗口尺寸"）
     * 才是 leash 缩放盒；mini 分除、等比适配均以内容区为基准。
     * - 点击态：2dp 边距 + 顶部拖动栏（白底 + 三点）；
     * - mini：贴边边框（均匀厚度，装饰对齐澎湃OS小窗）；
     * - 进出 mini 动画：取动画插值值，几何平滑过渡。
     */
    private fun shellInsets(): MiniPolicy.ShellInsets {
        animInsets?.let { return it }
        return if (miniActive) miniInsets() else normalInsets()
    }

    /** 点击态外壳：应用内容与窗口边缘齐平（0 边距，顶栏/底栏与内容严丝合缝）+ 顶部拖动栏。 */
    private fun normalInsets(): MiniPolicy.ShellInsets {
        val strip = binding.ibSuper.height.takeIf { it > 0 } ?: 18.dpToPx().toInt()
        return MiniPolicy.ShellInsets(0, strip, 0, 0)
    }

    /** mini 外壳：贴边边框。内容顶到描边内缘——描边画在 cvParent 边界内（内缘=边界+描边宽），
     *  卡片与 cvParent 同盒（XML 边距已归零）⇒ 内衬 = 描边宽。
     *  真机实测（1264×2780@515dpi）：描边 19px。 */
    private fun miniInsets(): MiniPolicy.ShellInsets {
        val bezel = MINI_BEZEL_DP.dpToPx().toInt()
        return MiniPolicy.ShellInsets(bezel, bezel, bezel, bezel)
    }

    /** 窗口卡矩形 → 应用内容区矩形（leash 缩放盒；宽高下限 1px 防动画终点负值）。 */
    private fun contentBoxOf(card: android.graphics.Rect): android.graphics.Rect {
        val insets = shellInsets()
        val left = card.left + insets.left
        val top = card.top + insets.top
        return android.graphics.Rect(
            left,
            top,
            (card.right - insets.right).coerceAtLeast(left + 1),
            (card.bottom - insets.bottom).coerceAtLeast(top + 1)
        )
    }

    /**
     * 点击态外壳装饰：应用方角（"可点击时不需要圆角"）+ 白顶栏 + 底栏手势。
     * 装饰只承担顶/底栏与边框（应用本体不做任何裁剪/圆角 —— 澎湃OS小窗模型）。
     * 顶部无描边（strokeWidth=0）：窗口外沿不画任何方框（应用内容即窗口本体）；
     * 应用与顶栏/底栏齐平（[normalInsets] 边距归零）——真机逐像素实锤过旧 6px 透缝与
     * #20000000 方框的存在。
     */
    private fun applyNormalChrome() {
        binding.cvBackground.radius = 0f
        binding.cvParent.radius = 0f
        binding.cvParent.strokeWidth = 0
        binding.cvParent.setStrokeColor(ColorStateList.valueOf(context.getColor(R.color.transparentOutline)))
        binding.ibSuper.visibility = View.VISIBLE
        binding.ibSuper.alpha = 1f
    }

    /**
     * 顶/底栏圆角（设置项 barRoundedCorner）：白顶栏背景 + 底部白容器（手势线=灰，见布局）。
     * 与内容贴合的一侧恒为方角（顶栏下缘 / 底栏上缘），只圆外侧两角——"贴住应用"的观感。
     */
    private fun applyHandleBarChrome() {
        val r = config.barRoundedCorner.dpToPx()
        binding.ibSuper.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFFFFFFFF.toInt())
            // 上两角圆、下缘方（紧贴应用顶边）
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        // 下两角圆、上缘方（紧贴应用底边）
        binding.cvBar.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(0f)
            .setTopRightCornerSize(0f)
            .setBottomLeftCornerSize(r)
            .setBottomRightCornerSize(r)
            .build()
    }

    /**
     * mini 外壳装饰（对齐澎湃OS）：银色圆角边框环盖住应用方角 ——
     * 外沿圆角 = 圆角设置，厚度 = 边框宽，内沿圆角 = 外沿-厚度（同心）；
     * 可见圆角由边框提供，应用本体保持方角。顶栏（白条+三点）在 mini 隐藏。
     */
    private fun applyMiniChrome() {
        val bezel = MINI_BEZEL_DP.dpToPx()
        val r = config.windowRoundedCorner.dpToPx()
        binding.cvParent.radius = r
        binding.cvBackground.radius = (r - bezel).coerceAtLeast(0f)
        binding.cvParent.strokeWidth = bezel.toInt()
        binding.cvParent.setStrokeColor(ColorStateList.valueOf(context.getColor(R.color.window_bezel)))
        // 顶栏不在此处硬切：交由 [animateSuperBarAlpha] 随几何动画渐隐（动画期间条带靠它覆盖，
        // 硬切会让"应被白条覆盖"的区域透出壁纸/时钟 = 内容与边框错位）。
    }

    /**
     * 顶栏（ibSuper 白条）透明度动画：顶栏是**覆盖在内容之上**的装饰条——
     * 正常态顶部内衬 = 边距+条高，动画期间若顶栏缺失，这段条带会透明透出底下的壁纸/壁纸时钟
     * （真机逐帧实锤：开启动画中内容顶部与边框内沿之间出现 16~数十 px 的壁纸蓝色条带）。
     * 进 mini 渐隐（先亮后隐，最后 GONE）；出 mini 渐显（快速到 1，覆盖条带）。
     */
    private fun animateSuperBarAlpha(from: Float, to: Float, durationMs: Long, hideAtEnd: Boolean) {
        superBarAlphaAnimator?.cancel()
        superBarAlphaAnimator = null
        if (durationMs <= 0L) {
            if (hideAtEnd) {
                binding.ibSuper.visibility = View.GONE
                binding.ibSuper.alpha = 1f
            } else {
                binding.ibSuper.visibility = View.VISIBLE
                binding.ibSuper.alpha = to
            }
            return
        }
        runCatching { binding.ibSuper.visibility = View.VISIBLE }
        binding.ibSuper.alpha = from
        superBarAlphaAnimator = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = if (to > from) DecelerateInterpolator() else AccelerateInterpolator()
            addUpdateListener { binding.ibSuper.alpha = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (superBarAlphaAnimator !== animation) return
                    superBarAlphaAnimator = null
                    if (hideAtEnd) {
                        binding.ibSuper.visibility = View.GONE
                        binding.ibSuper.alpha = 1f
                    }
                }
            })
            start()
        }
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
        insetsFrom: MiniPolicy.ShellInsets? = null,
        insetsTo: MiniPolicy.ShellInsets? = null,
        interpolator: android.animation.TimeInterpolator = DecelerateInterpolator(),
        onFrame: ((Float) -> Unit)? = null,
        onEnd: ((cancelled: Boolean) -> Unit)? = null
    ) {
        // 内容矩形动画独占几何：取消惯性/夹取/上一次几何动画（修复"框自己跳走"）
        cancelGeometryAnimators()
        animDeltaW = (binding.cvParent.width - binding.cvBackground.width).coerceAtLeast(0)
        animDeltaH = (binding.cvParent.height - binding.cvBackground.height).coerceAtLeast(0)
        if (durationMs <= 0L || from == to) {
            animInsets = null
            applyContentRectFrame(to)
            onFrame?.invoke(1f)
            onEnd?.invoke(false)
            return
        }
        // 飞行期间请求最高刷新率（结束/取消复位）——帧率吃满手机最高刷新率
        setFlightFrameRateBoost(true)
        val animInterp = interpolator
        boundsAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            this.interpolator = animInterp
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                // 外壳厚度随模式切换逐帧插值（点击态 ↔ mini），内容盒不做跳变
                if (insetsFrom != null && insetsTo != null) {
                    animInsets = MiniPolicy.ShellInsets.lerp(insetsFrom, insetsTo, f)
                }
                applyContentRectFrame(
                    android.graphics.Rect(
                        (from.left + (to.left - from.left) * f).toInt(),
                        (from.top + (to.top - from.top) * f).toInt(),
                        (from.right + (to.right - from.right) * f).toInt(),
                        (from.bottom + (to.bottom - from.bottom) * f).toInt()
                    )
                )
                onFrame?.invoke(f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (boundsAnimator === animation) boundsAnimator = null
                    animInsets = null
                    if (cancelled) {
                        // 被打断（Superseded）：先跑终态收尾（模式/装饰/回灌），再落终态几何——
                        // 不留"半程卡死"的中间态；销毁中的窗口不再补几何（视图已摘除）
                        onEnd?.invoke(true)
                        if (!isDestroyed) applyContentRectFrame(to)
                    }
                    onFrame?.invoke(1f)
                    setFlightFrameRateBoost(false)
                    if (!cancelled) onEnd?.invoke(false)
                }
            })
            start()
        }
    }

    /** 几何动画时长：尊重开发者选项动画缩放；缩放为 0 时立即完成；跟随模块动画速度配置。 */
    private fun geometryAnimDuration(base: Long = AnimTimingPolicy.BASE_DURATION_MS): Long {
        val scale = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
            )
        }.getOrDefault(1f)
        if (scale <= 0f) return 0L
        // 时长解析 = 核心策略（缩放 0 → 立即完成；配置 ≥ 上限视为自动用 base）
        return AnimTimingPolicy.resolveDuration(config.animationSpeed, scale, base)
    }

    /** 几何诊断：输出本窗口几何/可见性/透明度全景（adb: am broadcast -a com.buildsession.betterYAMF.action.DUMP_GEOMETRY） */
    override fun dumpGeometry() {
        runMain {
            runCatching {
                val p = binding.root.layoutParams as? WindowManager.LayoutParams
                val loc = IntArray(2)
                binding.root.getLocationOnScreen(loc)
                val insets = shellInsets()
                val card = currentContentRect()
                log(
                    TAG,
                    "GEOM window=$windowId task=$currentTaskId destroyed=$isDestroyed mini=$miniActive coreMode=${shell.snapshot().mode} " +
                        "params=(${p?.x},${p?.y}) loc=(${loc[0]},${loc[1]}) " +
                        "root=${binding.root.width}x${binding.root.height} alpha=${binding.root.alpha} vis=${binding.root.visibility} " +
                        "revealed=$chromeRevealed pendingReveal=$pendingReveal " +
                        "cvParent=(l=${binding.cvParent.left},t=${binding.cvParent.top}," +
                        "${binding.cvParent.width}x${binding.cvParent.height},vis=${binding.cvParent.visibility}) " +
                        "cvBackground=(l=${binding.cvBackground.left},t=${binding.cvBackground.top}," +
                        "${binding.cvBackground.width}x${binding.cvBackground.height},vis=${binding.cvBackground.visibility}) " +
                        "ibSuper=(l=${binding.ibSuper.left},t=${binding.ibSuper.top}," +
                        "${binding.ibSuper.width}x${binding.ibSuper.height},vis=${binding.ibSuper.visibility}) " +
                        "insets=(l=${insets.left},t=${insets.top},r=${insets.right},b=${insets.bottom}) " +
                        "contentBox=${card?.let { contentBoxOf(it) } ?: "n/a"} " +
                        "offset=(${frameOffsetX},${frameOffsetY},known=$frameOffsetKnown)"
                )
            }.onFailure { log(TAG, "dumpGeometry failed", it) }
        }
    }

    /**
     * 打开揭示的统一入口：
     * - 回收区投放（openAsMini + 松手点，且任务已认领/尺寸已测量）：不揭示普通窗口、不等 500ms，
     *   直接发 [WindowInputEvent.RequestMini]——**核心状态机转身（mode=MINI）** 后回发
     *   [WindowCommand.EnterMini]，由 [execEnterMini] 执行"从松手点区域一镜收缩入位"的登场动画
     *   （核心 mode 必须同步，否则单击放大被吞）。未就绪时退回常规揭示，任务就绪后经本入口再走投放。
     * - 其余场景：常规淡入揭示（openAsMini 的 500ms 后 RequestMini 流程不变）。
     */
    private fun revealChromeOrDropEntry() {
        if (openAsMiniPending && dropPoint != null && currentTaskId > 0 &&
            originalWidth > 0 && originalHeight > 0 && binding.cvParent.width > 0
        ) {
            openAsMiniPending = false
            miniRequested = true
            dispatchInput(WindowInputEvent.RequestMini(windowId, System.currentTimeMillis()))
        } else {
            revealChrome()
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

                if (binding.ibRightResize.visibility == View.VISIBLE) {
                    isResize = true
                }
            }.onFailure { log(TAG, "revealChrome failed", it) }
        }
    }

    /**
     * 几何飞行的刷新率提升：向系统请求显示器最高刷新率（帧率吃满），结束/取消即复位——
     * 动画由 Choreographer 逐帧驱动，显示器不升频则全程 60Hz（"帧率低"的真因）。
     * 双通道投票（取运行时最高模式值，不写死）：①LayoutParams.preferredRefreshRate（WMS 侧
     * DisplayModeDirector 依票选型）；②窗口 SurfaceControl 的 frame rate（SF 层票，平台自身
     * ViewRootImpl#setPreferredFrameRate :14329 即走此 Transaction 通道），FIXED_SOURCE =
     * 内容严格依赖该帧率，结束用 clearFrameRate 撤销。
     */
    private fun setFlightFrameRateBoost(enabled: Boolean) {
        if (flightFrameRateBoosted == enabled) return
        flightFrameRateBoosted = enabled
        runCatching {
            val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            val maxHz = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f
            if (enabled && maxHz <= 0f) return@runCatching

            // 通道 1（WMS 票）：LayoutParams.preferredRefreshRate——DisplayModeDirector 依票选型
            val params = binding.root.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                params.preferredRefreshRate = if (enabled) maxHz else 0f
                Instances.windowManager.updateViewLayout(binding.root, params)
            }

            // 通道 2（SF 层票）：窗口 SurfaceControl 的 frame rate——
            // 反射细节收口在适配器（ViewSurfaceOps），宿主不直接碰系统内部
            ViewSurfaceOps.setViewSurfaceFrameRate(binding.root, maxHz, enabled)
            log(TAG, "flight frame rate boost=${if (enabled) "${maxHz}Hz" else "cleared"} window=$windowId")
        }.onFailure { log(TAG, "flight frame rate boost($enabled) failed", it) }
    }

    private fun execEnterMini(corner: DropKind, cmdId: Long) {
        if (originalWidth <= 0 || originalHeight <= 0) {
            log(TAG, "execEnterMini skipped: size not measured")
            reportCommandResult(cmdId, CommandStatus.FAILED)
            return
        }
        // 新几何动画接管前，先把上一次未决动画打断收尾（Superseded：落终态 + 回灌）
        cancelGeometryAnimators()
        // 回收区投放登场：消费一次性松手点（非投放路径为 null → 走原"从当前矩形收缩"行为）
        val drop = dropPoint
        dropPoint = null

        isResize = false

        binding.rootClickMask.visibility = View.VISIBLE
        binding.rlBarControllerBottom.visibility = View.GONE
        binding.rlBarControllerSide.visibility = View.GONE
        binding.ibRightResize.visibility = View.GONE

        // mini 状态下整卡可点（点任意位置放大回正常窗口），关闭内容透传洞
        contentTouchThrough = false
        WindowSessions.chromeTouchHoles.remove(windowId)

        val dm = context.resources.displayMetrics
        // 安全带 = 当前真实系统栏高度（适配器动态读取；顶=状态栏，底=导航栏）
        val topInsetPx = topSafeInsetPx()
        val bottomInsetPx = bottomSafeInsetPx()
        val centered = currentContentRect()
        // 位置记忆：记住进 mini 前的（默认居中）位置（重复进入不覆盖；退出 mini 回此位置）
        miniMemory.rememberIfAbsent(centered?.toWindowRect())
        // M7：内容矩形动画（chrome 与 leash 同源）——收缩与贴角在一个动画里完成；
        // 目标几何由核心策略（MiniPolicy）计算，宿主只传值并执行。
        // 分除基准 = 应用内容区（vSizePreviewer 尺寸 = 用户设定的"窗口尺寸"）；
        // mini 外壳 = 贴边边框（均匀厚度），点击态外壳（白顶栏+边距）随动画逐帧过渡。
        // 投放登场：起点外壳为"零内衬"（整幅内容的大卡片），mini 描边随飞行渐成。
        val fromInsets = if (drop != null) MiniPolicy.ShellInsets(0, 0, 0, 0) else shellInsets()
        miniActive = true
        applyMiniChrome()
        // 首帧内衬 = 起点内衬（防"卡还是点击态、内衬已切 mini"的 1 帧内衬跳变）
        animInsets = fromInsets
        val toInsets = miniInsets()
        // mini 目标 = 当前内容区**等比缩放**到磁吸槽位（保持宽高比——参考图帧_02/帧_05 实锤：
        // mini 与展开态是同一 UI 的纯缩放）。比例一致 ⇒ mini 期间几何走 leash 缩放、
        // 应用零重排，动画天然平滑。见 [MiniPolicy.enterTarget]。
        val fromContentW = ((centered?.width() ?: 0) - fromInsets.width).coerceAtLeast(1)
        val fromContentH = ((centered?.height() ?: 0) - fromInsets.height).coerceAtLeast(1)
        val to = MiniPolicy.enterTarget(
            corner = corner,
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            marginPx = 8.dpToPx().toInt(),
            topInsetPx = topInsetPx,
            bottomInsetPx = bottomInsetPx,
            currentTop = centered?.top,
            contentWidth = fromContentW,
            contentHeight = fromContentH,
            insets = toInsets
        ).toAndroidRect()

        if (drop == null) {
            // 顶栏渐隐：动画期间先保持可见（覆盖其条带），结束再 GONE（见 [animateSuperBarAlpha]）
            animateSuperBarAlpha(1f, 0f, geometryAnimDuration(), hideAtEnd = true)
            animateContentRect(centered ?: to, to, geometryAnimDuration(), insetsFrom = fromInsets, insetsTo = toInsets) { cancelled ->
                reportCommandResult(cmdId, if (cancelled) CommandStatus.SUPERSEDED else CommandStatus.DONE)
                // 终态：补一次结算（几何/轮廓落定；mini 态保持 leash 缩放，不落真实 bounds）
                binding.root.post(::updateSmoothBounds)
            }
            return
        }

        // —— 回收区投放登场（对齐"米窗示范"视频逐帧量化结论：一条从大到小的连续强减速飞行，
        //    右缘锚定槽位右上角、圆角/描边随飞行渐成，落点即 mini，全程无"可点击中间态"）——
        pendingReveal = false
        chromeRevealed = true
        binding.ibSuper.visibility = View.GONE
        // 起点几何 = 核心策略（右缘锚定目标右缘；顶边夹在状态栏/底栏安全带内）
        val start = DropEntryPolicy.startRect(
            target = to.toWindowRect(),
            screenHeight = dm.heightPixels,
            marginPx = 8.dpToPx().toInt(),
            topInsetPx = topInsetPx,
            bottomInsetPx = bottomInsetPx
        ).toAndroidRect()
        // 起手帧内衬 = 起点内衬（零内衬大卡片，防 1 帧 mini 内衬跳变）
        animInsets = fromInsets
        // 起手帧直接落到起点（root 仍透明），随后轻淡入 + 一条减速飞行入位
        applyContentRectFrame(start)
        binding.root.alpha = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DropEntryPolicy.START_FADE_MS
            addUpdateListener { anim -> binding.root.alpha = anim.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    binding.root.alpha = 1f
                }
            })
            start()
        }
        val radius = config.windowRoundedCorner.dpToPx()
        val bezel = MINI_BEZEL_DP.dpToPx()
        // 起手即"零圆角/零描边"（与 f=0 帧一致，防首帧闪现终态 mini 装饰）
        binding.cvParent.radius = 0f
        binding.cvParent.strokeWidth = 0
        binding.cvBackground.radius = 0f
        log(TAG, "drop entry task=$currentTaskId from=$start to=$to")
        animateContentRect(
            start, to, geometryAnimDuration(base = DropEntryPolicy.DURATION_BASE_MS),
            insetsFrom = fromInsets, insetsTo = toInsets,
            // 视频实测起步即高速、长尾收束；给首帧一丝缓起更细腻
            interpolator = PathInterpolator(
                DropEntryPolicy.CURVE_X1, DropEntryPolicy.CURVE_Y1,
                DropEntryPolicy.CURVE_X2, DropEntryPolicy.CURVE_Y2
            ),
            onFrame = { f ->
                // 圆角/描边随飞行渐成（大卡片方角 → mini 描边边框）
                binding.cvParent.radius = radius * f
                binding.cvParent.strokeWidth = (bezel * f).toInt()
                binding.cvBackground.radius = ((radius - bezel) * f).coerceAtLeast(0f)
            }
        ) { cancelled ->
            reportCommandResult(cmdId, if (cancelled) CommandStatus.SUPERSEDED else CommandStatus.DONE)
            binding.root.post(::updateSmoothBounds)
        }
    }

    private fun execExitMini(cmdId: Long) {
        // 新几何动画接管前，先把上一次未决动画打断收尾（Superseded：落终态 + 回灌）
        cancelGeometryAnimators()
        // 几何不可用 = 命令失败：先回灌（核心回退模式），再做任何视觉翻转——状态不分叉
        val from = currentContentRect() ?: run {
            reportCommandResult(cmdId, CommandStatus.FAILED)
            return
        }
        isResize = true
        contentTouchThrough = true
        binding.rootClickMask.visibility = View.GONE
        binding.ibRightResize.visibility = View.VISIBLE
        if (orientation == 0) {
            binding.rlBarControllerBottom.visibility = View.VISIBLE
        } else {
            binding.rlBarControllerSide.visibility = View.VISIBLE
        }
        // M7：位置记忆——回到进 mini 前的矩形；无记忆则围绕当前中心展开。
        // 两者都经安全边距夹取（贴屏会让圆角/边框/阴影出屏 = "边缘消失"缺陷）；
        // 目标几何由核心策略（MiniPolicy）计算。外壳厚度 mini→点击态随动画过渡。
        val dm = context.resources.displayMetrics
        val fromInsets = shellInsets()
        val to = MiniPolicy.exitTarget(
            remembered = miniMemory.take(),
            current = from.toWindowRect(),
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            marginPx = 8.dpToPx().toInt(),
            topInsetPx = topSafeInsetPx()
        ).toAndroidRect()
        val toInsets = normalInsets()
        // 顶栏渐显：快速点亮（覆盖"边距+条高"条带，避免动画期间透出壁纸/时钟 = 错位感）
        animateSuperBarAlpha(0f, 1f, (geometryAnimDuration() * 0.45f).toLong().coerceAtLeast(1L), hideAtEnd = false)
        // mini 期间几何走 leash 缩放（应用零重排）；正常态静止点由静止结算落真实 bounds
        //（应用按窗口尺寸重排一次，见 applySmoothBounds）。
        animateContentRect(from, to, geometryAnimDuration(), insetsFrom = fromInsets, insetsTo = toInsets) { cancelled ->
            // 终态：退出 mini——恢复点击态外壳装饰与 wrap-content（后续测量/吸附基于自然尺寸）
            miniActive = false
            applyNormalChrome()
            setBackgroundWrapContent()
            setParrentWrapContent()
            reportCommandResult(cmdId, if (cancelled) CommandStatus.SUPERSEDED else CommandStatus.DONE)
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
        resizeGesture = true
        binding.vSizePreviewer.layoutParams.let {
            resizeBeginWidth = it.width
            resizeBeginHeight = it.height
        }
        binding.vSizePreviewer.visibility = View.VISIBLE
        binding.cvParent.strokeWidth = 0
    }

    private fun execResizePreviewUpdate(command: WindowCommand.ResizePreviewUpdate) {
        // ColorOS 缩放逻辑：拖拽 = 等比纯缩放（宽高比严格保持，绝不变形）。
        // 这是"无损放大缩小"的手势策略位——尺寸变化最终由后端以表面缩放落地
        //（任务虚拟尺寸恒定、应用零重排），见 SettleGeometry/applySmoothBounds。
        val factor = ResizePolicy.dragScaleFactor(
            resizeBeginWidth, resizeBeginHeight, command.dx, command.dy
        )
        val (targetWidth, targetHeight) = ResizePolicy.scaledSize(
            resizeBeginWidth, resizeBeginHeight, factor
        )
        binding.vSizePreviewer.updateLayoutParams {
            width = targetWidth
            height = targetHeight
        }
    }

    private fun execResizePreviewCommit() {
        // 手势结束：keepInScreen 推导（纯平移夹取）→ 静止结算（后端以纯表面缩放落地，
        // 任务虚拟尺寸恒定、应用零重排——无损缩放）
        resizeGesture = false
        // ⚠ 必须 INVISIBLE 而不是 GONE：vSizePreviewer 是窗口卡（wrap_content）的尺寸驱动源，
        // GONE 会把整卡折叠成仅剩条栏（真机实证：root 33x149，外壳/手柄全部失效，
        // "resize 一次后装饰消失、手柄再也摸不到"）。INVISIBLE 保持参与布局 = 新尺寸留存。
        binding.vSizePreviewer.visibility = View.INVISIBLE
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
            dispatchScreenMetrics()
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

