import com.android.build.api.dsl.LibraryExtension

plugins {
  id("multiweb.android-library")
}

extensions.configure<LibraryExtension> {
  defaultConfig {
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
}

dependencies {
  implementation(project(":webview-api"))
  api(project(":webview-extension-api"))
  implementation(libs.androidx.webkit)
  androidTestImplementation(libs.androidx.test.runner)
  testImplementation(kotlin("test"))
}
