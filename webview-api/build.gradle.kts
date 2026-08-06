plugins {
  id("multiweb.kmp-library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      // 公共状态订阅 API 直接暴露 StateFlow，必须向库使用方传递该依赖。
      api(libs.kotlinx.coroutines.core)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
  }
}
