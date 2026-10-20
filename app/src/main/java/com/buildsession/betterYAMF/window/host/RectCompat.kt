package com.buildsession.betterYAMF.window.host

import android.graphics.Rect
import com.buildsession.betterYAMF.window.api.WindowRect

/** 宿主侧矩形转换：公共 API 矩形 ↔ Android Rect（仅宿主使用）。 */

internal fun WindowRect.toAndroidRect(): Rect = Rect(left, top, right, bottom)

internal fun Rect.toWindowRect(): WindowRect = WindowRect(left, top, right, bottom)
