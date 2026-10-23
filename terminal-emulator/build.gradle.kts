plugins { id("com.android.library") }

android {
    namespace = "com.termux.emulator"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { ndkBuild { cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Os") } }
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDir("src/main/assets")
}
dependencies { implementation("androidx.annotation:annotation:1.9.1") }
