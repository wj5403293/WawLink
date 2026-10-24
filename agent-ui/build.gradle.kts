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
            // HCT / Material dynamic roles; Kotlin 2.1.10, no Compose dependency (Apache-2.0).
            implementation("com.materialkolor:material-color-utilities:2.0.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmSharedMain)
        androidMain.dependencies {
            implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
            implementation("io.github.Rosemoe.sora-editor:language-textmate:0.23.6")
        }
        androidUnitTest.dependencies { implementation(kotlin("test-junit")) }
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
    // Pure tokenizer regressions run on the JVM; only Sora's Android logger needs default stubs.
    testOptions.unitTests.isReturnDefaultValues = true
    sourceSets.getByName("test").resources.srcDir("src/commonMain/composeResources/files")
}
compose.resources {
    publicResClass = true
    packageOfResClass = "dev.wawlink.ui.resources"
    generateResClass = always
}
