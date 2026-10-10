# MW-20260818-005 固定候选审查报告

候选 SHA：3ba4d6d41c327650ace3555aebc832cce1e71275
裁决：PASS

## 已验证

- 审查前后 `git status --short`：退出 `0`，干净 worktree 未变更。
- `git rev-parse HEAD`：退出 `0`，等于固定候选 SHA。
- 基线与候选祖先关系、候选与审查 HEAD 祖先关系：退出 `0`。
- `git diff --check 4320a062d641c84c9ed5ec67e1af09551f62d430 3ba4d6d41c327650ace3555aebc832cce1e71275`：退出 `0`。
- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest tools.tests.test_agent_workflow -v`：退出 `0`，48 项通过。
- `./gradlew apiCheck :webview-android:assembleRelease :webview-android:compileDebugAndroidTestKotlin :webview-desktop:check :webview-ios:compileTestKotlinIosSimulatorArm64 :sample-compose:compileKotlinJs :sample-compose:compileKotlinWasmJs --no-parallel -Dorg.gradle.jvmargs=-Xmx2g`：退出 `0`，全部成功。

## 审查结论

- 公共 API：`ExactHttpsHosts` 保持构造、`copy`、`component1` 与等值语义，并收紧非法主机输入。
- Android：门面与原生消息入口均限制顶层框架。
- iOS/Desktop：空允许主机集合在可信 JS URL 入口显式拒绝。
- JS/Wasm：共享 KMP 策略随 JS/Wasm 编译通过。
- 安全默认值：未发现 JS 桥来源边界绕过，也未放宽 Cookie、文件访问、下载或外部导航权限。

## 风险

无 P0。无 P1。

P2：未执行 Android System WebView instrumentation runtime、WKWebView runtime、JS/Wasm 浏览器 runtime；替代证据为 Android instrumentation Kotlin 编译、iOS Simulator 测试 Kotlin 编译与 JS/Wasm 编译通过。Android 回归测试位于 `webview-android/src/androidTest/kotlin/io/github/multiweb/android/AndroidScriptBridgeInstrumentationTest.kt`。
