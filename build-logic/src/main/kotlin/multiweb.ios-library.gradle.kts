import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("org.jetbrains.kotlinx.binary-compatibility-validator")
  `maven-publish`
}

extensions.configure<KotlinMultiplatformExtension> {
  iosArm64()
  iosSimulatorArm64()
  iosX64()
}
