import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
  id("multiweb.ios-library")
}

kotlin {
  targets.withType<KotlinNativeTarget>().configureEach {
    compilations.getByName("main").cinterops.create("multiWebFileChooser") {
      definitionFile.set(project.file("src/nativeInterop/cinterop/MultiWebFileChooser.def"))
      compilerOpts("-I${project.projectDir}/src/nativeInterop/cinterop")
    }
  }

  sourceSets {
    iosMain.dependencies {
      api(project(":webview-api"))
      api(project(":webview-extension-api"))
    }
  }
}
