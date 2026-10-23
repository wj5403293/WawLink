plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.library")
    `maven-publish`
}
kotlin {
    androidTarget { publishLibraryVariants("release", "debug") }
    jvm("desktop")
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-protocol"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(compose.animation)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("dev.snipme:highlights:1.0.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmSharedMain)
        androidMain.dependencies {
            implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
        }
        val desktopMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("com.fifesoft:rsyntaxtextarea:3.6.0")
                // JavaFX 21 runs on the application's JDK 17. Ship WebKit natives with the app.
                val os = System.getProperty("os.name").lowercase()
                val arm = System.getProperty("os.arch") in setOf("aarch64", "arm64")
                val platform = when {
                    os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
                    os.contains("win") -> "win"
                    else -> if (arm) "linux-aarch64" else "linux"
                }
                listOf("base", "graphics", "controls", "media", "web").forEach {
                    implementation("org.openjfx:javafx-$it:21.0.13:$platform")
                }
            }
        }
        val desktopTest by getting { dependencies { implementation(kotlin("test")) } }
    }
}
android {
    namespace = "dev.wawlink.ui"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
compose.resources {
    publicResClass = true
    packageOfResClass = "dev.wawlink.ui.resources"
    generateResClass = always
}
