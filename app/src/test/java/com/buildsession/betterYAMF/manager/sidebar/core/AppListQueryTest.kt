package com.buildsession.betterYAMF.manager.sidebar.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AppListQueryTest {

    private data class Item(val label: String)

    private val labelOf: (Item) -> String = { it.label }

    @Test
    fun sortByLabelIsCaseInsensitive() {
        val items = listOf(Item("b"), Item("A"), Item("c"), Item("B"))
        assertEquals(
            listOf("A", "b", "B", "c"),
            AppListQuery.sortByLabel(items, labelOf).map { it.label }
        )
    }

    @Test
    fun filterByQueryMatchesCaseInsensitiveContains() {
        val items = listOf(Item("Camera"), Item("时钟"), Item("CAMERA Pro"))
        assertEquals(
            listOf("Camera", "CAMERA Pro"),
            AppListQuery.filterByQuery(items, "camera", labelOf).map { it.label }
        )
    }

    @Test
    fun emptyQueryReturnsAllItems() {
        val items = listOf(Item("a"), Item("b"))
        assertEquals(items, AppListQuery.filterByQuery(items, "", labelOf))
    }

    @Test
    fun uppercaseQueryMatchesLowercaseLabel() {
        val items = listOf(Item("wechat"), Item("alipay"))
        assertEquals(
            listOf("wechat"),
            AppListQuery.filterByQuery(items, "WE", labelOf).map { it.label }
        )
    }

    @Test
    fun noMatchYieldsEmpty() {
        val items = listOf(Item("a"))
        assertEquals(emptyList<Item>(), AppListQuery.filterByQuery(items, "zzz", labelOf))
    }
}
