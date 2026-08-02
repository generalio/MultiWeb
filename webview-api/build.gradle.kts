plugins {
  id("multiweb.kmp-library")
}

kotlin {
  sourceSets {
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
  }
}

