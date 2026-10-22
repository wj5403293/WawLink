plugins {
    kotlin("multiplatform")
    id("com.android.library")
}
kotlin {
    androidTarget { publishLibraryVariants("release", "debug") }
    jvm("desktop")
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies { api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
android {
    namespace = "dev.wawlink.protocol"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
