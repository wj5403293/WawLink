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
    }
}
rootProject.name = "WawLink"
include(":agent-protocol", ":agent-ui", ":agent-driver-omp", ":container-runtime", ":app-android", ":app-desktop")
