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

rootProject.name = "Zhijuan"

include(":app")
include(":data")
include(":core")
include(":provider")
include(":feature:connection")
include(":feature:creation")
include(":feature:generation")
include(":feature:reader")
include(":feature:library")
include(":feature:template")
