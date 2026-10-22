# MW-20261022-webview-js

FULL；需求来源：用户授权Android旧WebView兼容、发布MultiWeb0.2.12并更新掌邮。Planner /root/webview_planner 已完成规划；基线 849bd8c18632fe42766d9735ad23c816e2d02694。账本由Supervisor独占。严格 Planner→单一Implementer→Integrator→Verify-Reviewer，不批准第二Implementer，不生成handoffs。

## 目标与实现约束
JavaScript关闭或bridges为空：真实install零feature检查；普通无facade消息桥不检查DOCUMENT_START_SCRIPT。有桥且WEB_MESSAGE_LISTENER不支持：禁用桥保持浏览，不throw、不用addJavascriptInterface。有facade且document-start支持维持原行为；不支持时通过控制器页面回调evaluateJavascript降级，推荐onPageStarted尽力+onPageFinished补注入。只处理设备能力缺失，配置错误仍fail-fast。
脚本必须检查实际location的协议/host/默认HTTPS端口以及top===window，不能依赖旧回调URL。Unsafe策略仍仅带host的HTTP/HTTPS顶层文档。重复注入幂等，不重绑onmessage/重置pending；保留native sourceOrigin、isMainFrame与方法allowlist验证。无新公共API，不扩大安全默认值。
降级不保证早于首段网页脚本；evaluateJavascript异步，不得承诺其先于扩展onPageFinished完成，也不能承诺旧onLoad一定赶上回调。文档明确需要等待/重试。

## 独占范围与依赖
Implementer独占：webview-android/src/main/kotlin/io/github/multiweb/android/AndroidScriptBridgeInstaller.kt、AndroidWebViewController.kt；必要时包内internal辅助文件；webview-android/src/test和src/androidTest内相关桥配置/安装/兼容测试；docs/使用指南.md仅Android降级说明。禁止webview-api、webview-extension-api、公共API基线、iOS/Desktop/Browser/Compose产品、CI、发布配置与无关文件。
Supervisor独占：账本、Git版本/发布操作；CyxbsMobile-webview-js/gradle/libs.versions.toml仅multiweb依赖0.2.12。掌邮基线a31b5012eb6d74be434861a47ab6c34c4d52a84b来自origin/develop、分支zzx/bugfix/webview_js；MultiWeb无develop，基于origin/main同名分支。不得改掌邮桥协议和路由。
Integrator仅将Supervisor转交的授权diff机械应用到专用集成worktree，语义冲突退回，记录diff --check，固定候选SHA。Reviewer只在干净固定SHA worktree验证，不修改代码；报告候选、命令退出结果、未测范围、P0/P1/P2及唯一PASS/REJECT，无未解决P0/P1才能提交/推送/发布。候选变动旧裁决失效。

## 验证
先补真实install失败测试记录红测，再修复并跑绿测。通过internal capability checker强制缺失能力；不得只测布尔函数。覆盖：无bridge/JS禁用零查询；无facade不查document-start；消息通道缺失不抛且浏览正常；document-start缺失时真实WebView桥往返；精确host/HTTPS默认端口、Unsafe HTTP(S)、iframe/file/data拒绝；实际location与回调不同；导航/重载/重复回调不破坏pending Promise；现有document-start回归。
命令：./gradlew :webview-android:testDebugUnitTest --no-configuration-cache；./gradlew :webview-android:connectedDebugAndroidTest --no-configuration-cache；./gradlew apiCheck :webview-android:assembleRelease :webview-android:compileDebugAndroidTestKotlin --no-configuration-cache；git diff --check。Reviewer重复最小unit、instrumentation、apiCheck/assemble。每阶段最多两次。
- 公共 API：不受影响；验证证据：apiCheck 退出 0。
- Android：受影响；验证证据：unit 23/23、instrumentation 18/18、assembleRelease 和 compileDebugAndroidTestKotlin 均通过。
- iOS：不受影响；验证证据：未修改 iOS 源码，发布工作流目标保持不变。
- Desktop：不受影响；验证证据：未修改 Desktop 源码，既有 desktop check 目标保持不变。
- JS/Wasm：不受影响；验证证据：未修改 JS/Wasm 源码，既有发布构建目标保持不变。
安全默认值影响：不放宽来源、消息通道或 JavaScript 接口权限。
P2：旧内核延后注入不保证首段脚本时序，已在文档与审查报告记录。

## 发布与接入
用户已授权升级发布。沿既有main合并/校验后v0.2.12标签工作流发布，不绕门禁，不手改CI。VERSION_NAME保留0.2.12-SNAPSHOT，tag派生正式版本。确认Central实际上传非跳过且公开POM/module与Android制品可读取。再将掌邮multiweb升级0.2.12，以公开远程仓库解析编译Android base/app，不能只用mavenLocal证明完成。自动推进下一开发版本由现有CI处理。
