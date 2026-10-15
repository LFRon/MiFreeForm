package com.buildsession.betterYAMF.xposed.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.ActivityTaskManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManagerHidden
import android.content.res.Resources
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.common.gson
import com.buildsession.betterYAMF.manager.services.YAMFManagerProxy
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import de.robv.android.xposed.XposedBridge
import com.buildsession.betterYAMF.common.model.Config as YAMFConfig

fun log(tag: String, message: String) {
    // 非警告/错误日志：仅 DEBUG 构建输出，Release 静默（避免 logd/IPC 徒增功耗）
    if (!BuildConfig.DEBUG) return
    XposedBridge.log("[$tag] $message")
}

fun log(tag: String, message: String, t: Throwable) {
    // 错误：Release 保留——走 logcat ERROR 级（轻量，不写 Xposed 日志文件）；DEBUG 完整输出
    if (BuildConfig.DEBUG) {
        XposedBridge.log("[$tag] $message")
        XposedBridge.log(t)
    } else {
        android.util.Log.e(tag, message, t)
    }
}

fun Number.dpToPx() =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), Resources.getSystem().displayMetrics
    )

val emptyContextParams = ContextParams.Builder().build()

fun Context.createContext() = createContext(emptyContextParams)

fun getTopRootTask(displayId: Int): ActivityTaskManager.RootTaskInfo? {
    Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
        if (task.visible)
            return task
    }
    return null
}

fun Context.registerReceiver(action: String, onReceive: BroadcastReceiver.(Context, Intent) -> Unit) {
    registerReceiver(object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onReceive(this, context, intent)
        }
    }, android.content.IntentFilter(action), Context.RECEIVER_EXPORTED)
}


val ActivityInfo.componentName: ComponentName
    get() = ComponentName(packageName, name)

fun IPackageManagerHidden.getActivityInfoCompat(className: ComponentName, flags: Int, userId: Int): ActivityInfo =
    getActivityInfo(className, flags.toLong(), userId)

fun vibratePhone(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    vibrator.vibrate(VibrationEffect.createOneShot(5, VibrationEffect.DEFAULT_AMPLITUDE))
}

fun animateResize(
    view: View,
    startWidth: Int,
    endWidth: Int,
    startHeight: Int,
    endHeight: Int,
    context: Context,
    baseDuration: Long = 300L,
    onUpdate: ((width: Int, height: Int) -> Unit)? = null,
    onEnd: (() -> Unit)? = null
) {
    val scale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
    } catch (e: Settings.SettingNotFoundException) {
        1.0f // fallback to normal scale if not found
    }

    val config = try {
        gson.fromJson(YAMFManagerProxy.configJson, YAMFConfig::class.java)
    } catch (e: Exception) {
        gson.fromJson(YAMFManager.configJson, YAMFConfig::class.java)
    }
    val adjustedDuration = (if (config.animationSpeed < 5100) config.animationSpeed else 300).toLong()

    val widthAnimator = ValueAnimator.ofInt(startWidth, endWidth).apply {
        addUpdateListener { animator ->
            val value = animator.animatedValue as Int
            val params = view.layoutParams
            params.width = value
            view.layoutParams = params
            onUpdate?.invoke(value, view.layoutParams.height)
        }
    }

    val heightAnimator = ValueAnimator.ofInt(startHeight, endHeight).apply {
        addUpdateListener { animator ->
            val value = animator.animatedValue as Int
            val params = view.layoutParams
            params.height = value
            view.layoutParams = params
            onUpdate?.invoke(view.layoutParams.width, value)
        }
    }

    AnimatorSet().apply {
        playTogether(widthAnimator, heightAnimator)
        duration = adjustedDuration
        interpolator = AccelerateDecelerateInterpolator()
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onEnd?.invoke()
            }
        })
        start()
    }
}

fun animateScaleThenResize(
    view: View,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    pivotX: Float,
    pivotY: Float,
    endWidth: Int,
    endHeight: Int,
    context: Context,
    baseDuration: Long = 300L,
    onEnd: (() -> Unit)? = null
) {
    val scale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
    } catch (e: Settings.SettingNotFoundException) {
        1.0f
    }

    val config = try {
        gson.fromJson(YAMFManagerProxy.configJson, YAMFConfig::class.java)
    } catch (e: Exception) {
        gson.fromJson(YAMFManager.configJson, YAMFConfig::class.java)
    }
    val adjustedDuration = (if (config.animationSpeed < 5100) config.animationSpeed else 300 * scale).toLong()

    val scaleAnimation = ScaleAnimation(
        startX, endX,
        startY, endY,
        Animation.RELATIVE_TO_SELF, pivotX,
        Animation.RELATIVE_TO_SELF, pivotY
    ).apply {
        duration = adjustedDuration
        fillAfter = false
        interpolator = AccelerateDecelerateInterpolator()
        setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}

            override fun onAnimationEnd(animation: Animation?) {
                val params = view.layoutParams
                params.width = endWidth
                params.height = endHeight
                view.layoutParams = params
                onEnd?.invoke()
            }

            override fun onAnimationRepeat(animation: Animation?) {}
        })
    }

    view.startAnimation(scaleAnimation)
}


fun animateAlpha(view: View, startAlpha: Float, endAlpha: Float, onEnd: (() -> Unit)? = null) {
    if (endAlpha == 1F) view.visibility = View.VISIBLE
    val animation1 = AlphaAnimation(startAlpha, endAlpha)
    animation1.duration = 300

    animation1.setAnimationListener(object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation?) {}

        override fun onAnimationEnd(animation: Animation?) {
            onEnd?.invoke()
        }

        override fun onAnimationRepeat(animation: Animation?) {}
    })

    view.startAnimation(animation1)
    if (endAlpha == 1F) view.visibility = View.VISIBLE else view.visibility = View.GONE
}
