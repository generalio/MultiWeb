@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// 测试夹具需要参与各平台公共测试编译，但不应继承发布约定插件。
plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.multiplatform")
}

extensions.configure<LibraryExtension> {
  namespace = "io.github.multiweb.test.fixtures"
  compileSdk = 35

  defaultConfig {
    minSdk = 24
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

extensions.configure<KotlinMultiplatformExtension> {
  jvmToolchain(17)

  jvm()
  androidTarget()
  js(IR) {
    browser()
  }
  wasmJs {
    browser()
  }
  iosArm64()
  iosSimulatorArm64()
  iosX64()
}
