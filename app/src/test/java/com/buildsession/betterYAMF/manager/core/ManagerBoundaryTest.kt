package com.buildsession.betterYAMF.manager.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManagerBoundaryTest {
    private val root = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private fun source(file: File) = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\\n]*"), " ")

    @Test fun uiUsesLogicGatewaysRatherThanHookOrTransportImplementations() {
        val forbidden = Regex("(?m)^import .*\\.(xposed|ipc|manager\\.services|window\\.backend|window\\.host)\\.|" +
            "(?m)^import (de\\.robv|io\\.github\\.libxposed)\\.")
        val violations = listOf("ui", "adapter", "applist", "sidebar").flatMap { part ->
            File(root, "app/src/main/java/com/buildsession/betterYAMF/manager/$part")
                .walkTopDown().filter { it.extension == "kt" && "/core/" !in it.invariantSeparatorsPath }
                .filter { forbidden.containsMatchIn(source(it)) }.map { it.relativeTo(root).path }.toList()
        }
        assertTrue("UI bypasses application logic: $violations", violations.isEmpty())
    }

    @Test fun settingsUiDoesNotMutateRepositorySnapshotsDirectly() {
        val violations = File(root, "app/src/main/java/com/buildsession/betterYAMF/manager/ui")
            .walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("\\bconfig\\s*\\.\\s*\\w+\\s*=(?!=)").containsMatchIn(source(it)) }
            .map { it.relativeTo(root).path }.toList()
        assertTrue("UI writes mutable configuration outside its repository: $violations", violations.isEmpty())
    }

    @Test fun serviceConnectionsUseEventsRatherThanPollingLoops() {
        val paths = listOf("xposed/services/UserService.kt", "manager/services/ModuleServiceClient.kt")
        val forbidden = Regex("Thread\\.sleep|while\\s*\\(\\s*true\\s*\\)|scheduleAtFixedRate|scheduleWithFixedDelay")
        val violations = paths.flatMap { path ->
            File(root, "app/src/main/java/com/buildsession/betterYAMF/$path")
                .walkTopDown().filter { it.extension == "kt" }
                .filter { forbidden.containsMatchIn(source(it)) }.map { it.relativeTo(root).path }.toList()
        }
        assertTrue("Service binding introduced power-consuming polling: $violations", violations.isEmpty())
    }
}
