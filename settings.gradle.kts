pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // Xposed API —— 做 LSPosed 模块必须的（compileOnly，不进 APK）
        maven("https://api.xposed.info/")
    }
}
rootProject.name = "Nakour"
include(":app")
