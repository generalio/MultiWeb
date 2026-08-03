plugins {
  id("multiweb.android-library")
}

dependencies {
  implementation(project(":webview-api"))
  api(project(":webview-extension-api"))
  implementation(libs.androidx.webkit)
  testImplementation(kotlin("test"))
}
