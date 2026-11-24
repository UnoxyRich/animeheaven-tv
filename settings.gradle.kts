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
        if (providers.environmentVariable("GITHUB_ACTIONS").orNull == "true") {
            // Maven Central often throttles hosted runners on these Android test dependencies.
            maven("https://maven-central.storage-download.googleapis.com/maven2") {
                name = "GoogleMavenCentralMirror"
                content {
                    includeModule("org.hamcrest", "hamcrest-integration")
                    includeModule("org.hamcrest", "hamcrest-library")
                    includeModule("com.squareup", "javawriter")
                    includeModule("com.google.code.findbugs", "jsr305")
                }
            }
        }
        mavenCentral()
    }
}

rootProject.name = "animeheaven-tv"
include(":app")
