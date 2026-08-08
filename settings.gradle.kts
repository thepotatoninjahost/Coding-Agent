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

rootProject.name = "CodingAgent"

include(":app")
include(":modules:architecture")
include(":modules:domain")
include(":modules:intake")
include(":modules:knowledge")
include(":modules:policy")
include(":modules:workspace")
include(":modules:research")
include(":modules:model")
include(":modules:orchestration")
include(":modules:terminal")
include(":modules:persistence")


include(":modules:live")
