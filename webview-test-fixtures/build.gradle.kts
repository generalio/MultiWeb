plugins {
  // 测试夹具只参与工程内契约测试，不生成或上传任何 Maven 构件。
  id("multiweb.kmp-test-fixtures")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(project(":webview-api"))
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
  }
}
