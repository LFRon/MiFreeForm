package com.buildsession.betterYAMF.window.core

/**
 * mini 位置记忆（纯逻辑）：
 * - [rememberIfAbsent]：进入 mini 前记录位置；已有记忆不覆盖（重复进入保持最初位置），null 不写；
 * - [take]：退出 mini 时取用并清空（下次进入重新记录）。
 */
class PositionMemory {

    private var remembered: WindowRect? = null

    fun rememberIfAbsent(value: WindowRect?) {
        if (remembered == null) {
            remembered = value
        }
    }

    fun take(): WindowRect? {
        val current = remembered
        remembered = null
        return current
    }
}
