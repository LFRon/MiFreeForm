package com.buildsession.betterYAMF.manager.sidebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.buildsession.betterYAMF.common.logging.AppLog
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.graphics.toColorInt
import com.buildsession.betterYAMF.R
import com.buildsession.betterYAMF.common.ui.vibratePhone
import com.buildsession.betterYAMF.databinding.SidebarLayoutBinding
import com.buildsession.betterYAMF.manager.core.settings.SettingsRepository
import com.buildsession.betterYAMF.manager.sidebar.SidebarMenuService.Companion.SERVICE_NOTIF_CHANNEL_ID
import com.buildsession.betterYAMF.manager.sidebar.core.SidebarCommand
import com.buildsession.betterYAMF.manager.sidebar.core.SidebarController
import com.buildsession.betterYAMF.manager.sidebar.core.SidebarInputEvent
import com.buildsession.betterYAMF.common.model.Config as YAMFConfig

/**
 * 侧边栏宿主（UI 层）：只做"输入采集 → 逻辑核心 → 命令执行"。
 * 交互语义全部固化在 [SidebarController]（纯逻辑，JVM 单测覆盖）。
 */
class SidebarService : Service() {

    companion object {
        const val ACTION_START = "com.buildsession.betterYAMF.action.SIDEBAR_START"
        const val ACTION_STOP = "com.buildsession.betterYAMF.action.SIDEBAR_STOP"
        private const val HOLD_TO_DRAG_DELAY_MS = 200L
    }

    lateinit var config: YAMFConfig
    private lateinit var binding: SidebarLayoutBinding
    private lateinit var params : WindowManager.LayoutParams
    private lateinit var windowManager: WindowManager
    private var isServiceRunning = false

    private var orientation = 0

    private var colorString = "#FFFFFF"

    private val controller = SidebarController()
    private val holdHandler = Handler(Looper.getMainLooper())
    private val holdRunnable = Runnable { dispatch(SidebarInputEvent.HoldElapsed) }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopService()
            else -> if (!isServiceRunning) {
                isServiceRunning = true
                initSidebar()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun initSidebar() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_Reyamf)
        val inflater = LayoutInflater.from(themedContext)
        binding = SidebarLayoutBinding.inflate(inflater)

        config = SettingsRepository.load()

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        params.gravity = Gravity.NO_GRAVITY

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(binding.root, params)

        params.x = if (config.sidebarPosition) 100000 else -100000

        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0
        params.y = when (rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                orientation = 0
                config.portraitY
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                orientation = 1
                config.landscapeY
            }
            else -> 0
        }

        colorString = if (config.sidebarTransparency == 100) {
            "#AAAAAA"
        } else {
            val alpha = (config.sidebarTransparency * 255 / 100)
            val alphaHex = String.format("%02X", alpha)
            "#${alphaHex}AAAAAA"
        }

        handleSidebar()

        setServiceState(this, ServiceState.STARTED)
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
//            // Android 14 / API 34 ke atas, wajib serviceType
//            startForeground(
//                SERVICE_NOTIFICATION_ID,
//                createNotification(),
//                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
//            )
//        } else {
//            // Android 13 ke bawah, cukup notification saja
//            startForeground(
//                SERVICE_NOTIFICATION_ID,
//                createNotification()
//            )
//        }
    }

    private fun handleSidebar() {
        binding.clickMask.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dispatch(SidebarInputEvent.Press(event.rawX, event.rawY, params.y))
                    startHoldTimer()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    dispatch(SidebarInputEvent.Move(event.rawX, event.rawY))
                    true
                }

                MotionEvent.ACTION_UP -> {
                    cancelHoldTimer()
                    dispatch(SidebarInputEvent.Up(event.rawX, event.rawY))
                    SettingsRepository.flushSidebarY()
                    v.performClick()
                    true
                }

                else -> false
            }
        }

        binding.rootSidebar.post {
            binding.cvSideBar.setCardBackgroundColor(colorString.toColorInt())
            if (config.sidebarPosition) {
                binding.rootSidebar.scaleX = -1f
            } else {
                binding.rootSidebar.scaleX = 1f
            }
        }

        binding.root.addOnLayoutChangeListener {  _, _, _, _, _, _, _, _, _ ->
            var newOrientation = 0
            val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            val rotation = display?.rotation ?: Surface.ROTATION_0

            when (rotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 -> {
                    params.y = config.portraitY
                    windowManager.updateViewLayout(binding.root, params)
                    newOrientation = 0
                }
                Surface.ROTATION_90, Surface.ROTATION_270 -> {
                    params.y = config.landscapeY
                    windowManager.updateViewLayout(binding.root, params)
                    newOrientation = 1
                }
            }


            if (orientation != newOrientation) {
                orientation = newOrientation
            }
        }
    }

    // —— 输入采集 → 逻辑核心 → 命令执行 ——

    private fun dispatch(event: SidebarInputEvent) {
        controller.onInput(event).forEach(::execute)
    }

    private fun execute(command: SidebarCommand) {
        when (command) {
            is SidebarCommand.MoveToY -> {
                params.y = command.y
                windowManager.updateViewLayout(binding.root, params)
                if (command.persist) {
                    SettingsRepository.scheduleSidebarY(command.y, isLandscape())
                }
            }
            SidebarCommand.Haptic -> vibratePhone(this)
            SidebarCommand.OpenMenu -> SidebarLauncher.startMenu(this)
            SidebarCommand.StopSidebar -> stopService()
        }
    }

    private fun startHoldTimer() {
        holdHandler.removeCallbacks(holdRunnable)
        holdHandler.postDelayed(holdRunnable, HOLD_TO_DRAG_DELAY_MS)
    }

    private fun cancelHoldTimer() {
        holdHandler.removeCallbacks(holdRunnable)
    }

    private fun isLandscape(): Boolean {
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    }

    private fun createNotification(): Notification {
        val channelId = SERVICE_NOTIF_CHANNEL_ID
        val channel = NotificationChannel(
            channelId,
            "reYAMF Sidebar Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sidebar Service Running")
            .setContentText("reYAMF Sidebar Service")
            .build()
    }

    private fun stopService() {
        SettingsRepository.flushSidebarY()
        cancelHoldTimer()
        try {
            windowManager.removeView(binding.root)
        } catch (e: Exception) {
            AppLog.debug("reYAMF") { "Sidebar killed" }
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
        setServiceState(this, ServiceState.STOPPED)
    }
}
