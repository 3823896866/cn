pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 液态玻璃库（Kyant0/Backdrop）
        maven("https://jitpack.io")
    }
}
rootProject.name = "MikasaUI"
include(":app")
