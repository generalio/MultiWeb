plugins {
  id("multiweb.kmp-library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(project(":webview-api"))
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
  }
}
