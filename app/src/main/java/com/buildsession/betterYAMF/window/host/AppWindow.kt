package com.buildsession.betterYAMF.window.host

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityTaskManager
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
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.IRotationWatcher
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManagerHidden
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ImageButton
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.flingAnimationOf
import androidx.wear.widget.RoundedDrawable
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.google.android.material.color.MaterialColors
import com.buildsession.betterYAMF.common.getAttr
import com.buildsession.betterYAMF.common.onException
import com.buildsession.betterYAMF.common.runMain
import com.buildsession.betterYAMF.databinding.LeftBackGestureOverlayBinding
import com.buildsession.betterYAMF.databinding.RightBackGestureOverlayBinding
import com.buildsession.betterYAMF.databinding.WindowAppBinding
import kotlinx.coroutines.withContext
import com.buildsession.betterYAMF.window.core.CloseOrigin
import com.buildsession.betterYAMF.window.core.FullscreenOrigin
import com.buildsession.betterYAMF.window.core.RegionId
import com.buildsession.betterYAMF.window.core.ShellMode
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable


@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class AppWindow(
    val context: Context,
    private val flags: Int,
    private val onVirtualDisplayCreated: (AppWindow, Int) -> Unit
) :
    TextureView.SurfaceTextureListener, SurfaceHolder.Callback {
    companion object {
        const val TAG = "reYAMF_AppWindow"
        const val ACTION_RESET_ALL_WINDOW = "com.buildsession.betterYAMF.ui.window.action.ACTION_RESET_ALL_WINDOW"
        private val shellRegistry = WindowShellRegistry()
    }

    lateinit var binding: WindowAppBinding
    lateinit var bindingLeftBackGesture: LeftBackGestureOverlayBinding
    lateinit var bindingRightBackGesture: RightBackGestureOverlayBinding
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var shell: WindowShellController
    
    var currentTaskId = -1
    private var isDestroyed = false

    private val rotationWatcher = RotationWatcher()
    private val surfaceOnTouchListener = SurfaceOnTouchListener()
    private val surfaceOnGenericMotionListener = SurfaceOnGenericMotionListener()
    var displayId = -1
    var rotateLock = false
    private var halfWidth = 0
    private var halfHeight = 0
    lateinit var surfaceView: View
    private var newDpi = calculateDpi(
        config.defaultWindowWidth, config.defaultWindowHeight,
        calculateScreenInches(config.defaultWindowWidth, config.defaultWindowHeight)
    ) - config.reduceDPI
    private var originalWidth: Int = 0
    private var originalHeight: Int = 0
    private var isResize: Boolean = true
    private var orientation = 0
    private var params = WindowManager.LayoutParams()
    private var paramsBg = WindowManager.LayoutParams()
    private var backGestureJob: Job? = null
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
                surfaceView.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
            }
        }
    }

    init {
        runCatching {
            binding = WindowAppBinding.inflate(LayoutInflater.from(context))
            bindingLeftBackGesture = LeftBackGestureOverlayBinding.inflate(LayoutInflater.from(context))
            bindingRightBackGesture = RightBackGestureOverlayBinding.inflate(LayoutInflater.from(context))
        }.onException { e ->
            Log.e(TAG, "Failed to create new window, did you reboot?", e)
            TipUtil.showToast("Failed to create new window, did you reboot?")
        }.onSuccess {
            doInit()
        }
    }

    private fun doInit() {
        when(config.surfaceView) {
            0 -> {
                surfaceView = binding.viewSurface
                binding.viewTexture.visibility = View.GONE
            }
            1 -> {
                surfaceView = binding.viewTexture
                binding.viewSurface.visibility = View.GONE
            }
        }

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

        paramsBg = WindowManager.LayoutParams(
            20.dpToPx().toInt(),
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        paramsBg.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        bindingLeftBackGesture.root.let {
            paramsBg.gravity = Gravity.START or Gravity.TOP
            Instances.windowManager.addView(bindingLeftBackGesture.root, paramsBg)
        }

        bindingRightBackGesture.root.let {
            val paramsBgR = WindowManager.LayoutParams(
                20.dpToPx().toInt(),
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            paramsBgR.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            paramsBgR.gravity = Gravity.END or Gravity.TOP
            Instances.windowManager.addView(bindingRightBackGesture.root, paramsBgR)
        }

        binding.root.let { layout ->
            Instances.windowManager.addView(layout, params)
        }

        binding.rootClickMask.setOnTouchListener { _, event ->
            translateMoveGesture(RegionId.BODY_MASK, event)
            true
        }

        binding.cvBarClickMask.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(displayId, System.currentTimeMillis(), RegionId.BAR_BOTTOM))
        }

        binding.cvBarSideClickMask.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(displayId, System.currentTimeMillis(), RegionId.BAR_SIDE))
        }

        binding.ibSuper.setOnTouchListener { _, event ->
            translateMoveGesture(RegionId.HANDLE, event)
            false
        }

        binding.ibSuper.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(displayId, System.currentTimeMillis(), RegionId.HANDLE))
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

        surfaceView.setOnTouchListener(surfaceOnTouchListener)
        surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)

        binding.ibClose.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(displayId, System.currentTimeMillis(), RegionId.BUTTON_CLOSE))
        }

        binding.ibFullscreen.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(displayId, System.currentTimeMillis(), RegionId.BUTTON_FULLSCREEN))
        }

        binding.ibMinimize.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(displayId, System.currentTimeMillis(), RegionId.BUTTON_MINIMIZE))
        }

        binding.ibCollapse.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(displayId, System.currentTimeMillis(), RegionId.BUTTON_COLLAPSE))
        }

        binding.ibSuperClose.setOnClickListener {
            dispatchInput(WindowInputEvent.Tap(displayId, System.currentTimeMillis(), RegionId.SUPER_CLOSE))
        }

        if (config.windowMode == 0) { // Virtual Display
            virtualDisplay = Instances.displayManager.createVirtualDisplay(
                "yamf${System.currentTimeMillis()}", config.defaultWindowWidth, config.defaultWindowHeight, newDpi-config.reduceDPI, null, flags
            )
            displayId = virtualDisplay.display.displayId
            (Instances.windowManager as WindowManagerHidden).setDisplayImePolicy(displayId, if (config.showImeInWindow) WindowManagerHidden.DISPLAY_IME_POLICY_LOCAL else WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY)
            
            (surfaceView as? TextureView)?.surfaceTextureListener = this
            (surfaceView as? SurfaceView)?.holder?.addCallback(this)
            var failCount = 0
            fun watchRotation() {
                runCatching {
                    Instances.iWindowManager.watchRotation(rotationWatcher, displayId)
                }.onFailure {
                    failCount++
                    // Log.d(TAG, "watchRotation: fail $failCount")
                    watchRotation()
                }
            }
            watchRotation()
        } else { // Smooth Freeform
            displayId = 0 // Main display
            surfaceView.visibility = View.GONE // Hide the surface view, task renders directly
            binding.cvParent.post(::updateSmoothBounds)
        }

        shell = shellRegistry.register(
            WindowRegistration(displayId, Resources.getSystem().displayMetrics.density)
        )
        updateShellScreenMetrics()
        
        context.registerReceiver(broadcastReceiver, IntentFilter(ACTION_RESET_ALL_WINDOW), Context.RECEIVER_EXPORTED)
        val width = config.defaultWindowWidth.dpToPx().toInt()
        val height = config.defaultWindowHeight.dpToPx().toInt()
        surfaceView.updateLayoutParams {
            this.width = width
            this.height = height
        }
        binding.vSizePreviewer.updateLayoutParams {
            this.width = width
            this.height = height
        }
        onVirtualDisplayCreated(this, displayId)

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

        //TODO: Find me a better alternative for less resource usage instead of polling
        backGestureJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                if (config.windowMode == 1) { // In smooth mode, we don't handle back gestures here for now
                    delay(1000)
                    continue
                }
                val mode = shell.snapshot().mode
                if (mode != ShellMode.NORMAL) {
                    withContext(Dispatchers.Main) {
                        bindingLeftBackGesture.root.visibility = View.GONE
                        bindingRightBackGesture.root.visibility = View.GONE
                    }
                } else if (displayId == YAMFManager.currentDisplayId) {
                    withContext(Dispatchers.Main) {
                        bindingLeftBackGesture.root.visibility = View.VISIBLE
                        bindingRightBackGesture.root.visibility = View.VISIBLE
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        bindingLeftBackGesture.root.visibility = View.GONE
                        bindingRightBackGesture.root.visibility = View.GONE
                    }
                }

                if (mode == ShellMode.NORMAL) {
                    withContext(Dispatchers.Main) {
                        if (orientation == 0) {
                            binding.rlBarControllerBottom.visibility = View.VISIBLE
                        } else {
                            binding.rlBarControllerSide.visibility = View.VISIBLE
                        }
                    }
                }
                delay(500)
            }
        }
    }

    fun onDestroy() {
        if (isDestroyed) return
        isDestroyed = true
        
        runCatching { context.unregisterReceiver(broadcastReceiver) }
        runCatching { Instances.iWindowManager.removeRotationWatcher(rotationWatcher) }
        
        YAMFManager.removeWindow(displayId)
        if (::shell.isInitialized) {
            shellRegistry.unregister(shell)
        }
        if (config.windowMode == 0) {
            runCatching { virtualDisplay.release() }
        } else {
            // In smooth mode, if the task is still alive, we should move it back to full screen or close it
            // For now, let's just stop tracking it
            YAMFManager.smoothFreeformTasks.remove(currentTaskId)
            YAMFManager.smoothFreeformBounds.remove(currentTaskId)
        }
        
        runMain {
            if (binding.root.isAttachedToWindow) {
                runCatching { Instances.windowManager.removeView(binding.root) }
            }
            if (bindingLeftBackGesture.root.isAttachedToWindow) {
                runCatching { Instances.windowManager.removeView(bindingLeftBackGesture.root) }
            }
            if (bindingRightBackGesture.root.isAttachedToWindow) {
                runCatching { Instances.windowManager.removeView(bindingRightBackGesture.root) }
            }
            cornerDropZoneView?.let {
                if (it.isAttachedToWindow) {
                    runCatching { Instances.windowManager.removeView(it) }
                }
                cornerDropZoneView = null
            }
        }
    }

    private fun getTopRootTask(): ActivityTaskManager.RootTaskInfo? {
        Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
            if (task.visible)
                return task
        }
        return null
    }

    private fun moveToTop() {
        if (bindingLeftBackGesture.root.isAttachedToWindow) {
            Instances.windowManager.removeView(bindingLeftBackGesture.root)
        }
        Instances.windowManager.addView(bindingLeftBackGesture.root, bindingLeftBackGesture.root.layoutParams)
        
        if (bindingRightBackGesture.root.isAttachedToWindow) {
            Instances.windowManager.removeView(bindingRightBackGesture.root)
        }
        Instances.windowManager.addView(bindingRightBackGesture.root, bindingRightBackGesture.root.layoutParams)

        if (binding.root.isAttachedToWindow) {
            Instances.windowManager.removeView(binding.root)
        }
        Instances.windowManager.addView(binding.root, binding.root.layoutParams)
        YAMFManager.moveToTop(displayId)
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
                dispatchInput(WindowInputEvent.Press(displayId, event.eventTime, region, event.rawX, event.rawY))
            MotionEvent.ACTION_UP ->
                dispatchInput(WindowInputEvent.Up(displayId, event.eventTime, region, event.rawX, event.rawY))
            MotionEvent.ACTION_CANCEL ->
                dispatchInput(WindowInputEvent.Cancel(displayId, event.eventTime, region, event.rawX, event.rawY))
        }
        if (config.windowMode == 1) {
            updateSmoothBounds()
        }
    }

    private fun translateBarGesture(region: RegionId, event: MotionEvent) {
        if (config.windowMode == 1) {
            updateSmoothBounds()
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN ->
                dispatchInput(WindowInputEvent.Press(displayId, event.eventTime, region, event.rawX, event.rawY))
            MotionEvent.ACTION_MOVE ->
                dispatchInput(WindowInputEvent.Move(displayId, event.eventTime, region, event.rawX, event.rawY))
            MotionEvent.ACTION_UP ->
                dispatchInput(WindowInputEvent.Up(displayId, event.eventTime, region, event.rawX, event.rawY))
            MotionEvent.ACTION_CANCEL ->
                dispatchInput(WindowInputEvent.Cancel(displayId, event.eventTime, region, event.rawX, event.rawY))
        }
    }

    private fun execMoveBy(command: WindowCommand.MoveBy) {
        val params = binding.root.layoutParams as WindowManager.LayoutParams
        params.x = (dragAnchorX + command.dx).toInt()
        params.y = (dragAnchorY + command.dy).toInt()
        Instances.windowManager.updateViewLayout(binding.root, params)
        if (config.windowMode == 1) updateSmoothBounds()
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
                    if (config.windowMode == 1) updateSmoothBounds()
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
                    if (config.windowMode == 1) updateSmoothBounds()
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
                backGestureJob?.cancel()
                backGestureJob = null

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
        when (origin) {
            FullscreenOrigin.BUTTON -> {
                getTopRootTask()?.runCatching {
                    Instances.activityTaskManager.moveRootTaskToDisplay(taskId, 0)
                }?.onFailure { t ->
                    if (t is Error) throw t
                    TipUtil.showToast("${t.message}")
                }?.onSuccess {
                    execRequestClose(CloseOrigin.BUTTON_X)
                }
            }
            FullscreenOrigin.DRAG_DOWN_BAR -> {
                getTopRootTask()?.runCatching {
                    Instances.activityTaskManager.moveRootTaskToDisplay(taskId, 0)
                    Instances.activityManager.moveTaskToFront(taskId, 0)
                }?.onSuccess {
                    onDestroy()
                }?.onFailure {
                    execResetWindowScale()
                }
            }
        }
    }

    private fun updateSmoothBounds() {
        if (config.windowMode != 1 || currentTaskId == -1) return
        
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
        val taskDisplayId = runCatching { taskInfo.getObject("displayId") as Int }.getOrDefault(-1)
        if (taskDisplayId == displayId) {
            // 检测是否回到了桌面 (Home Activity)，如果是则自动关闭小窗
            // ACTIVITY_TYPE_HOME = 2
            val activityType = runCatching {
                val config = taskInfo.getObject("configuration")
                val windowConfig = config.getObject("windowConfiguration")
                windowConfig.invokeMethod("getActivityType") as Int
            }.getOrElse {
                // 兜底逻辑：通过 topActivity 的包名判断是否为桌面
                val topActivity = taskInfo.topActivity
                if (topActivity != null) {
                    val pkg = topActivity.packageName
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    val defaultLauncher = resolveInfo?.activityInfo?.packageName
                    
                    // 1. 检查是否为默认桌面
                    // 2. 检查常见 Launcher 包名
                    // 3. 模糊匹配包含 launcher 的包名 (排除某些系统组件)
                    if (pkg == defaultLauncher || 
                        pkg == "com.android.launcher3" || 
                        pkg == "com.google.android.apps.nexuslauncher" ||
                        (pkg.contains("launcher", ignoreCase = true) && !pkg.contains("service", ignoreCase = true))) 2 else 0
                } else 0
            }

            if (activityType == 2) {
                onDestroy()
                return
            }

            updateTask(taskInfo)
        }
    }

    fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        val taskDisplayId = runCatching { taskInfo.getObject("displayId") as Int }.getOrDefault(-1)
        if (taskDisplayId == displayId) {
            if(!taskInfo.isVisible){
                return
            }
            
            // 同样增加桌面检测，防止某些情况下 onTaskMovedToFront 未触发
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

            if (activityType == 2) {
                onDestroy()
                return
            }
            
            updateTask(taskInfo)
        }
    }

    inner class RotationWatcher : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            runMain {
                if (rotateLock.not())
                    rotate(rotation)
            }
        }
    }

    fun rotate(rotation: Int) {
        val isLandscape = rotation == 1 || rotation == 3
        val newOrientation = if (isLandscape) 1 else 0
        
        if (orientation != newOrientation) {
            orientation = newOrientation
            
            // Swap surface view dimensions if transitioning between portrait and landscape
            val surfaceWidth = surfaceView.width
            val surfaceHeight = surfaceView.height
            binding.vSizePreviewer.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }
            surfaceView.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }

            // Update bar controllers visibility
            if (orientation == 0) { // Portrait
                binding.rlBarControllerSide.isVisible = false
                binding.rlBarControllerBottom.isVisible = true
            } else { // Landscape
                binding.rlBarControllerSide.isVisible = true
                binding.rlBarControllerBottom.isVisible = false
            }
            
            // After rotation, make sure the window is still in screen
            keepInScreen(animate = false)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (shell.snapshot().mode == ShellMode.NORMAL) {
            newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
            virtualDisplay.resize(width, height, newDpi)
            surface.setDefaultBufferSize(width, height)
            halfWidth = width % 2
            halfHeight = height % 2
        } else {
            newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
            virtualDisplay.resize(width * 2 + halfWidth, height * 2 + halfHeight, newDpi)
            surface.setDefaultBufferSize(width * 2 + halfWidth, height * 2 + halfHeight)
        }
        virtualDisplay.surface = Surface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (isResize) {
            if (shell.snapshot().mode != ShellMode.MINI) {
                newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
                virtualDisplay.resize(width, height, newDpi)
                surface.setDefaultBufferSize(width, height)
                halfWidth = width % 2
                halfHeight = height % 2
            } else {
                newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
                virtualDisplay.resize(width * 2 + halfWidth, height * 2 + halfHeight, newDpi)
                surface.setDefaultBufferSize(width * 2 + halfWidth, height * 2 + halfHeight)
            }
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {

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

        if (config.surfaceView == 1) {
            binding.cvBackground.updateLayoutParams {
                width = originalWidth/2
                height = originalHeight/2
            }
            keepInScreen()
        } else {
            animateResizeCentered(
                binding.cvBackground,
                originalWidth, originalWidth/2,
                originalHeight, originalHeight/2
            ){
                isResize = true
                bindingLeftBackGesture.root.visibility = View.GONE
                bindingRightBackGesture.root.visibility = View.GONE
                keepInScreen()
            }
        }

        binding.ibRightResize.visibility = View.GONE
        surfaceView.setOnTouchListener(null)
        surfaceView.setOnGenericMotionListener(null)
    }

    private fun execExitMini() {
        isResize = true
        binding.rootClickMask.visibility = View.GONE

        if (surfaceView is SurfaceView) {
            binding.cvBackground.updateLayoutParams {
                width = originalWidth
                height = originalHeight
            }
            setBackgroundWrapContent()
            setParrentWrapContent()
            keepInScreen()
            if (orientation == 0) {
                binding.rlBarControllerBottom.visibility = View.VISIBLE
                binding.rlBarControllerSide.visibility = View.GONE
            } else {
                binding.rlBarControllerSide.visibility = View.VISIBLE
                binding.rlBarControllerBottom.visibility = View.GONE
            }
        } else {
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
                bindingLeftBackGesture.root.visibility = View.VISIBLE
                bindingRightBackGesture.root.visibility = View.VISIBLE
                keepInScreen()
            }
        }

        binding.ibRightResize.visibility = View.VISIBLE
        if (orientation == 0) {
            binding.rlBarControllerBottom.visibility = View.VISIBLE
        } else {
            binding.rlBarControllerSide.visibility = View.VISIBLE
        }
        surfaceView.visibility = View.VISIBLE
        surfaceView.setOnTouchListener(surfaceOnTouchListener)
        surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)
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
        bindingLeftBackGesture.root.visibility = View.GONE
        bindingRightBackGesture.root.visibility = View.GONE
    }

    private fun execExitCollapsed() {
        isResize = false
        binding.rootClickMask.visibility = View.GONE
        expandWindow()
        bindingLeftBackGesture.root.visibility = View.VISIBLE
        bindingRightBackGesture.root.visibility = View.VISIBLE
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

    private fun calculateScreenInches(width: Int, height: Int): Float {
        val x = (width / context.resources.displayMetrics.xdpi).pow(2)
        val y = (height / context.resources.displayMetrics.ydpi).pow(2)

        return sqrt(x + y)
    }

    private fun calculateDpi(width: Int, height: Int, screenSizeInInches: Float): Int {
        val widthSqr = width.toFloat().pow(2)
        val heightSqr = height.toFloat().pow(2)
        val diagonalPixels = sqrt(widthSqr + heightSqr)

        return floor(diagonalPixels / screenSizeInInches).toInt()
    }

    private fun rightResize(ibResize: ImageButton) {
        ibResize.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when(event.action) {
                    MotionEvent.ACTION_DOWN ->
                        dispatchInput(WindowInputEvent.Press(displayId, event.eventTime, RegionId.RESIZE_HANDLE, event.rawX, event.rawY))
                    MotionEvent.ACTION_MOVE ->
                        dispatchInput(WindowInputEvent.Move(displayId, event.eventTime, RegionId.RESIZE_HANDLE, event.rawX, event.rawY))
                    MotionEvent.ACTION_UP -> {
                        dispatchInput(WindowInputEvent.Up(displayId, event.eventTime, RegionId.RESIZE_HANDLE, event.rawX, event.rawY))
                        if (config.windowMode == 1) {
                            updateSmoothBounds()
                        }
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
        binding.vSizePreviewer.post {
            surfaceView.updateLayoutParams {
                width = binding.vSizePreviewer.width
                height = binding.vSizePreviewer.height
            }
        }

        binding.vSizePreviewer.visibility = View.GONE
        binding.cvParent.strokeWidth = 2.dpToPx().toInt()
        keepInScreen()
    }

    fun forwardMotionEvent(event: MotionEvent) {
        if (!shell.snapshot().superMenuVisible) {
            val newEvent = MotionEvent.obtain(event)
            newEvent.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            Instances.inputManager.injectInputEvent(newEvent, 0)
            newEvent.recycle()
        }
    }

    inner class SurfaceOnTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE
            forwardMotionEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    dispatchInput(WindowInputEvent.Press(displayId, event.eventTime, RegionId.CONTENT, event.rawX, event.rawY))
                MotionEvent.ACTION_UP ->
                    dispatchInput(WindowInputEvent.Up(displayId, event.eventTime, RegionId.CONTENT, event.rawX, event.rawY))
                MotionEvent.ACTION_CANCEL ->
                    dispatchInput(WindowInputEvent.Cancel(displayId, event.eventTime, RegionId.CONTENT, event.rawX, event.rawY))
            }
            if (config.windowMode == 1) {
                updateSmoothBounds()
            }
            return true
        }
    }

    inner class SurfaceOnGenericMotionListener : View.OnGenericMotionListener {
        override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE
            forwardMotionEvent(event)
            return true
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        virtualDisplay.surface = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        newDpi = calculateDpi(width, height, calculateScreenInches(width, height )) - config.reduceDPI
        virtualDisplay.resize(width, height, newDpi)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        virtualDisplay.surface = null
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
                    displayId,
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
            dispatchInput(WindowInputEvent.Fling(displayId, e2.eventTime, velocityX, velocityY))
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            dispatchInput(
                WindowInputEvent.TapConfirmed(
                    displayId,
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

