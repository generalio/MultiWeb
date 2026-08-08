plugins {
  id("multiweb.ios-library")
}

kotlin {
  sourceSets {
    iosMain.dependencies {
      api(project(":webview-api"))
      api(project(":webview-extension-api"))
    }
  }
}
