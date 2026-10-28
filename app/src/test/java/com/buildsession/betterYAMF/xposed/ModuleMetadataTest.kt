package com.buildsession.betterYAMF.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * The module must load as a libxposed API 102 module and nothing else. These are packaging facts a
 * compiler cannot check: a stray legacy entry file silently switches the framework back to the
 * compatibility bridge, and a missing `system` scope silently loses system_server hooks.
 */
class ModuleMetadataTest {
    private val root = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val app = File(root, "app")

    @Test
    fun modernEntryPointResolvesToProjectSource() {
        val entries = File(app, "src/main/resources/META-INF/xposed/java_init.list")
            .readLines().map(String::trim).filter(String::isNotEmpty)
        assertEquals(1, entries.size)
        val entry = entries.single()
        val source = File(app, "src/main/java/" + entry.replace('.', '/') + ".kt")
        assertTrue("${entry} has no source file", source.isFile)
        assertTrue("entry must extend XposedModule", source.readText().contains(": XposedModule()"))
    }

    @Test
    fun modulePropTargetsApi102() {
        val props = Properties()
        File(app, "src/main/resources/META-INF/xposed/module.prop").reader().use(props::load)
        assertEquals("102", props.getProperty("targetApiVersion"))
        val minimum = props.getProperty("minApiVersion").toInt()
        assertTrue("minApiVersion must not exceed the target", minimum <= 102)
    }

    @Test
    fun scopeNamesSystemServerExplicitly() {
        val scope = File(app, "src/main/resources/META-INF/xposed/scope.list")
            .readLines().map(String::trim).filter(String::isNotEmpty)
        assertTrue("system_server is the virtual 'system' scope, not 'android'", "system" in scope)
        assertTrue("com.android.systemui" in scope)
        assertTrue(scope.any { it == "com.android.launcher3" || it == "com.google.android.apps.nexuslauncher" })
    }

    @Test
    fun legacyEntryAndMetaDataAreGone() {
        assertFalse(
            "assets/xposed_init would make a legacy framework load the module",
            File(app, "src/main/assets/xposed_init").exists(),
        )
        val manifest = File(app, "src/main/AndroidManifest.xml").readText()
        listOf("xposedmodule", "xposedminversion", "xposedscope", "xposedrecommend").forEach {
            assertFalse("$it meta-data must not survive the migration", manifest.contains(it))
        }
        assertTrue(manifest.contains("android:description="))
    }

    @Test
    fun noSourceReferencesTheLegacyApi() {
        val comments = Regex("/\\*.*?\\*/|//[^\\n]*", RegexOption.DOT_MATCHES_ALL)
        val offenders = File(app, "src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter { it.readText().replace(comments, " ").contains("de.robv.android.xposed") }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("legacy Xposed API is unreachable for API 102 modules: $offenders", offenders.isEmpty())
    }
}
