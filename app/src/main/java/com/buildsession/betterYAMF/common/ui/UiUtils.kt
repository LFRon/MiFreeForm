package com.buildsession.betterYAMF.common.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Resources
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue

fun Number.dpToPx() =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), Resources.getSystem().displayMetrics
    )

val ActivityInfo.componentName: ComponentName
    get() = ComponentName(packageName, name)

fun vibratePhone(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    vibrator.vibrate(VibrationEffect.createOneShot(5, VibrationEffect.DEFAULT_AMPLITUDE))
}
