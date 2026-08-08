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
  // 使用基础插件接入 Central Portal 和 GPG 签名，发布物仍由本工程约定插件手动注册。
  implementation("com.vanniktech:gradle-maven-publish-plugin:0.34.0")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
  implementation("org.jetbrains.kotlinx:binary-compatibility-validator:0.17.0")
}
