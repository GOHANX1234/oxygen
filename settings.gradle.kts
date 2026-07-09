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

rootProject.name = "oxygen-s"

include(
    ":app",
    ":core-virtual",
    ":core-storage",
    ":core-loader",
    ":native-hook",
    ":compat",
    ":clone-registry-db",
)
