import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
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

  publishing {
    singleVariant("release") {
      withSourcesJar()
    }
  }
}

extensions.configure<KotlinAndroidProjectExtension> {
  jvmToolchain(17)
}

// AGP 在项目评估结束后才注册 release 软件组件，发布配置必须在此之后关联该组件。
afterEvaluate {
  extensions.configure<PublishingExtension> {
    publications {
      register<MavenPublication>("release") {
        from(components["release"])
      }
    }
  }
}
