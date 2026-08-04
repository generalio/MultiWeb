plugins {
  id("multiweb.desktop-library")
}

dependencies {
  api(project(":webview-api"))
  api(project(":webview-extension-api"))
  api(libs.jcefmaven)
  testImplementation(kotlin("test"))
}
