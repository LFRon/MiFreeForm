package com.buildsession.betterYAMF.manager.sidebar.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SidebarAppsMergerTest {

    private data class Fav(val key: String, val id: Int)
    private data class Catalog(val key: String, val id: Int)

    @Test
    fun keepsFavoritesPresentInCatalogInFavoriteOrder() {
        val favorites = listOf(Fav("b", 1), Fav("a", 2), Fav("c", 3))
        val catalog = listOf(Catalog("c", 30), Catalog("a", 10))
        assertEquals(
            listOf(Fav("a", 2), Fav("c", 3)),
            SidebarAppsMerger.intersectBy(favorites, catalog, { it.key }, { it.key })
        )
    }

    @Test
    fun emptyCatalogYieldsEmptyResult() {
        val favorites = listOf(Fav("a", 1))
        assertEquals(
            emptyList<Fav>(),
            SidebarAppsMerger.intersectBy(favorites, emptyList<Catalog>(), { it.key }, { it.key })
        )
    }

    @Test
    fun emptyFavoritesYieldsEmptyResult() {
        val catalog = listOf(Catalog("a", 1))
        assertEquals(
            emptyList<Fav>(),
            SidebarAppsMerger.intersectBy(emptyList<Fav>(), catalog, { it.key }, { it.key })
        )
    }

    @Test
    fun keyExtractorsCanDifferInType() {
        val favorites = listOf(Fav("com.a/.A", 7))
        val catalog = listOf(Catalog("com.a/.A", 70), Catalog("com.b/.B", 71))
        assertEquals(
            listOf(Fav("com.a/.A", 7)),
            SidebarAppsMerger.intersectBy(favorites, catalog, { it.key }, { it.key })
        )
    }
}
