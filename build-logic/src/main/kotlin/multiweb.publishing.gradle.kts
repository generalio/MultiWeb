import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
  // 基础插件不会自动创建 publication，可避免与 Android、KMP 和 JVM 的现有发布配置冲突。
  id("com.vanniktech.maven.publish.base")
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

val githubRepository = providers.gradleProperty("MULTIWEB_GITHUB_REPOSITORY")
  .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
val githubUser = providers.gradleProperty("MULTIWEB_GITHUB_USER")
  .orElse(providers.environmentVariable("GITHUB_ACTOR"))
val githubToken = providers.gradleProperty("MULTIWEB_GITHUB_TOKEN")
  .orElse(providers.environmentVariable("GITHUB_TOKEN"))
val mavenRepositoryUrl = providers.gradleProperty("MULTIWEB_MAVEN_REPOSITORY_URL")
val mavenUser = providers.gradleProperty("MULTIWEB_MAVEN_USERNAME")
val mavenPassword = providers.gradleProperty("MULTIWEB_MAVEN_PASSWORD")
// Central 发布必须显式开启，避免日常构建和 GitHub Packages 发布错误要求签名私钥。
val mavenCentralEnabled = providers.gradleProperty("MULTIWEB_ENABLE_MAVEN_CENTRAL")
  .map(String::toBoolean)
  .orElse(false)

extensions.configure<MavenPublishBaseExtension> {
  if (mavenCentralEnabled.get()) {
    // 发布任务会自动向 Central Portal 上传并完成发布；仅限具备 CI 密钥的正式发布流程。
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
  }
}

extensions.configure<PublishingExtension> {
  publications.withType(MavenPublication::class.java).configureEach {
    groupId = project.group.toString()
    version = project.version.toString()

    pom {
      name.set("MultiWeb ${project.name}")
      description.set("Kotlin Multiplatform WebView components")
      url.set("https://github.com/generalio/MultiWeb")

      licenses {
        license {
          name.set("Apache-2.0")
          url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
          distribution.set("repo")
        }
      }
      developers {
        developer {
          id.set("generalio")
          name.set("generals")
          email.set("ChtlTree@outlook.com")
          url.set("https://github.com/generalio")
        }
      }
      scm {
        url.set("https://github.com/generalio/MultiWeb")
        connection.set("scm:git:git://github.com/generalio/MultiWeb.git")
        developerConnection.set("scm:git:ssh://git@github.com/generalio/MultiWeb.git")
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
