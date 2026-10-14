package com.buildsession.betterYAMF.xposed.hook.freeform

object FreeformResolver {
    fun firstLoadable(loader: ClassLoader, candidates: List<String>): String? =
        candidates.firstOrNull { candidate ->
            runCatching { loader.loadClass(candidate) }.isSuccess
        }
}
