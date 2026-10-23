plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}
kotlin {
    jvm("desktop")
    jvmToolchain(17)
    sourceSets {
        val desktopMain by getting {
            kotlin.srcDir("src/sharedMain/kotlin")
            dependencies {
                implementation(project(":agent-ui"))
                implementation(project(":agent-driver-omp"))
                implementation(project(":container-runtime"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("org.jetbrains.jediterm:jediterm-ui:3.63")
                implementation("org.jetbrains.jediterm:jediterm-core:3.63")
            }
        }
        val desktopTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
    }
}
compose.desktop {
    application {
        mainClass = "dev.wawlink.app.MainKt"
        jvmArgs += listOf("-Dwawlink.projectDir=${rootProject.projectDir.absolutePath}")
        nativeDistributions {
            packageName = "WawLink"
            packageVersion = "1.0.0"
        }
    }
}
