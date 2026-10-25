plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}
android {
    namespace = "dev.wawlink.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.wawlink.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets["main"].java.srcDir("../app-desktop/src/sharedMain/kotlin")
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/*.so"
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":agent-ui"))
    implementation(project(":agent-driver-omp"))
    implementation(project(":container-runtime"))
    implementation(project(":terminal-view"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("org.luaj:luaj-jse:3.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation(compose.material3)
}
