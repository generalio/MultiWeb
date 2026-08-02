pluginManagement {
  repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "MultiWeb"

includeBuild("build-logic")
include(":webview-api")
include(":webview-test-fixtures")
include(":webview-android")
include(":webview-ios")
include(":webview-desktop")
