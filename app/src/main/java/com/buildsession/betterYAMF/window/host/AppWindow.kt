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
import android.content.pm.IPackageManagerHidden
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
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
import android.widget.ImageView
import android.widget.ImageButton
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.flingAnimationOf
import androidx.wear.widget.RoundedDrawable
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.google.android.material.color.MaterialColors
import com.buildsession.betterYAMF.common.getAttr
import com.buildsession.betterYAMF.common.onException
import com.buildsession.betterYAMF.common.runMain
import com.buildsession.betterYAMF.databinding.WindowAppBinding
import com.buildsession.betterYAMF.window.core.CloseOrigin
import com.buildsession.betterYAMF.window.core.FullscreenOrigin
import com.buildsession.betterYAMF.window.core.RegionId
import com.buildsession.betterYAMF.window.core.WindowCommand
import com.buildsession.betterYAMF.window.core.WindowInputEvent
import com.buildsession.betterYAMF.window.core.WindowRegistration
import com.buildsession.betterYAMF.window.core.WindowShellController
import com.buildsession.betterYAMF.window.core.WindowShellRegistry
import com.buildsession.betterYAMF.common.model.resolveWindowSizePx
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.services.YAMFManager.config
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.RunMainThreadQueue
import com.buildsession.betterYAMF.xposed.utils.TipUtil
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.animateAlpha
import com.buildsession.betterYAMF.xposed.utils.animateResize
import com.buildsession.betterYAMF.xposed.utils.animateScaleThenResize
import com.buildsession.betterYAMF.xposed.utils.dpToPx
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
    private val onWindowInit: (AppWindow, Int) -> Unit
) {
    companion object {
        const val TAG = "reYAMF_AppWindow"
        const val ACTION_RESET_ALL_WINDOW = "com.buildsession.betterYAMF.ui.window.action.ACTION_RESET_ALL_WINDOW"
        private const val HOME_REFRONT_DELAY_MS = 0L
        private const val MINI_START_DELAY_MS = 500L
        private val shellRegistry = WindowShellRegistry()
        private val windowIdGen = java.util.concurrent.atomic.AtomicInteger(1)
    }

    val windowId = windowIdGen.getAndIncrement()
    lateinit var binding: WindowAppBinding
    private lateinit var shell: WindowShellController

    var currentTaskId = -1
    private var isDestroyed = false
    private var openAsMiniPending = false
    private var miniRequested = false

    private var originalWidth: Int = 0
    private var originalHeight: Int = 0
    private var isResize: Boolean = true
    private var orientation = 0
    private var params = WindowManager.LayoutParams()
    private var keepInScreenAnimator: ValueAnimator? = null
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
            if (intent.action == ACTION_RESET_ALL_WINDOW) {
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
        params.setTitle(YAMFManager.CHROME_WINDOW_TITLE)

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

        binding.ibSuper.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(windowId, System.currentTimeMillis(), RegionId.HANDLE))
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

        binding.ibClose.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(windowId, System.currentTimeMillis(), RegionId.BUTTON_CLOSE))
        }

        binding.ibFullscreen.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(windowId, System.currentTimeMillis(), RegionId.BUTTON_FULLSCREEN))
        }

        binding.ibMinimize.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(windowId, System.currentTimeMillis(), RegionId.BUTTON_MINIMIZE))
        }

        binding.ibCollapse.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(windowId, System.currentTimeMillis(), RegionId.BUTTON_COLLAPSE))
        }

        binding.ibSuperClose.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(windowId, System.currentTimeMillis(), RegionId.SUPER_CLOSE))
        }

        shell = shellRegistry.register(
            WindowRegistration(windowId, Resources.getSystem().displayMetrics.density)
        )
        updateShellScreenMetrics()
        
        context.registerReceiver(broadcastReceiver, IntentFilter(ACTION_RESET_ALL_WINDOW), Context.RECEIVER_EXPORTED)
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
            binding.cvappIcon.radius = config.windowRoundedCorner.dpToPx()


            binding.cvParent.radius = (config.windowRoundedCorner+2).dpToPx()
            originalWidth = binding.cvParent.width
            originalHeight = binding.cvParent.height
            binding.cvParent.visibility = View.VISIBLE

            animateScaleThenResize(
                binding.cvBackground,
                0F, 0F,
                1F, 1F,
                0.5F, 0.5F,
                originalWidth, originalHeight,
                context
            ) {
                setBackgroundWrapContent()

                CoroutineScope(Dispatchers.Main).launch {
                    delay(200)

                    binding.cvParent.strokeWidth = 2.dpToPx().toInt()
                }

                isResize = true
            }
        }

    }

    fun onDestroy(releaseTask: Boolean = true) {
        if (isDestroyed) return
        isDestroyed = true

        // 关闭小窗时任务回到全屏（否则任务会残留为没有外壳的 freeform 小窗）；
        // 任务移交新壳（同任务重复开窗）时静默销毁，不做释放。
        if (releaseTask) {
            runCatching {
                if (currentTaskId > 0) YAMFManager.switchTaskToFullscreen(currentTaskId)
            }.onFailure {
                log(TAG, "release task to fullscreen failed", it)
            }
        }
        
        runCatching { context.unregisterReceiver(broadcastReceiver) }
        
        YAMFManager.removeWindow(windowId)
        YAMFManager.chromeTouchHoles.remove(windowId)
        YAMFManager.pendingSmoothFreeformApps.entries.removeAll { it.value === this }
        if (::shell.isInitialized) {
            shellRegistry.unregister(shell)
        }
        YAMFManager.smoothFreeformTasks.remove(currentTaskId)
        YAMFManager.smoothFreeformBounds.remove(currentTaskId)
        YAMFManager.smoothCornerRadiusPx.remove(currentTaskId)
        YAMFManager.smoothTaskActivities.remove(currentTaskId)
        
        runMain {
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
        YAMFManager.moveToTop(windowId)
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
            is WindowCommand.EnterCollapsed -> execEnterCollapsed()
            is WindowCommand.ExitCollapsed -> execExitCollapsed()
            is WindowCommand.ShowSuperMenu -> execShowSuperMenu()
            is WindowCommand.HideSuperMenu -> execHideSuperMenu()
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
            flingXAnimation?.cancel()
            flingYAnimation?.cancel()
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
            flingXAnimation?.addEndListener { _, _, _, _ ->
                keepInScreen()
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
            flingYAnimation?.addEndListener { _, _, _, _ ->
                keepInScreen()
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

    private fun execShowSuperMenu() {
        animateAlpha(binding.clSuperLayout, 0f, 1f)
    }

    private fun execHideSuperMenu() {
        animateAlpha(binding.clSuperLayout, 1f, 0f)
    }

    private fun execRequestClose(origin: CloseOrigin) {
        when (origin) {
            CloseOrigin.DRAG_UP_BAR -> {
                animateScaleThenResize(
                    binding.cvParent,
                    binding.cvParent.scaleX, binding.cvParent.scaleY,
                    0f, 0f,
                    0.5f, 0.5f,
                    0, 0,
                    context
                ) {
                    onDestroy()
                }
            }
            CloseOrigin.BUTTON_X, CloseOrigin.DOUBLE_TAP_BAR -> {
                isResize = false

                binding.cvappIcon.visibility = View.INVISIBLE
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
                        animateScaleThenResize(
                            binding.cvParent,
                            1F, 1F,
                            0F, 0F,
                            0.5F, 0.5F,
                            0, 0,
                            context
                        ) {
                            onDestroy()
                        }
                    }
                }
            }
        }
    }

    private fun execRequestFullscreen(origin: FullscreenOrigin) {
        if (currentTaskId == -1) return
        when (origin) {
            FullscreenOrigin.BUTTON -> {
                runCatching {
                    YAMFManager.switchTaskToFullscreen(currentTaskId)
                    YAMFManager.windowBackend.bringTaskToFront(currentTaskId)
                }.onFailure { t ->
                    if (t is Error) throw t
                    TipUtil.showToast("${t.message}")
                }.onSuccess {
                    execRequestClose(CloseOrigin.BUTTON_X)
                }
            }
            FullscreenOrigin.DRAG_DOWN_BAR -> {
                runCatching {
                    YAMFManager.switchTaskToFullscreen(currentTaskId)
                    YAMFManager.windowBackend.bringTaskToFront(currentTaskId)
                }.onSuccess {
                    onDestroy()
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

        if (contentTouchThrough) {
            val handleHeight = binding.ibSuper.height.takeIf { it > 0 } ?: 25.dpToPx().toInt()
            YAMFManager.chromeTouchHoles[windowId] = android.graphics.Rect(
                rect.left,
                rect.top + handleHeight,
                rect.right,
                rect.bottom
            )
        } else {
            YAMFManager.chromeTouchHoles.remove(windowId)
        }
        YAMFManager.smoothCornerRadiusPx[currentTaskId] = (config.windowRoundedCorner + 2).dpToPx().toInt()
        
        if (YAMFManager.smoothFreeformBounds[currentTaskId] != rect) {
            // 系统窗口后端：几何落地（leash 变换或兼容路径由实现决定，宿主不感知）
            YAMFManager.windowBackend.applyGeometry(currentTaskId, rect)
        }
    }

    /** 系统窗口后端把任务 leash 交给我们（onTaskAdopted 回灌）后，按当前几何立即摆放 */
    fun onOrganizerLeash() {
        runMain {
            runCatching {
                // leash 刚到手：按当前几何立即摆一次位（首帧布局未完成时 updateSmoothBounds 会
                // 自行跳过，布局完成后由 OnGlobalLayout 再同步；不再等待 frameOffset 已知）。
                updateSmoothBounds()
                val taskId = currentTaskId
                val backend = YAMFManager.windowBackend
                if (taskId > 0 && backend.isTaskManaged(taskId)) {
                    if (YAMFManager.smoothFreeformBounds[taskId] != null) {
                        backend.reapplyGeometry(taskId)
                    } else {
                        // 几何未知（首帧布局尚未完成）：先保证任务表面可见，避免后端迁移后
                        // 任务表面处于隐藏等待状态
                        backend.ensureTaskVisible(taskId)
                    }
                }
            }.onFailure { log(TAG, "onOrganizerLeash failed", it) }
        }
    }

    private fun updateTask(taskInfo: ActivityManager.RunningTaskInfo) {
        currentTaskId = taskInfo.taskId
        RunMainThreadQueue.add {
            if (taskInfo.isVisible.not()) {
                delay(500) // fixme: use a method that directly determines visibility
            }

            var backgroundColor = 0
            var statusBarColor = 0
            var navigationBarColor = 0
            var taskDescription: ActivityManager.TaskDescription?

            if (Build.VERSION.SDK_INT < 35) {
                val topActivity = taskInfo.topActivity ?: return@add
                taskDescription = Instances.activityTaskManager.getTaskDescription(taskInfo.taskId) ?: return@add
                val activityInfo = (Instances.iPackageManager as IPackageManagerHidden).getActivityInfoCompat(topActivity, 0, taskInfo.getObjectAs("userId"))

                backgroundColor = taskDescription.backgroundColor
                statusBarColor = taskDescription.backgroundColor
                navigationBarColor = taskDescription.backgroundColor
                
                val iconDrawable = runCatching { taskDescription.icon }.getOrNull()?.let { BitmapDrawable(context.resources, it) } 
                    ?: activityInfo.loadIcon(Instances.packageManager)
                
                binding.appIcon.setImageDrawable(iconDrawable)
                binding.appIcon.scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningTasks = activityManager.getRunningTasks(5)

                for (task in runningTasks) {
                    if (task.taskId == taskInfo.taskId) {
                        val packageName = task.baseActivity?.packageName
                        try {
                            val packageManager = context.packageManager
                            backgroundColor = task.taskDescription!!.backgroundColor
                            statusBarColor = task.taskDescription!!.backgroundColor
                            navigationBarColor = task.taskDescription!!.backgroundColor
                            val iconDrawable = packageManager.getApplicationIcon(packageName!!)
                            binding.appIcon.setImageDrawable(iconDrawable)
                            binding.appIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                        } catch (e: PackageManager.NameNotFoundException) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            if (config.coloredController) {
                val onStateBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(statusBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
                } else {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
                }

                binding.ibClose.imageTintList = ColorStateList.valueOf(onStateBar)
                binding.background.setBackgroundColor(navigationBarColor)

                val onNavigationBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(navigationBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
                } else {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
                }

                binding.ibMinimize.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibFullscreen.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibRightResize.imageTintList = ColorStateList.valueOf(onNavigationBar)
            }
        }
    }

    fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
        val isHome = isHomeTask(taskInfo)
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
        // 另一个小窗（smooth 任务）到前台则允许压在上一层，不抢。
        if (!YAMFManager.smoothFreeformTasks.contains(taskInfo.taskId) && !isDestroyed) {
            keepAboveHome()
        }
    }

    fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        if (!taskInfo.isVisible) {
            return
        }

        if (isHomeTask(taskInfo)) {
            keepAboveHome()
            return
        }

        if (taskInfo.taskId == currentTaskId) {
            updateTask(taskInfo)
        }
    }

    fun markOpenAsMini() {
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
            val dm = context.resources.displayMetrics
            val cx = (dm.widthPixels - 12).toFloat()
            val cy = 150f
            val t = System.currentTimeMillis()
            dispatchInput(WindowInputEvent.Press(windowId, t, RegionId.BODY_MASK, cx, cy))
            dispatchInput(WindowInputEvent.Move(windowId, t, RegionId.BODY_MASK, cx, cy))
            dispatchInput(WindowInputEvent.Up(windowId, t, RegionId.BODY_MASK, cx, cy))
        }.onFailure {
            log(TAG, "requestMiniAtCorner failed", it)
        }
    }

    private var pendingHide: Runnable? = null
    private var reFrontScheduled = false

    fun onTaskVisibilityChanged(visible: Boolean) {
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

    fun onHomeTop() {
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
        val suppressUntil = YAMFManager.recentsGestureSuppressUntilMs
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
        if (YAMFManager.isTouchActive() && attempts < 40) {
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

    private fun isHomeTask(taskInfo: ActivityManager.RunningTaskInfo): Boolean {
        val activityType = runCatching {
            val config = taskInfo.getObject("configuration")
            val windowConfig = config.getObject("windowConfiguration")
            windowConfig.invokeMethod("getActivityType") as Int
        }.getOrElse {
            val topActivity = taskInfo.topActivity
            if (topActivity != null) {
                val pkg = topActivity.packageName
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                val defaultLauncher = resolveInfo?.activityInfo?.packageName

                if (pkg == defaultLauncher ||
                    pkg == "com.android.launcher3" ||
                    pkg == "com.google.android.apps.nexuslauncher" ||
                    (pkg.contains("launcher", ignoreCase = true) && !pkg.contains("service", ignoreCase = true))) 2 else 0
            } else 0
        }
        return activityType == 2
    }

    private fun keepInScreen(animate: Boolean = true) {
        binding.root.post {
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

    private fun animateResizeCentered(
        view: View,
        startWidth: Int,
        endWidth: Int,
        startHeight: Int,
        endHeight: Int,
        onEnd: (() -> Unit)? = null
    ) {
        val params = binding.root.layoutParams as WindowManager.LayoutParams
        val initialWindowWidth = binding.root.width
        val initialWindowHeight = binding.root.height
        val initialViewWidth = if (view.width > 0) view.width else startWidth
        val initialViewHeight = if (view.height > 0) view.height else startHeight
        
        val centerX = params.x + initialWindowWidth / 2
        val centerY = params.y + initialWindowHeight / 2

        animateResize(
            view, startWidth, endWidth, startHeight, endHeight, context,
            onUpdate = { currentViewWidth, currentViewHeight ->
                val currentParams = binding.root.layoutParams as WindowManager.LayoutParams
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                
                val currentWindowWidth = initialWindowWidth + (currentViewWidth - initialViewWidth)
                val currentWindowHeight = initialWindowHeight + (currentViewHeight - initialViewHeight)
                
                // Keep window within screen boundaries during resize
                val targetX = centerX - currentWindowWidth / 2
                val targetY = centerY - currentWindowHeight / 2
                
                currentParams.x = targetX.coerceIn(0, screenWidth - currentWindowWidth)
                currentParams.y = targetY.coerceIn(0, screenHeight - currentWindowHeight)
                
                runCatching {
                    Instances.windowManager.updateViewLayout(binding.root, currentParams)
                }
            },
            onEnd = onEnd
        )
    }

    private fun execEnterMini(corner: com.buildsession.betterYAMF.window.core.DropKind) {
        isResize = false

        binding.rootClickMask.visibility = View.VISIBLE
        binding.rlBarControllerBottom.visibility = View.GONE
        binding.rlBarControllerSide.visibility = View.GONE

        // 侧边最小化比角落 mini 再小一档（1/3），角落为 1/2
        val isSide = corner == com.buildsession.betterYAMF.window.core.DropKind.SIDE_LEFT ||
                corner == com.buildsession.betterYAMF.window.core.DropKind.SIDE_RIGHT
        val miniDivisor = if (isSide) 3 else 2
        binding.cvBackground.updateLayoutParams {
            width = originalWidth / miniDivisor
            height = originalHeight / miniDivisor
        }

        binding.ibRightResize.visibility = View.GONE

        // mini 状态下整卡可点（点任意位置放大回正常窗口），关闭内容透传洞
        contentTouchThrough = false
        YAMFManager.chromeTouchHoles.remove(windowId)

        // 收起后吸附到触发的角落（与演示一致）。
        // 半尺寸布局尚未完成时 root.width 还是旧值，检测到就重试，避免吸到错误位置。
        var snapAttempts = 0
        fun applyMiniSnap() {
            runCatching {
                if (originalWidth > 0 && binding.root.width > originalWidth * 3 / 4 && snapAttempts < 4) {
                    snapAttempts++
                    binding.root.post { applyMiniSnap() }
                    return@runCatching
                }
                val params = binding.root.layoutParams as WindowManager.LayoutParams
                val dm = context.resources.displayMetrics
                val margin = 8.dpToPx().toInt()
                val topInset = 140
                val bottomInset = 120
                when (corner) {
                    com.buildsession.betterYAMF.window.core.DropKind.CORNER_TL -> {
                        params.x = margin
                        params.y = topInset + margin
                    }
                    com.buildsession.betterYAMF.window.core.DropKind.CORNER_BL -> {
                        params.x = margin
                        params.y = dm.heightPixels - binding.root.height - bottomInset - margin
                    }
                    com.buildsession.betterYAMF.window.core.DropKind.CORNER_BR -> {
                        params.x = dm.widthPixels - binding.root.width - margin
                        params.y = dm.heightPixels - binding.root.height - bottomInset - margin
                    }
                    com.buildsession.betterYAMF.window.core.DropKind.SIDE_LEFT -> {
                        params.x = margin
                        params.y = params.y.coerceIn(0, dm.heightPixels - binding.root.height - frameOffsetY)
                    }
                    com.buildsession.betterYAMF.window.core.DropKind.SIDE_RIGHT -> {
                        params.x = dm.widthPixels - binding.root.width - margin
                        params.y = params.y.coerceIn(0, dm.heightPixels - binding.root.height - frameOffsetY)
                    }
                    else -> {
                        params.x = dm.widthPixels - binding.root.width - margin
                        params.y = topInset + margin
                    }
                }
                Instances.windowManager.updateViewLayout(binding.root, params)
                updateSmoothBounds()
            }.onFailure {
                log(TAG, "applyMiniSnap failed", it)
            }
        }
        binding.root.post { applyMiniSnap() }
    }

    private fun execExitMini() {
        isResize = true
        contentTouchThrough = true
        binding.rootClickMask.visibility = View.GONE

        binding.cvBackground.updateLayoutParams {
            width = originalWidth
            height = originalHeight
        }
        animateScaleThenResize(
            binding.cvBackground,
            0.5F, 0.5F,
            1F, 1F,
            0.5F, 0.5F,
            originalWidth, originalHeight,
            context
        ){
            setBackgroundWrapContent()
            setParrentWrapContent()
            keepInScreen()
            binding.root.post(::updateSmoothBounds)
        }

        binding.ibRightResize.visibility = View.VISIBLE
        if (orientation == 0) {
            binding.rlBarControllerBottom.visibility = View.VISIBLE
        } else {
            binding.rlBarControllerSide.visibility = View.VISIBLE
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

    private fun execEnterCollapsed() {
        isResize = false
        contentTouchThrough = false
        binding.rootClickMask.visibility = View.VISIBLE
        binding.rlBarControllerBottom.visibility = View.GONE
        binding.rlBarControllerSide.visibility = View.GONE
        collapseWindow()
    }

    private fun execExitCollapsed() {
        isResize = false
        contentTouchThrough = true
        binding.rootClickMask.visibility = View.GONE
        expandWindow()
    }

    private fun expandWindow() {
        binding.background.visibility = View.VISIBLE

        animateResizeCentered(
            binding.appIcon, 40.dpToPx().toInt(), 0, 40.dpToPx().toInt(), 0) {
            binding.cvappIcon.visibility = View.GONE
            animateResizeCentered(binding.cvBackground, 0, originalWidth, 0, originalHeight) {
                setBackgroundWrapContent()
                setParrentWrapContent()
                binding.cvappIcon.visibility = View.VISIBLE

                CoroutineScope(Dispatchers.Main).launch {
                    delay(200)

                    if (orientation == 0) {
                        binding.rlBarControllerBottom.visibility = View.VISIBLE
                    } else {
                        binding.rlBarControllerSide.visibility = View.VISIBLE
                    }
                }

                binding.cvappIcon.visibility = View.GONE
                isResize = true
                keepInScreen()
            }
        }
    }

    private fun collapseWindow() {
        CoroutineScope(Dispatchers.Main).launch {
            delay(200)

            animateResizeCentered(binding.cvBackground, binding.cvBackground.width, 0, binding.cvBackground.height, 0) {
                binding.cvappIcon.visibility = View.VISIBLE
                binding.cvappIcon.radius = 20.dpToPx() // 使其变成圆球 (40dp的一半)
                binding.background.visibility = View.GONE
                animateResizeCentered(binding.appIcon, 0, 40.dpToPx().toInt(), 0, 40.dpToPx().toInt()) {
                    keepInScreen()
                }

                isResize = true
            }
        }
    }

    private fun rightResize(ibResize: ImageButton) {
        ibResize.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when(event.action) {
                    MotionEvent.ACTION_DOWN ->
                        dispatchInput(WindowInputEvent.Press(windowId, event.eventTime, RegionId.RESIZE_HANDLE, event.rawX, event.rawY))
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

