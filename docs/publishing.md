# 发布说明

## 本地验证

在工程根目录执行：

```shell
./gradlew publishToMavenLocal
```

使用方加入 `mavenLocal()` 后，即可使用 `io.github.multiweb` 下的各模块坐标。当前版本由
`VERSION_NAME` 决定。

## GitHub Packages

在本机 Gradle 用户目录的 `gradle.properties` 或 CI 环境中提供下列值：

```properties
MULTIWEB_GITHUB_REPOSITORY=<owner>/<repository>
MULTIWEB_GITHUB_USER=<GitHub 用户名或机器人账号>
MULTIWEB_GITHUB_TOKEN=<具备 packages:write 权限的令牌>
```

CI 中可改用 GitHub 提供的 `GITHUB_REPOSITORY`、`GITHUB_ACTOR` 与 `GITHUB_TOKEN` 环境变量。配置后执行：

```shell
./gradlew publish
```

使用方配置仓库：

```kotlin
repositories {
  maven("https://maven.pkg.github.com/<owner>/<repository>") {
    credentials {
      username = providers.gradleProperty("MULTIWEB_GITHUB_USER").orNull
      password = providers.gradleProperty("MULTIWEB_GITHUB_TOKEN").orNull
    }
  }
}
```

## 通用 Maven 仓库

支持使用 Basic Auth 的 Maven 仓库。发布环境提供以下属性后执行 `./gradlew publish`：

```properties
MULTIWEB_MAVEN_REPOSITORY_URL=<仓库部署地址>
MULTIWEB_MAVEN_USERNAME=<用户名>
MULTIWEB_MAVEN_PASSWORD=<密码或令牌>
```

POM 的以下属性仅在全部所需值存在时写入，避免生成虚构元数据：

```properties
MULTIWEB_POM_URL=<项目主页>
MULTIWEB_POM_LICENSE_NAME=<许可证名称>
MULTIWEB_POM_LICENSE_URL=<许可证地址>
MULTIWEB_POM_SCM_URL=<源码仓库地址>
MULTIWEB_POM_SCM_CONNECTION=<SCM 连接地址>
MULTIWEB_POM_DEVELOPER_ID=<开发者标识>
MULTIWEB_POM_DEVELOPER_NAME=<开发者名称>
```

Maven Central 的最终发布还需要仓库所有者确认许可证、源码地址、开发者信息和 Central Portal 签名方案；这些
信息尚未在工程中定义，因此本阶段不配置远程 Central 发布。
