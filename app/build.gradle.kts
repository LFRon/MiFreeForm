import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.rikka.tools.refine)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hiltAndroid)
}

// Kept outside version control. Both variants use this project-owned signing identity.
val signingPropertiesFile = rootProject.file("keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use { load(it) }
}
val projectKeystore = signingProperties.getProperty("storeFile")?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }
val validateProjectSigning by tasks.registering {
    group = "verification"
    description = "Checks the local signing configuration shared by Debug and Release."
    doLast {
        check(signingPropertiesFile.isFile) {
            "Missing keystore.properties. Copy keystore.properties.example and configure your signing key."
        }
        val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .filter { signingProperties.getProperty(it).isNullOrBlank() }
        check(missing.isEmpty()) { "Missing signing properties: ${missing.joinToString()}" }
        check(projectKeystore?.isFile == true) { "The keystore configured by storeFile does not exist." }
    }
}

android {
    val buildTime = System.currentTimeMillis()
    val baseVersionName = "Preview1.1"
    namespace = "com.buildsession.betterYAMF"
    compileSdk = 36

    defaultConfig {
        // No build-type suffix: Debug and Release update the same installed package.
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
    val projectSigning = signingConfigs.create("project") {
        storeFile = projectKeystore
        storeType = signingProperties.getProperty("storeType", "PKCS12")
        storePassword = signingProperties.getProperty("storePassword")
        keyAlias = signingProperties.getProperty("keyAlias")
        keyPassword = signingProperties.getProperty("keyPassword")
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
        getByName("debug") {
            signingConfig = projectSigning
        }
        release {
            signingConfig = projectSigning
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

// Validate before app compilation/packaging; JVM-only modules and IDE sync need no private key.
tasks.named("preBuild") { dependsOn(validateProjectSigning) }

dependencies {
    implementation(project(":window-runtime"))

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
