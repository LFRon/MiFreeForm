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
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.services.YAMFManager.config
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.RunMainThreadQueue
import com.buildsession.betterYAMF.xposed.utils.TipUtil
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
        private val shellRegistry = WindowShellRegistry()
        private val windowIdGen = java.util.concurrent.atomic.AtomicInteger(1)
    }

    val windowId = windowIdGen.getAndIncrement()
    lateinit var binding: WindowAppBinding
    private lateinit var shell: WindowShellController

    var currentTaskId = -1
    private var isDestroyed = false

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

        val displayManager = context.getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0
        
        val dm = context.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val windowWidth = config.defaultWindowWidth.dpToPx().toInt()
        val windowHeight = config.defaultWindowHeight.dpToPx().toInt()

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

        binding.cvParent.post(::updateSmoothBounds)

        shell = shellRegistry.register(
            WindowRegistration(windowId, Resources.getSystem().displayMetrics.density)
        )
        updateShellScreenMetrics()
        
        context.registerReceiver(broadcastReceiver, IntentFilter(ACTION_RESET_ALL_WINDOW), Context.RECEIVER_EXPORTED)
        val width = config.defaultWindowWidth.dpToPx().toInt()
        val height = config.defaultWindowHeight.dpToPx().toInt()
        binding.vSizePreviewer.updateLayoutParams {
            this.width = width
            this.height = height
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

    fun onDestroy() {
        if (isDestroyed) return
        isDestroyed = true
        
        runCatching { context.unregisterReceiver(broadcastReceiver) }
        
        YAMFManager.removeWindow(windowId)
        YAMFManager.pendingSmoothFreeformApps.entries.removeAll { it.value === this }
        if (::shell.isInitialized) {
            shellRegistry.unregister(shell)
        }
        YAMFManager.smoothFreeformTasks.remove(currentTaskId)
        YAMFManager.smoothFreeformBounds.remove(currentTaskId)
        
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
        YAMFManager.moveToTop(windowId)
    }

    private fun dispatchInput(event: WindowInputEvent) {
        shell.onInput(event).forEach(::execute)
    }

    private fun execute(command: WindowCommand) {
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
            is WindowCommand.EnterMini -> execEnterMini()
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
        val maxX = (screenWidth - windowWidth).toFloat()
        val minY = 0f
        val maxY = (screenHeight - windowHeight).toFloat()

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
                    Instances.activityManager.moveTaskToFront(currentTaskId, 0)
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
                    Instances.activityManager.moveTaskToFront(currentTaskId, 0)
                }.onSuccess {
                    onDestroy()
                }.onFailure {
                    execResetWindowScale()
                }
            }
        }
    }

    private fun updateSmoothBounds() {
        if (currentTaskId == -1) return
        
        val location = IntArray(2)
        binding.cvParent.getLocationOnScreen(location)
        val rect = android.graphics.Rect(
            location[0], 
            location[1], 
            location[0] + binding.cvParent.width, 
            location[1] + binding.cvParent.height
        )
        
        if (YAMFManager.smoothFreeformBounds[currentTaskId] != rect) {
            YAMFManager.updateSmoothBounds(currentTaskId, rect)
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
        if (isHomeTask(taskInfo)) {
            onDestroy()
            return
        }

        if (taskInfo.taskId == currentTaskId) {
            updateTask(taskInfo)
        }
    }

    fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        if (!taskInfo.isVisible) {
            return
        }

        if (isHomeTask(taskInfo)) {
            onDestroy()
            return
        }

        if (taskInfo.taskId == currentTaskId) {
            updateTask(taskInfo)
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
            val maxX = screenWidth - windowWidth
            val maxY = screenHeight - windowHeight

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
                        }
                    }
                    start()
                }
            } else {
                params.x = targetX
                params.y = targetY
                runCatching {
                    Instances.windowManager.updateViewLayout(binding.root, params)
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

    private fun execEnterMini() {
        isResize = false

        binding.rootClickMask.visibility = View.VISIBLE
        binding.rlBarControllerBottom.visibility = View.GONE
        binding.rlBarControllerSide.visibility = View.GONE

        binding.cvBackground.updateLayoutParams {
            width = originalWidth/2
            height = originalHeight/2
        }
        keepInScreen()

        binding.ibRightResize.visibility = View.GONE
    }

    private fun execExitMini() {
        isResize = true
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
        binding.rootClickMask.visibility = View.VISIBLE
        binding.rlBarControllerBottom.visibility = View.GONE
        binding.rlBarControllerSide.visibility = View.GONE
        collapseWindow()
    }

    private fun execExitCollapsed() {
        isResize = false
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
                    MotionEvent.ACTION_MOVE ->
                        dispatchInput(WindowInputEvent.Move(windowId, event.eventTime, RegionId.RESIZE_HANDLE, event.rawX, event.rawY))
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

