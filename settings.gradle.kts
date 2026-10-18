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

rootProject.name = "Cog"
include(":app")
// The contract integrations are written against, and the integrations themselves: each its own
// module, installed (or not) from Cog's settings.
include(":integration-api")
include(":integrations:homeassistant")
include(":integrations:spotify")
include(":integrations:tasker")
