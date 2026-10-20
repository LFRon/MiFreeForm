package com.buildsession.betterYAMF.window.runtime

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArchitectureBoundaryTest {
    private val root = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private fun source(file: File) = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\\n]*"), " ")

    @Test fun apiCoreAndRuntimeHaveNoPlatformReflectionOrClockDependency() {
        val forbidden = Regex("\\b(android|androidx|de\\.robv|com\\.android|io\\.github\\.libxposed)\\.|" +
            "\\bBuildConfig\\b|java\\.lang\\.reflect|Class\\.forName|System\\.(currentTimeMillis|nanoTime)|Thread\\.sleep")
        val violations = mutableListOf<String>()
        for (module in listOf("window-api", "window-core", "window-runtime")) {
            File(root, "$module/src/main").walkTopDown().filter { it.extension == "kt" }.forEach {
                if (forbidden.containsMatchIn(source(it))) violations += it.relativeTo(root).path
            }
        }
        assertTrue("Platform mechanisms crossed the JVM boundary: $violations", violations.isEmpty())
    }

    @Test fun allAndroidAndHookConsumersCompileAgainstPublicContractsRatherThanCore() {
        val violations = File(root, "app/src").walkTopDown().filter { it.extension in setOf("kt", "java") }
            .filter { source(it).contains("com.buildsession.betterYAMF.window.core") }
            .map { it.relativeTo(root).path }.toList()
        assertTrue("App code depends on CORE implementation: $violations", violations.isEmpty())
        val appBuild = File(root, "app/build.gradle.kts").readText()
        assertTrue("CORE must not be an app compile dependency", !appBuild.contains("project(\":window-core\")"))
        val runtimeBuild = File(root, "window-runtime/build.gradle.kts").readText()
        assertTrue(runtimeBuild.contains("implementation(project(\":window-core\"))"))
    }

    @Test fun nativeHookCallbackAndInvocationTypesAreConfinedToTheFrameworkBridge() {
        val violations = File(root, "app/src/main/java/com/buildsession/betterYAMF/xposed")
            .walkTopDown().filter { it.extension == "kt" && "/bridge/" !in it.invariantSeparatorsPath }
            .filter { Regex("\\b(XC_MethodHook|XC_MethodReplacement|XposedBridge)\\b").containsMatchIn(source(it)) }
            .map { it.relativeTo(root).path }.toList()
        assertTrue("Hook framework types escaped the AD bridge: $violations", violations.isEmpty())
    }

    @Test fun runtimeFactoriesDoNotLeakCoreTypesThroughTheirPublicSignatures() {
        val types = listOf(WindowRuntimes::class.java, LauncherEntries::class.java)
        val signatures = types.flatMap { it.declaredMethods.toList() }.map { it.toGenericString() }
        assertTrue(signatures.joinToString("\n"), signatures.none { it.contains("window.core.") })
    }
}
