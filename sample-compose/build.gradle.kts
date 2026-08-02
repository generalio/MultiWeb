@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
}

extensions.configure<ApplicationExtension> {
  namespace = "io.github.multiweb.sample"
  compileSdk = 35

  defaultConfig {
    applicationId = "io.github.multiweb.sample"
    minSdk = 24
    targetSdk = 35
    versionCode = 1
    versionName = "0.1.1"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

extensions.configure<KotlinMultiplatformExtension> {
  jvmToolchain(17)

  androidTarget()
  jvm("desktop")
  iosArm64()
  iosSimulatorArm64()
  iosX64()
  js(IR) {
    browser()
    binaries.executable()
  }
  wasmJs {
    browser()
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.material3)
      implementation(compose.ui)
      implementation(project(":webview-api"))
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(project(":webview-test-fixtures"))
    }
    androidMain.dependencies {
      implementation(libs.androidx.activity.compose)
      implementation(project(":webview-android"))
    }
    getByName("desktopMain").dependencies {
      implementation(compose.desktop.currentOs)
      implementation(project(":webview-desktop"))
    }
    iosMain.dependencies {
      implementation(project(":webview-ios"))
    }
    jsMain.dependencies {
      implementation(project(":webview-browser"))
    }
    wasmJsMain.dependencies {
      implementation(project(":webview-browser"))
    }
  }
}

compose.desktop {
  application {
    mainClass = "io.github.multiweb.sample.desktop.MainKt"
  }
}
