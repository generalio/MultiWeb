plugins {
  id("multiweb.desktop-library")
}

dependencies {
  api(project(":webview-api"))
  api(libs.jcefmaven)
}
