package com.buildsession.betterYAMF.manager.sidebar.core

import java.util.Locale

/**
 * 应用列表查询（纯逻辑）：排序与搜索的大小写语义与现实现逐位一致（`Locale.ROOT` 小写）。
 */
object AppListQuery {

    fun <T> sortByLabel(items: List<T>, labelOf: (T) -> String): List<T> =
        items.sortedBy { labelOf(it).lowercase(Locale.ROOT) }

    fun <T> filterByQuery(items: List<T>, query: String, labelOf: (T) -> String): List<T> {
        if (query.isEmpty()) return items
        val q = query.lowercase(Locale.ROOT)
        return items.filter { labelOf(it).lowercase(Locale.ROOT).contains(q) }
    }
}
