package com.buildsession.betterYAMF.manager.sidebar.core

/**
 * 侧边栏应用列表合并（纯逻辑）：保留"存在于应用目录中的收藏条目"，收藏顺序不变。
 *
 * 现状语义：以组件（componentName 字符串键）判等，userId 不参与；结果保留收藏条目本身。
 */
object SidebarAppsMerger {

    fun <T, C, K> intersectBy(
        favorites: List<T>,
        catalog: List<C>,
        keyOfFavorite: (T) -> K,
        keyOfCatalog: (C) -> K
    ): List<T> {
        if (favorites.isEmpty() || catalog.isEmpty()) return emptyList()
        val catalogKeys = catalog.mapTo(HashSet(), keyOfCatalog)
        return favorites.filter { keyOfFavorite(it) in catalogKeys }
    }
}
