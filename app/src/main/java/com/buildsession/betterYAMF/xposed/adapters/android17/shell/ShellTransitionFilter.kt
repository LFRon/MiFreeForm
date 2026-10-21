package com.buildsession.betterYAMF.xposed.adapters.android17.shell

/** AD-only container ancestry. A non-task activity beneath an owned task is owned too. The input
 * ordering is not necessarily parent-first. Unrelated display/wallpaper/PiP changes survive.
 */
internal object ShellTransitionFilter {
    data class Node(val container: Any, val parent: Any?, val owned: Boolean)
    fun excluded(nodes: List<Node>): Set<Any> {
        val parents = nodes.associate { it.container to it.parent }
        val direct = nodes.filter { it.owned }.mapTo(HashSet()) { it.container }
        return nodes.mapNotNullTo(HashSet()) { node ->
            var current: Any? = node.container
            val visited = HashSet<Any>()
            while (current != null && visited.add(current)) {
                if (current in direct) return@mapNotNullTo node.container
                current = parents[current]
            }
            null
        }
    }
}
