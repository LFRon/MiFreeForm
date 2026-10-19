package com.buildsession.betterYAMF.window.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 架构边界锁：`window/core` 必须零平台依赖（逻辑层"空对空"事件循环）。
 *
 * 扫描核心层源码（剥离注释后）禁止出现 android / androidx / de.robv / com.android
 * 的引入或限定引用；hook 点位、反射、平台类名只允许出现在适配器层。
 * 新增策略/状态机若破坏该边界，本测试立即失败。
 */
class CoreBoundaryTest {

    @Test
    fun `核心层零平台依赖`() {
        val candidates = listOf(
            File("src/main/java/com/buildsession/betterYAMF/window/core"),
            File("app/src/main/java/com/buildsession/betterYAMF/window/core")
        )
        val dir = candidates.firstOrNull { it.isDirectory }
            ?: error("无法定位 window/core 源码目录（cwd=${File(".").absolutePath}）")

        val forbidden = Regex("\\b(android|androidx|de\\.robv|com\\.android|io\\.github\\.libxposed)\\.")
        val violations = mutableListOf<String>()
        dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .sortedBy { it.name }
            .forEach { file ->
                val stripped = file.readText()
                    .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
                    .replace(Regex("//[^\\n]*"), " ")
                stripped.lineSequence().forEachIndexed { index, line ->
                    if (forbidden.containsMatchIn(line)) {
                        violations += "${file.name}:${index + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue(
            "window/core 出现平台依赖（逻辑层必须零平台依赖，平台对接只允许在适配器层）:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
