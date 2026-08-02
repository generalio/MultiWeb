@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("org.jetbrains.kotlinx.binary-compatibility-validator")
  id("multiweb.publishing")
}

extensions.configure<KotlinMultiplatformExtension> {
  js(IR) {
    browser()
  }
  wasmJs {
    browser()
  }
}
