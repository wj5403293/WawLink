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
            api(project(":agent-protocol"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        }
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":container-runtime"))
                implementation("org.yaml:snakeyaml:2.4")
            }
        }
        androidMain { dependsOn(jvmSharedMain) }
        val desktopMain by getting { dependsOn(jvmSharedMain) }
        commonTest.dependencies { implementation(kotlin("test")) }
        val jvmSharedTest by creating { dependsOn(commonTest.get()) }
        val desktopTest by getting { dependsOn(jvmSharedTest) }
    }
}
android {
    namespace = "dev.wawlink.driver"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
