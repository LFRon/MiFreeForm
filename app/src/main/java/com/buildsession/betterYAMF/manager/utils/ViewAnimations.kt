package com.buildsession.betterYAMF.manager.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import com.buildsession.betterYAMF.common.gson
import com.buildsession.betterYAMF.manager.services.YAMFManagerProxy
import com.buildsession.betterYAMF.common.model.Config as YAMFConfig

/**
 * app（UI）侧动画工具。
 *
 * 分层边界：只依赖 UI↔模块的 RPC 代理（[YAMFManagerProxy]）读取动画速度，
 * 读取失败回退默认配置——不触碰模块进程单例（Xposed 侧对象在 App 进程不可用）。
 */

private const val DEFAULT_ANIMATION_SPEED = 5100f

private fun resolveAnimationConfig(): YAMFConfig =
    runCatching {
        gson.fromJson(YAMFManagerProxy.configJson, YAMFConfig::class.java)
    }.getOrNull() ?: YAMFConfig()

fun animateResize(
    view: View,
    startWidth: Int,
    endWidth: Int,
    startHeight: Int,
    endHeight: Int,
    context: Context,
    onUpdate: ((width: Int, height: Int) -> Unit)? = null,
    onEnd: (() -> Unit)? = null
) {
    val config = resolveAnimationConfig()
    val adjustedDuration = (if (config.animationSpeed < DEFAULT_ANIMATION_SPEED) config.animationSpeed else 300f).toLong()

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
    onEnd: (() -> Unit)? = null
) {
    val scale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
    } catch (e: Settings.SettingNotFoundException) {
        1.0f
    }

    val config = resolveAnimationConfig()
    val adjustedDuration = (if (config.animationSpeed < DEFAULT_ANIMATION_SPEED) config.animationSpeed else 300 * scale).toLong()

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
