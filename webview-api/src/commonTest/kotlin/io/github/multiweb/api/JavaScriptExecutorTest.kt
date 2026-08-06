package io.github.multiweb.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaScriptExecutorTest {
  @Test
  fun 执行器接收脚本和精确可信主机集合() {
    val executor = RecordingJavaScriptExecutor()
    val allowedHosts = setOf("app.example.com")

    val submitted = executor.executeJavaScript(
      script = "window.__multiweb_test = true",
      allowedHosts = allowedHosts,
    )

    assertTrue(submitted)
    assertEquals("window.__multiweb_test = true", executor.lastScript)
    assertEquals(allowedHosts, executor.lastAllowedHosts)
  }

  private class RecordingJavaScriptExecutor : JavaScriptExecutor {
    var lastScript: String? = null
    var lastAllowedHosts: Set<String>? = null

    override fun executeJavaScript(script: String, allowedHosts: Set<String>): Boolean {
      lastScript = script
      lastAllowedHosts = allowedHosts
      return true
    }
  }
}
