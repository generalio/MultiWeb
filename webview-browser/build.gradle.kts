plugins {
  id("multiweb.browser-library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(project(":webview-api"))
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
    wasmJsMain.dependencies {
      implementation("org.jetbrains.kotlinx:kotlinx-browser-wasm-js:0.3.1")
    }
  }
}
