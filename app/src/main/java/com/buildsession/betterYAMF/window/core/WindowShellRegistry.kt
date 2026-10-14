package com.buildsession.betterYAMF.window.core

class WindowShellRegistry(
    private val config: CoreConfig = CoreConfig()
) : TopPort {
    private val order = mutableListOf<Int>()
    private val controllers = mutableMapOf<Int, WindowShellController>()
    private var nextHandle = 1

    val count: Int
        get() = controllers.size

    fun register(registration: WindowRegistration): WindowShellController {
        val controller = WindowShellController(registration, config, this)
        controllers[nextHandle++] = controller
        order.add(0, registration.windowId)
        return controller
    }

    fun unregister(controller: WindowShellController) {
        controllers.entries.removeAll { it.value === controller }
        order.remove(controller.windowId)
    }

    override fun isTop(windowId: Int): Boolean =
        if (order.isNotEmpty()) order[0] == windowId else true

    override fun markTop(windowId: Int) {
        order.remove(windowId)
        order.add(0, windowId)
    }
}
