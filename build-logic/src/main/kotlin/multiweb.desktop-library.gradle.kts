import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("multiweb.publishing")
}

extensions.configure<KotlinJvmProjectExtension> {
  jvmToolchain(17)
}

extensions.configure<JavaPluginExtension> {
  withSourcesJar()
}

// Kotlin JVM 插件在项目评估完成后提供 java 软件组件，用其生成可被 Maven 使用方解析的发布物。
afterEvaluate {
  extensions.configure<PublishingExtension> {
    publications {
      register<MavenPublication>("mavenJava") {
        from(components["java"])
      }
    }
  }
}
