@file:OptIn(
  org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class,
  org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class,
)

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// JCEF 的 macOS Swing 嵌入实现依赖这些 JDK 内部 AWT 包，Java 17+ 必须显式导出。
val jcefMacOsJvmArguments = if (System.getProperty("os.name").startsWith("Mac")) {
  listOf(
    "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.lwawt=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
  )
} else {
  emptyList()
}

// macOS 上的 JCEF 与 Compose Swing 互操作依赖 JetBrains Runtime 的 AWT 实现，避免 Microsoft JDK 17 的 AppKit 崩溃。
val desktopJbrLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
  languageVersion.set(JavaLanguageVersion.of(21))
  vendor.set(JvmVendorSpec.JETBRAINS)
}

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
  jvm("desktop") {
    // 为 Kotlin Multiplatform 生成的 desktopRun 任务指定默认入口，支持 Gradle 和 IDE 直接运行。
    mainRun {
      mainClass.set("io.github.multiweb.sample.desktop.MainKt")
    }
  }
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
    // JCEF 在 macOS Swing 嵌入时需要访问该 JDK 内部 API；参数同时用于本地运行与打包产物。
    jvmArgs(*jcefMacOsJvmArguments.toTypedArray())
  }
}

tasks.withType<JavaExec>().configureEach {
  if (name == "desktopRun") {
    // desktopRun 由 Kotlin 为 IDE 生成，必须使用 JBR 运行 JCEF，并补充 JDK 模块访问权限。
    javaLauncher.set(desktopJbrLauncher)
    jvmArgs(*jcefMacOsJvmArguments.toTypedArray())
  }
}
