pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            content { includeGroup("org.jetbrains.jediterm") }
        }
    }
}
rootProject.name = "WawLink"
include(":agent-protocol", ":agent-ui", ":agent-driver-omp", ":container-runtime", ":app-android", ":app-desktop", ":terminal-emulator", ":terminal-view")
