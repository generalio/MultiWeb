# MultiWeb 工程约束

## 格式

- Kotlin 与 Gradle Kotlin DSL 统一使用空格缩进：Tab size 2、Indent 2、Continuation indent 4。
- 所有改动必须遵循 `.editorconfig`；提交前不得混入无关格式化。

## 架构

- `webview-api` 只能包含跨平台模型、策略和接口，禁止引用 Android、UIKit、WebKit、JCEF 或 Compose 类型。
- 平台实现必须位于独立模块；任何跨平台可观察行为变更都要补充或更新公共层契约测试。
- 安全相关能力保持最小权限默认值。新增放宽权限的开关时，必须写明默认值、作用平台与安全影响。
- `webview-desktop` 的 JCEF 应用实例属于进程级资源，只能由宿主初始化和销毁；控制器只能释放自身的浏览器与客户端。
- JCEF 无法按单个浏览器可靠控制的安全配置必须显式拒绝，禁止静默降级为运行时默认行为。
- JS/Wasm 平台只能以浏览器新窗口处理 URL；不得将浏览器全局 Cookie、缓存或 JavaScript 权限伪装为组件可控能力。

## 注释与文档

- 关键业务代码、公共 API、枚举项以及公共数据类字段必须使用中文 KDoc 或行注释说明其职责、语义和约束。
- 注释应说明设计原因、边界条件或调用方需要关注的信息；禁止添加与代码字面含义重复的无效注释。
- 修改既有关键代码或公共 API 时，同步检查并更新其中文注释。新增英文注释前必须确认其为外部术语、协议名称或原文引用等必要场景。

## 测试与缺陷流程

- 每次改动后运行最小相关验证；验证命令和结果必须记录在交付说明中。
- 发现缺陷时，先增加可复现的失败测试，再修复并运行该测试与受影响回归测试。测试仍失败时继续定位和修复，不得以跳过测试作为完成标准。
- 公共 API 变更必须运行 `apiCheck`；修改 API 基线只能在确认兼容性策略后进行。
- `webview-test-fixtures` 仅供工程内测试和示例使用，禁止应用发布约定插件、生成 Maven publication 或上传到任何远程仓库。
- 新增或修改 KMP target 时，必须分别编译 Android、iOS、JVM、JS 与 Wasm，并记录无法执行的运行时测试范围。
- Compose 示例的公共操作逻辑必须与原生视图嵌入代码分离，并使用 `webview-test-fixtures` 的
  `FakeWebViewController` 编写非设备测试；Android、iOS 与 JCEF 的真实运行时测试不可由该替身代替。

## 提交

- 每个完成且已验证的改动阶段都必须创建一个单独 Git commit。
- 提交前检查 `git status` 与 `git diff --check`，不得提交构建产物、凭据或无关文件。
- 创建或更新 PR 前必须完成一次 MultiWeb 代码审查，复查最终 diff、公共 API 兼容性、跨平台一致性、JS 桥安全边界与受影响测试；存在未解决的 P0/P1 问题时禁止提交或推送。

## 发布

- 发布凭据只能从 Gradle 属性或环境变量读取，禁止写入 `gradle.properties`、源码、文档示例或 Git 历史。
- POM 的仓库地址、许可证和开发者信息未确认时必须参数化配置，禁止填入推测值；发布前由仓库所有者提供实际信息。
- Maven Central 正式发布必须经过已验证的 Central Portal 命名空间与 GPG 签名；普通构建和 GitHub Packages 发布不得隐式触发 Central 上传。

## GitHub Actions

- Pull Request 与主分支工作流必须至少运行公共 API 校验和受影响平台的构建验证。
- GitHub Packages 发布仅可由 `vX.Y.Z` 形式的标签触发；工作流发布版本必须由标签派生，禁止使用 `-SNAPSHOT` 版本。
- 发布工作流必须显式声明最小权限，并只使用 GitHub Actions 提供的短期 `GITHUB_TOKEN`。
