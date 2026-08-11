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
include(":core:model")
include(":core:task")
include(":core:security")
include(":core:database")
include(":core:backup")
include(":core:network")
include(":core:diagnostics")
include(":provider:common")
include(":provider:fake")
include(":provider:capability-storage")
include(":provider:stream")
include(":provider:transport")
include(":provider:openai-chat")
include(":provider:openai-responses")
include(":provider:anthropic")
include(":provider:gemini")
include(":feature:generation")
