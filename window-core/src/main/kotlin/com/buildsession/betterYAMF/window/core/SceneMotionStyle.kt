package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.SceneStyle

internal val SceneStyle.timeScale: Float get() = if (animatorScale <= 0f) 0f else
    AnimTimingPolicy.resolveDuration(configuredDurationMs, animatorScale).toFloat() / AnimTimingPolicy.BASE_DURATION_MS
