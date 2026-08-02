plugins {
  id("multiweb.android-library")
}

dependencies {
  implementation(project(":webview-api"))
  testImplementation(kotlin("test"))
}
