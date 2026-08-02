plugins {
  `kotlin-dsl`
}

repositories {
  google()
  gradlePluginPortal()
  mavenCentral()
}

dependencies {
  implementation("com.android.tools.build:gradle:8.7.3")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
  implementation("org.jetbrains.kotlinx:binary-compatibility-validator:0.17.0")
}

