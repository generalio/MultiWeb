package io.github.multiweb.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopWebViewRuntimeTest {

  @Test
  fun `macOS 且宿主未配置时启用混合互操作`() {
    var configuredValue: String? = null

    prepareMacosComposeInterop(
      operatingSystemName = "Mac OS X",
      currentBlendingValue = null,
      setBlendingValue = { configuredValue = it },
    )

    assertEquals("true", configuredValue)
  }

  @Test
  fun `不会覆盖宿主已配置的混合互操作值`() {
    var configuredValue: String? = null

    prepareMacosComposeInterop(
      operatingSystemName = "Mac OS X",
      currentBlendingValue = "false",
      setBlendingValue = { configuredValue = it },
    )

    assertNull(configuredValue)
  }

  @Test
  fun `非 macOS 不配置混合互操作`() {
    var configuredValue: String? = null

    prepareMacosComposeInterop(
      operatingSystemName = "Linux",
      currentBlendingValue = null,
      setBlendingValue = { configuredValue = it },
    )

    assertNull(configuredValue)
  }

  @Test
  fun `macOS 终止处理器会拦截默认退出并转交 Runtime`() {
    val scheduledRequests = mutableListOf<() -> Unit>()
    var requestCount = 0

    val handler = createMacOsTerminationHandler(
      operatingSystemName = "Mac OS X",
      scheduleOnEdt = { action -> scheduledRequests += action },
      requestApplicationExit = { requestCount++ },
    )

    assertTrue(handler.onBeforeTerminate())
    scheduledRequests.single().invoke()
    assertEquals(1, requestCount)
  }

  @Test
  fun `非 macOS 终止处理器保持 JCEF 默认退出行为`() {
    var requestCount = 0

    val handler = createMacOsTerminationHandler(
      operatingSystemName = "Linux",
      scheduleOnEdt = { action -> action() },
      requestApplicationExit = { requestCount++ },
    )

    assertFalse(handler.onBeforeTerminate())
    assertEquals(0, requestCount)
  }

  @Test
  fun `等待所有控制器准确关闭后才销毁 CEF`() {
    val cefApplication = FakeDesktopCefApplication()
    val disposedControllers = mutableListOf<String>()
    val pollers = mutableListOf<FakeDesktopTerminationPoller>()
    var applicationExitCount = 0
    val coordinator = DesktopApplicationExitCoordinator(
      cefApplication = cefApplication,
      disposeController = disposedControllers::add,
      createTerminationPoller = { onTick ->
        FakeDesktopTerminationPoller(onTick).also(pollers::add)
      },
    )
    coordinator.bindApplicationExit { applicationExitCount++ }
    coordinator.register("first")
    coordinator.register("second")

    coordinator.requestApplicationExit()
    assertEquals(setOf("first", "second"), disposedControllers.toSet())
    assertEquals(0, cefApplication.disposeCount)

    coordinator.onControllerClosed("second")
    assertEquals(0, cefApplication.disposeCount)

    coordinator.onControllerClosed("first")
    assertEquals(1, cefApplication.disposeCount)
    assertEquals(1, pollers.single().startCount)
    assertEquals(0, applicationExitCount)

    cefApplication.state = org.cef.CefApp.CefAppState.TERMINATED
    pollers.single().dispatchTick()

    assertEquals(1, pollers.single().stopCount)
    assertEquals(1, applicationExitCount)
  }

  @Test
  fun `重复退出请求不会重复关闭控制器或 CEF`() {
    val cefApplication = FakeDesktopCefApplication(
      state = org.cef.CefApp.CefAppState.TERMINATED,
    )
    val disposedControllers = mutableListOf<String>()
    var applicationExitCount = 0
    val coordinator = DesktopApplicationExitCoordinator(
      cefApplication = cefApplication,
      disposeController = disposedControllers::add,
      createTerminationPoller = { error("已终止的 CEF 不应启动轮询") },
    )
    coordinator.bindApplicationExit { applicationExitCount++ }
    coordinator.register("controller")

    coordinator.requestApplicationExit()
    coordinator.requestApplicationExit()
    coordinator.onControllerClosed("controller")
    coordinator.onControllerClosed("controller")

    assertEquals(listOf("controller"), disposedControllers)
    assertEquals(1, cefApplication.disposeCount)
    assertEquals(1, applicationExitCount)
  }

  @Test
  fun `Compose 已释放控制器后仍等待原生关闭确认`() {
    val cefApplication = FakeDesktopCefApplication(
      state = org.cef.CefApp.CefAppState.TERMINATED,
    )
    val disposedControllers = mutableListOf<String>()
    val coordinator = DesktopApplicationExitCoordinator(
      cefApplication = cefApplication,
      disposeController = disposedControllers::add,
      createTerminationPoller = { error("已终止的 CEF 不应启动轮询") },
    )
    coordinator.bindApplicationExit {}
    coordinator.register("controller")

    coordinator.unregister("controller")
    coordinator.requestApplicationExit()

    assertEquals(0, cefApplication.disposeCount)
    assertTrue(disposedControllers.isEmpty())

    coordinator.onControllerClosed("controller")

    assertEquals(1, cefApplication.disposeCount)
  }

  @Test
  fun `CEF 不可用时停止终止轮询且不伪造应用退出`() {
    val cefApplication = FakeDesktopCefApplication()
    val pollers = mutableListOf<FakeDesktopTerminationPoller>()
    var applicationExitCount = 0
    val coordinator = DesktopApplicationExitCoordinator<String>(
      cefApplication = cefApplication,
      disposeController = {},
      createTerminationPoller = { onTick ->
        FakeDesktopTerminationPoller(onTick).also(pollers::add)
      },
    )
    coordinator.bindApplicationExit { applicationExitCount++ }

    coordinator.requestApplicationExit()
    assertEquals(1, pollers.single().startCount)

    cefApplication.state = org.cef.CefApp.CefAppState.INITIALIZATION_FAILED
    pollers.single().dispatchTick()

    assertEquals(1, pollers.single().stopCount)
    assertEquals(0, applicationExitCount)
  }
}

private class FakeDesktopCefApplication(
  override var state: org.cef.CefApp.CefAppState = org.cef.CefApp.CefAppState.INITIALIZED,
) : DesktopCefApplication {
  var disposeCount = 0
    private set

  override fun dispose() {
    disposeCount++
  }
}

private class FakeDesktopTerminationPoller(
  private val onTick: () -> Unit,
) : DesktopTerminationPoller {
  var startCount = 0
    private set
  var stopCount = 0
    private set

  override fun start() {
    startCount++
  }

  override fun stop() {
    stopCount++
  }

  fun dispatchTick() {
    onTick()
  }
}
