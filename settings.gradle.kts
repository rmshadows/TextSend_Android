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
        // JitPack 远程仓库：https://jitpack.io
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "Textsend"
include(":app")
