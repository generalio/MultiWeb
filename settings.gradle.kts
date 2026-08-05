pluginManagement {
  repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
  repositories {
    // Kotlin/JS 浏览器测试只从 Node.js 官方分发源解析运行时，避免开放额外 Maven 依赖来源。
    ivy {
      name = "NodeJsDistributions"
      url = uri("https://nodejs.org/dist")
      patternLayout {
        artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
      }
      metadataSources {
        artifact()
      }
      content {
        includeModule("org.nodejs", "node")
      }
    }
    // Kotlin/JS 的 npm 依赖由 Yarn 官方发布包安装，限定为单一运行时模块。
    ivy {
      name = "YarnDistributions"
      url = uri("https://github.com/yarnpkg/yarn/releases/download")
      patternLayout {
        artifact("v[revision]/[artifact]-v[revision].[ext]")
      }
      metadataSources {
        artifact()
      }
      content {
        includeModule("com.yarnpkg", "yarn")
      }
    }
    google()
    mavenCentral()
  }
}

rootProject.name = "MultiWeb"

includeBuild("build-logic")
include(":webview-api")
include(":webview-extension-api")
include(":webview-test-fixtures")
include(":webview-android")
include(":webview-ios")
include(":webview-desktop")
include(":webview-browser")
include(":webview-compose")
include(":sample-compose")
