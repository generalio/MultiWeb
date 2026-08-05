import com.android.build.api.dsl.LibraryExtension

plugins {
  id("multiweb.kmp-library")
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
}

extensions.configure<LibraryExtension> {
  defaultConfig {
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      /** 公共 API 的函数签名包含 Compose 类型，必须向使用方传递。 */
      api(compose.runtime)
      api(compose.ui)
      api(project(":webview-api"))
      api(project(":webview-extension-api"))
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
    androidInstrumentedTest.dependencies {
      implementation(libs.androidx.activity.compose)
      implementation(libs.androidx.compose.ui.test.junit4)
      implementation(libs.androidx.test.runner)
    }
    androidMain.dependencies {
      api(project(":webview-android"))
    }
    iosMain.dependencies {
      api(project(":webview-ios"))
    }
    jvmMain.dependencies {
      api(compose.desktop.currentOs)
      api(project(":webview-desktop"))
    }
    jsMain.dependencies {
      implementation(project(":webview-browser"))
    }
    wasmJsMain.dependencies {
      implementation(project(":webview-browser"))
    }
  }
}
