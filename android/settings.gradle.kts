pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "Ara"

include(":app")
include(":core-common")
include(":core-ui")
include(":core-crypto")
include(":core-database")
include(":core-network")
include(":feature-chat")
include(":feature-calls")
include(":feature-contacts")
include(":feature-settings")
