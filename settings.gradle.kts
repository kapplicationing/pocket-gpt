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

rootProject.name = "pocket-gpt"

include(
    ":apps:mobile-android",
    ":apps:mobile-android-baselineprofile",
    ":apps:mobile-android-host",
    ":packages:core-domain",
    ":packages:inference-adapters",
    ":packages:tool-runtime",
    ":packages:memory",
    ":packages:native-bridge",
    ":packages:app-runtime",
)
