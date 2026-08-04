# 贡献指南

## 开始前

1. Fork 本仓库并从 `main` 创建主题分支，例如 `fix/android-navigation` 或 `feat/desktop-download`。
2. 阅读 [架构说明](architecture.md)、[开发指南](development.md) 和根目录 `AGENTS.md`。
3. 先搜索现有 Issue 和代码，避免重复实现或将平台细节加入公共模块。

## 二次开发原则

- 优先使用 `WebViewExtension`、`ScriptBridge` 与 Android 的 `AndroidWebViewFactory` 扩展行为；不要直接修改
  平台控制器来实现单个业务项目的需求。
- 跨平台语义稳定、至少两个平台能一致实现时，才考虑在 `webview-api` 或 `webview-extension-api` 新增 API。
- 平台独有功能保留在平台模块，并明确不支持的平台和降级行为。
- 所有新增公共字段、枚举项和关键安全逻辑必须写中文 KDoc，说明默认值、边界与兼容性影响。
- 不要降低默认安全配置，也不要向未受信任网页暴露原生 JS 桥。

## 修改要求

- 使用空格缩进：Tab size 2、Indent 2、Continuation indent 4。
- 一个 PR 聚焦一个问题。避免混入版本升级、格式化或不相关重构。
- 缺陷修复应先添加会失败的回归测试，再提交修复。新增公共行为要补充契约测试。
- 公共 API 有变更时运行 `./gradlew apiCheck`；不要直接修改 API 基线来掩盖兼容性变化。
- 不提交 `build/`、`.gradle/`、JCEF 本地运行时、IDE 工作区、Token、GPG 私钥或本机 `local.properties`。
- 不要让 `webview-test-fixtures` 应用发布插件或生成 publication；它永远不属于发布产物。

## 提交信息

提交信息使用简短的 Conventional Commit 风格，并用中文或英文清晰说明范围：

```text
fix(android): 修复重定向导航策略
feat(extension): 增加受限下载事件
docs: 补充 iOS 接入说明
```

每个已完成且验证通过的阶段单独提交。提交前至少执行：

```shell
git diff --check
git status --short
```

## Pull Request

PR 描述应包含以下信息：

```md
## 目的

## 改动内容

## 平台影响
- Android：
- iOS：
- Desktop：
- JS/Wasm：

## 验证命令与结果

## 风险与兼容性
```

若改动涉及安全策略、JS 桥、Cookie、文件访问或外部导航，必须补充默认行为、受影响平台与安全评估。
若无法在本机运行某项真实运行时测试，应在 PR 中说明原因和已执行的替代验证。

## 审查标准

维护者会重点检查模块依赖方向、生命周期释放、线程约束、平台降级、安全默认值、公共 API 兼容性和测试覆盖。
文档、代码注释与实际行为不一致时，PR 不应合并。
