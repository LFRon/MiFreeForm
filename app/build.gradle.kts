import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.rikka.tools.refine)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hiltAndroid)
}

android {
    val buildTime = System.currentTimeMillis()
    val baseVersionName = "Preview1.1"
    namespace = "com.buildsession.betterYAMF"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.buildsession.betterYAMF"
        minSdk = 33
        targetSdk = 36
        versionCode = 3
        versionName = baseVersionName

        ndk {
            abiFilters.add("arm64-v8a")
            // abiFilters.addAll(listOf("armeabi-v7a", "x86", "x86_64"))
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("long", "BUILD_TIME", buildTime.toString())
    }
    packaging {
        resources.excludes.addAll(
            arrayOf(
                "META-INF/**",
                "kotlin/**"
            )
        )
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        languageVersion = "2.0"
    }
    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }
    lint {
        abortOnError = false
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.wear)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.preference.ktx)

    compileOnly(project(":android-stub"))
    compileOnly(libs.rikka.hidden.stub)
    implementation(libs.rikka.hidden.compat)

    //never upgrade until new extension function
    //noinspection GradleDependency
    implementation(libs.ezxhelper)
    compileOnly(libs.xposed.api)

    //lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    //flexbox
    implementation(libs.flexbox)

    //dynamicanimation
    implementation(libs.androidx.dynamicanimation.ktx)

    //gson
    implementation(libs.gson)

    //material
    implementation(libs.material)

    //glide
    implementation (libs.glide)

    // byte buddy
    implementation(libs.byte.buddy.android)

    //lifecycle service
    implementation(libs.androidx.lifecycle.service)

    //room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    //hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    ksp(libs.androidx.hilt.compiler)
}

val gitHash: String
    get() {
        val out = ByteArrayOutputStream()
        val cmd = exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            standardOutput = out
            isIgnoreExitValue = true
        }
        return if (cmd.exitValue == 0)
            out.toString().trim()
        else
            "(error)"
    }

val isDirty: Boolean
    get() {
        val out = ByteArrayOutputStream()
        exec {
            commandLine("git", "diff", "--stat")
            standardOutput = out
            isIgnoreExitValue = true
        }
        return out.size() != 0
    }

// Compile the pure event loop with only the JVM standard library. An accidental Android / hook
// dependency must fail the build, even though the app's normal Kotlin compilation has android.jar.
val coreBoundaryCompiler by configurations.creating
val coreBoundaryStdlib by configurations.creating
dependencies {
    coreBoundaryCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
    coreBoundaryStdlib("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
}
val compileWindowCoreBoundary by tasks.registering(JavaExec::class) {
    val sources = fileTree("src/main/java/com/buildsession/betterYAMF/window/core") { include("**/*.kt") }
    val destination = layout.buildDirectory.dir("window-core-boundary")
    inputs.files(sources)
    inputs.files(coreBoundaryStdlib)
    outputs.dir(destination)
    classpath = coreBoundaryCompiler + coreBoundaryStdlib
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    doFirst {
        destination.get().asFile.mkdirs()
        setArgs(listOf("-no-stdlib", "-no-reflect", "-jvm-target", "11", "-classpath",
            coreBoundaryStdlib.asPath, "-d", destination.get().asFile.absolutePath) +
            sources.files.sortedBy { it.path }.map { it.absolutePath })
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(compileWindowCoreBoundary)
}
