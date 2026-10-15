package com.buildsession.betterYAMF.common.ui

import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation

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
