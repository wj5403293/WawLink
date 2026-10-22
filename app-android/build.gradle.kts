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
    sourceSets["debug"].assets.srcDir(layout.buildDirectory.dir("generated/debugAssets"))
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
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(compose.material3)
}
val prepareDebugRootfs by tasks.registering(Copy::class) {
    from(rootProject.layout.buildDirectory.file("outputs/ubuntu-main-debug.tar.zst"))
    into(layout.buildDirectory.dir("generated/debugAssets"))
    doFirst {
        check(rootProject.layout.buildDirectory.file("outputs/ubuntu-main-debug.tar.zst").get().asFile.isFile) {
            "缺少预置 Rootfs；请先执行 ./scripts/build-debug-rootfs.sh"
        }
    }
}
tasks.matching { it.name == "mergeDebugAssets" }.configureEach { dependsOn(prepareDebugRootfs) }
