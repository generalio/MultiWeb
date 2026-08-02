import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
  `maven-publish`
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

val pomUrl = providers.gradleProperty("MULTIWEB_POM_URL")
val pomLicenseName = providers.gradleProperty("MULTIWEB_POM_LICENSE_NAME")
val pomLicenseUrl = providers.gradleProperty("MULTIWEB_POM_LICENSE_URL")
val pomScmUrl = providers.gradleProperty("MULTIWEB_POM_SCM_URL")
val pomScmConnection = providers.gradleProperty("MULTIWEB_POM_SCM_CONNECTION")
val pomDeveloperId = providers.gradleProperty("MULTIWEB_POM_DEVELOPER_ID")
val pomDeveloperName = providers.gradleProperty("MULTIWEB_POM_DEVELOPER_NAME")
val githubRepository = providers.gradleProperty("MULTIWEB_GITHUB_REPOSITORY")
  .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
val githubUser = providers.gradleProperty("MULTIWEB_GITHUB_USER")
  .orElse(providers.environmentVariable("GITHUB_ACTOR"))
val githubToken = providers.gradleProperty("MULTIWEB_GITHUB_TOKEN")
  .orElse(providers.environmentVariable("GITHUB_TOKEN"))
val mavenRepositoryUrl = providers.gradleProperty("MULTIWEB_MAVEN_REPOSITORY_URL")
val mavenUser = providers.gradleProperty("MULTIWEB_MAVEN_USERNAME")
val mavenPassword = providers.gradleProperty("MULTIWEB_MAVEN_PASSWORD")

extensions.configure<PublishingExtension> {
  publications.withType(MavenPublication::class.java).configureEach {
    groupId = project.group.toString()
    version = project.version.toString()

    pom {
      name.set("MultiWeb ${project.name}")
      description.set("Kotlin Multiplatform WebView components")

      if (pomUrl.isPresent) {
        url.set(pomUrl.get())
      }
      if (pomLicenseName.isPresent && pomLicenseUrl.isPresent) {
        licenses {
          license {
            name.set(pomLicenseName.get())
            url.set(pomLicenseUrl.get())
          }
        }
      }
      if (pomScmUrl.isPresent) {
        scm {
          url.set(pomScmUrl.get())
          if (pomScmConnection.isPresent) {
            connection.set(pomScmConnection.get())
          }
        }
      }
      if (pomDeveloperId.isPresent && pomDeveloperName.isPresent) {
        developers {
          developer {
            id.set(pomDeveloperId.get())
            name.set(pomDeveloperName.get())
          }
        }
      }
    }
  }

  repositories {
    if (githubRepository.isPresent) {
      maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/${githubRepository.get()}")
        credentials {
          username = githubUser.orNull
          password = githubToken.orNull
        }
      }
    }

    if (mavenRepositoryUrl.isPresent) {
      maven {
        name = "ConfiguredMaven"
        url = uri(mavenRepositoryUrl.get())
        credentials {
          username = mavenUser.orNull
          password = mavenPassword.orNull
        }
      }
    }
  }
}
