plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}
kotlin {
    androidTarget()
    jvm("desktop")
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        }
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("org.apache.commons:commons-compress:1.27.1")
                implementation("org.tukaani:xz:1.10")
            }
        }
        androidMain { dependsOn(jvmSharedMain); dependencies { implementation("com.github.luben:zstd-jni:1.5.7-2@aar") } }
        val desktopMain by getting { dependsOn(jvmSharedMain); dependencies { implementation("com.github.luben:zstd-jni:1.5.7-2") } }
        commonTest.dependencies { implementation(kotlin("test")) }
        val jvmSharedTest by creating { dependsOn(commonTest.get()) }
        val desktopTest by getting { dependsOn(jvmSharedTest) }
    }
}
android {
    namespace = "dev.wawlink.runtime"
    compileSdk = 35
    defaultConfig { minSdk = 26; ndk { abiFilters += "arm64-v8a" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
