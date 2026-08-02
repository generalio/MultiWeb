import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
  id("org.jetbrains.kotlin.jvm")
  `maven-publish`
}

extensions.configure<KotlinJvmProjectExtension> {
  jvmToolchain(17)
}
