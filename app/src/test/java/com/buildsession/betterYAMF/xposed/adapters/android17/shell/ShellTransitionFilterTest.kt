package com.buildsession.betterYAMF.xposed.adapters.android17.shell

import org.junit.Assert.*
import org.junit.Test

class ShellTransitionFilterTest {
    private fun node(token: String, parent: String? = null, owned: Boolean = false) =
        ShellTransitionFilter.Node(token, parent, owned)

    @Test fun mixedTransitionKeepsOtherApplicationsHomePipAndWallpaper() {
        val nodes = listOf(node("child-activity", "mini"), node("app"), node("home"),
            node("mini", "root", true), node("wallpaper"), node("pip"), node("root", owned = true))
        val removed = ShellTransitionFilter.excluded(nodes)
        assertEquals(setOf("root", "mini", "child-activity"), removed)
        assertEquals(listOf("app", "home", "wallpaper", "pip"), nodes.map { it.container }.filterNot { it in removed })
        assertEquals(7, nodes.size)
    }
    @Test fun ancestryDoesNotDependOnZOrderAndDoesNotRemoveTheCommonDisplayParent() {
        val nodes = listOf(node("activity", "fragment"), node("fragment", "mini"),
            node("mini", "display", true), node("other", "display"), node("display"))
        for (order in listOf(nodes, nodes.reversed(), nodes.sortedBy { it.container.toString() }))
            assertEquals(setOf("mini", "fragment", "activity"), ShellTransitionFilter.excluded(order))
    }
    @Test fun noOwnershipLeavesTheWholeNativeTransitionIntact() {
        assertTrue(ShellTransitionFilter.excluded(listOf(node("a", "root"), node("root"))).isEmpty())
        assertTrue(ShellTransitionFilter.excluded(emptyList()).isEmpty())
    }
    @Test fun releaseOrReusedIdDoesNotInheritOwnershipFromAnEarlierProjection() {
        assertEquals(setOf("task"), ShellTransitionFilter.excluded(listOf(node("task", owned = true))))
        assertTrue(ShellTransitionFilter.excluded(listOf(node("task"))).isEmpty())
    }
    @Test fun orphanAndCyclicMetadataCannotHangOrHideUnrelatedTasks() {
        val nodes = listOf(node("a", "b"), node("b", "a"), node("orphan", "missing"), node("owned", owned = true))
        assertEquals(setOf("owned"), ShellTransitionFilter.excluded(nodes))
    }
    @Test fun syntheticChangesHaveIndependentIdentitiesAndFollowOnlyTheirOwnParent() {
        val ownedChild = Any(); val nativeBar = Any()
        val nodes = listOf(ShellTransitionFilter.Node(ownedChild, "owned", false),
            ShellTransitionFilter.Node(nativeBar, null, false), node("owned", owned = true))
        assertEquals(setOf("owned", ownedChild), ShellTransitionFilter.excluded(nodes))
    }
}
