@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://mvnrepository.com/artifact")
        // Vendored libxposed artifacts; see app/build.gradle.kts for why they are local.
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "BetterYAMF"
include(":app", ":android-stub", ":window-api", ":window-core", ":window-runtime")
