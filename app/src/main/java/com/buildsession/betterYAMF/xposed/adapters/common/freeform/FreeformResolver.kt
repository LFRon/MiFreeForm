package com.buildsession.betterYAMF.xposed.adapters.common.freeform

object FreeformResolver {
    fun firstLoadable(loader: ClassLoader, candidates: List<String>): String? =
        candidates.firstOrNull { candidate ->
            runCatching { loader.loadClass(candidate) }.isSuccess
        }
}
