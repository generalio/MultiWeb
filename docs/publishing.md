# 发布说明

MultiWeb 的公开发布坐标为 `io.github.generalio.multiweb:<模块名>:<版本>`。例如：

```kotlin
repositories {
  mavenCentral()
}

dependencies {
  implementation("io.github.generalio.multiweb:webview-api:<版本>")
  implementation("io.github.generalio.multiweb:webview-android:<版本>")
}
```

`io.github.multiweb` 是历史坐标，已发布的构件不能重命名；新版本请使用上述坐标。

发布清单只包含可供使用方依赖的组件：`webview-api`、`webview-extension-api`、`webview-android`、
`webview-ios`、`webview-desktop` 和 `webview-browser`。`webview-test-fixtures` 是工程内部测试夹具，
不应用发布插件，也不会出现在 GitHub Packages 或 Maven Central。

## Maven Central 前置条件

发布者需要完成以下一次性配置：

1. 在 [Central Portal](https://central.sonatype.com/) 注册账号，并验证 `io.github.generalio` 命名空间。
2. 在 Central Portal 的 User Token 页面生成发布令牌，保存生成的用户名和密码。
3. 生成 GPG 密钥对并发布公钥。Central Portal 要求构件签名可由公开密钥验证。
4. 不要将发布令牌、私钥或密码提交到仓库。

生成和查看 GPG 密钥：

```shell
gpg --full-generate-key
gpg --list-secret-keys --keyid-format LONG
gpg --keyserver hkps://keys.openpgp.org --send-keys <密钥ID>
```

Central Portal 会按签名指纹查询公开密钥。执行上传后，打开
`https://keys.openpgp.org/`，按密钥邮箱完成验证；未完成邮箱验证时，密钥可能不会被服务器公开给 Central Portal。
也可以用以下命令确认服务器能返回公钥：

```shell
gpg --keyserver hkps://keys.openpgp.org --recv-keys <密钥ID>
```

导出用于 CI 的 ASCII 装甲私钥。该命令会在终端显示私钥，只能复制到 GitHub Secret，不能保存到项目文件：

```shell
gpg --export-secret-keys --armor <密钥ID>
```

## GitHub Actions 密钥

在仓库的 `Settings` -> `Secrets and variables` -> `Actions` -> `New repository secret` 中配置：

| Secret | 值 |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal User Token 的用户名 |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal User Token 的密码 |
| `SIGNING_KEY` | 完整 ASCII 装甲 GPG 私钥 |
| `SIGNING_PASSWORD` | GPG 私钥口令；无口令私钥可不配置 |

推送符合 `vX.Y.Z` 格式的标签后，GitHub Packages 工作流始终执行；Maven Central 工作流只有在前三项必需 Secret 全部存在时才会执行。`SIGNING_PASSWORD` 仅在私钥设有口令时需要。Central 构件上传、校验与正式发布由 Central Portal 自动完成，索引生效通常需要数分钟。

## 本地发布验证

本机凭据只能放在 `~/.gradle/gradle.properties`，例如：

```properties
mavenCentralUsername=<Central Portal User Token 用户名>
mavenCentralPassword=<Central Portal User Token 密码>
signingInMemoryKey=<ASCII 装甲 GPG 私钥>
# 私钥设有口令时才配置 signingInMemoryKeyPassword。
```

先验证本地 Maven 构件：

```shell
./gradlew publishToMavenLocal
```

确认元数据和签名配置后，才可以显式上传 Central：

```shell
./gradlew publishAndReleaseToMavenCentral -PMULTIWEB_ENABLE_MAVEN_CENTRAL=true -PVERSION_NAME=<正式版本>
```

该命令会触发真实上传，版本不可覆盖前必须确认版本号未被 Maven Central 使用。

## GitHub Packages

GitHub Packages 继续保留为仓库分发渠道。发布标签会触发 [GitHub Packages 工作流](../.github/workflows/publish.yml)，其版本取自标签去掉 `v` 后的值。正式版本不能包含 `SNAPSHOT`。

使用私有 GitHub Packages 时，使用方需要具备仓库读取权限的令牌：

```kotlin
repositories {
  maven("https://maven.pkg.github.com/generalio/MultiWeb") {
    credentials {
      username = providers.gradleProperty("MULTIWEB_GITHUB_USER").orNull
      password = providers.gradleProperty("MULTIWEB_GITHUB_TOKEN").orNull
    }
  }
}
```

## 版本管理

1. 日常开发保持 `VERSION_NAME` 为下一个 `-SNAPSHOT` 版本。
2. 合并到 `main` 后确认构建校验工作流通过。
3. 创建并推送正式标签，例如：

```shell
git tag -a v0.2.0 -m "Release 0.2.0"
git push origin v0.2.0
```

4. 发布完成后，将 `VERSION_NAME` 更新为下一个开发版本并提交。

发布前可检查任务边界：

```shell
if ./gradlew :webview-test-fixtures:tasks --all | rg -q '(^|[[:space:]])publish[A-Za-z]'; then
  echo "错误：测试夹具存在 Maven 发布任务。" >&2
  exit 1
fi
./gradlew tasks --all | rg "publishAllPublicationsTo(GitHubPackages|MavenCentral)Repository"
```

第一段命令退出为 `0` 即表示测试夹具不存在 Maven 发布任务。Kotlin 可能显示
`export*PublicationCoordinates*` 元数据任务，它们不会发布或上传构件。第二条命令用于确认正式发布入口
仍可被 Gradle 发现。
