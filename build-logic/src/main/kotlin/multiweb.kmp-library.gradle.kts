@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.multiplatform")
  id("org.jetbrains.kotlinx.binary-compatibility-validator")
  id("multiweb.publishing")
}

extensions.configure<LibraryExtension> {
  namespace = "io.github.multiweb.${project.name.replace('-', '.')}"
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

  androidTarget {
    publishLibraryVariants("release")
  }
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
