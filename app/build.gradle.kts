plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

val quickJsRuntime by configurations.creating {
    isTransitive = false
}
android {
    namespace = "ru.robotpost.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.robotpost.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0-alpha"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
chaquopy {
    defaultConfig {
        version = "3.11"
        pip { install("yt-dlp[default]") }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    add(quickJsRuntime.name, "io.github.junkfood02.youtubedl-android:library:0.18.1")
}

tasks.register("extractQuickJsRuntime") {
    val outputDirectory = layout.projectDirectory.dir("src/main/jniLibs").asFile
    outputs.dir(outputDirectory)
    doLast {
        val aar = quickJsRuntime.singleFile
        val zip = java.util.zip.ZipFile(aar)
        try {
            listOf("arm64-v8a", "x86_64").forEach { abi ->
                val entry = zip.getEntry("jni/" + abi + "/libqjs.so")
                    ?: throw GradleException("QuickJS runtime missing for " + abi)
                val output = File(outputDirectory, abi + "/libqjs.so")
                output.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    output.outputStream().use { target -> input.copyTo(target) }
                }
            }
        } finally {
            zip.close()
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn("extractQuickJsRuntime")
}
