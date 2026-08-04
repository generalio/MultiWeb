# 发布说明

## 本地验证

在工程根目录执行：

```shell
./gradlew publishToMavenLocal
```

使用方加入 `mavenLocal()` 后，即可使用 `io.github.generalio.multiweb` 下的各模块坐标。当前版本由
`VERSION_NAME` 决定。

发布坐标已从 `io.github.multiweb` 迁移至 `io.github.generalio.multiweb`。旧坐标下已经发布的版本不会
被重命名，使用方升级到新版本时需要同步更新依赖声明。

## GitHub Packages 自动发布

仓库内的 [发布工作流](../.github/workflows/publish.yml) 会在推送 `vX.Y.Z` 标签时自动执行：

1. 在 macOS runner 上执行公共 API 校验并构建 Android、iOS 与桌面发布物。
2. 以去除 `v` 前缀后的标签值覆盖本次 Gradle 发布版本。
3. 使用 GitHub Actions 提供的短期令牌发布到当前仓库的 GitHub Packages。
4. 在发布成功后创建包含自动生成说明的 GitHub Release。

正式发布标签必须是 `v主版本.次版本.修订版本`，可追加预发布标识，例如 `v1.2.0-rc.1`；不允许使用
`SNAPSHOT`。项目当前远端仓库为 `generalio/MultiWeb`，使用方仓库地址为：

`https://maven.pkg.github.com/generalio/MultiWeb`

建议的版本管理流程：

1. 日常开发保持 `VERSION_NAME` 为下一版本的 `-SNAPSHOT`，例如 `0.1.1-SNAPSHOT`。
2. 合并到 `main` 后，确认“构建校验”工作流通过。
3. 创建并推送正式标签，例如：

```shell
git tag -a v0.1.0 -m "Release 0.1.0"
git push origin v0.1.0
```

4. GitHub Actions 发布完成后，将 `VERSION_NAME` 升级到下一个开发版本并提交，例如 `0.1.1-SNAPSHOT`。

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

支持使用 Basic Auth 的 Maven 仓库。发布环境提供仓库地址与认证信息后执行 `./gradlew publish`；认证信息
只能存放在本机 Gradle 用户目录或 CI 密钥配置中，不得进入此仓库。

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
