package com.buildsession.betterYAMF.common.logging

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keep every project-owned log writer behind the build-variant/severity policy. */
class LoggingBoundaryTest {
    @Test fun allProjectLoggingUsesTheCommonPolicy() {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val sources = listOf("app/src/main/java", "window-api/src/main/kotlin",
            "window-core/src/main/kotlin", "window-runtime/src/main/kotlin").map { File(root, it) }
        val violations = mutableListOf<String>()
        val writers = Regex("android\\.util\\.Log|XposedBridge\\s*\\.\\s*log\\s*\\(|\\bprintln\\s*\\(|\\bprintStackTrace\\s*\\(|System\\.(out|err)\\b")
        sources.asSequence().flatMap { it.walkTopDown().asSequence() }
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filterNot { it.invariantSeparatorsPath.endsWith("/common/logging/AppLog.kt") }
            .forEach { file ->
                val source = file.readText()
                    .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
                    .replace(Regex("//[^\\n]*"), " ")
                source.lineSequence().forEachIndexed { line, text ->
                    if (writers.containsMatchIn(text)) violations += "${file.name}:${line + 1}: $text"
                }
            }
        assertTrue("Logging bypasses AppLog:\n${violations.joinToString("\n")}", violations.isEmpty())
    }
}
