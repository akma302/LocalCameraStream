pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "LocalCameraStream"
include(":app", ":camera:api", ":camera:camera2-impl", ":camera:ndk-impl",
    ":protocol", ":network", ":video:api", ":media", ":server", ":client:api", ":client:impl", ":client:ui")
