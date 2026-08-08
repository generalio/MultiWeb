package io.github.multiweb.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopNativeViewAttachmentCoordinatorTest {

  @Test
  fun `创建浏览器会等待原生视图进入 showing 状态`() {
    val target = FakeAttachmentTarget(isShowing = false)
    val created = mutableListOf<String>()
    val coordinator = DesktopNativeViewAttachmentCoordinator(
      target = target,
      isControllerDisposed = { false },
      createBrowser = { created += "browser" },
    )

    coordinator.registerShowingListener()
    coordinator.requestBrowserCreation()

    assertEquals(emptyList(), created)

    target.isShowing = true
    target.dispatchShowingChanged()

    assertEquals(listOf("browser"), created)
    assertEquals(1, target.removeShowingListenerCount)
  }

  @Test
  fun `已显示的视图只会创建一次浏览器`() {
    val target = FakeAttachmentTarget(isShowing = true)
    var createCount = 0
    val coordinator = DesktopNativeViewAttachmentCoordinator(
      target = target,
      isControllerDisposed = { false },
      createBrowser = { createCount++ },
    )

    coordinator.registerShowingListener()
    coordinator.requestBrowserCreation()
    target.dispatchShowingChanged()
    coordinator.requestBrowserCreation()

    assertEquals(1, createCount)
  }

  @Test
  fun `控制器销毁后不会创建浏览器`() {
    val target = FakeAttachmentTarget(isShowing = false)
    var createCount = 0
    val coordinator = DesktopNativeViewAttachmentCoordinator(
      target = target,
      isControllerDisposed = { false },
      createBrowser = { createCount++ },
    )

    coordinator.registerShowingListener()
    coordinator.dispose()
    target.isShowing = true
    target.dispatchShowingChanged()
    coordinator.requestBrowserCreation()

    assertEquals(0, createCount)
    assertEquals(1, target.removeShowingListenerCount)
  }
}

private class FakeAttachmentTarget(
  override var isDisplayable: Boolean = true,
  override var isShowing: Boolean,
) : DesktopNativeViewLayoutTarget {
  private val showingListeners = mutableSetOf<() -> Unit>()

  var removeShowingListenerCount = 0
    private set

  override val width: Int = 100
  override val height: Int = 100

  override fun addShowingListener(listener: () -> Unit) {
    showingListeners += listener
  }

  override fun removeShowingListener(listener: () -> Unit) {
    removeShowingListenerCount++
    showingListeners -= listener
  }

  override fun addLayoutChangedListener(listener: () -> Unit) = Unit

  override fun removeLayoutChangedListener(listener: () -> Unit) = Unit

  override fun revalidate() = Unit

  override fun paintImmediately() = Unit

  override fun repaint() = Unit

  fun dispatchShowingChanged() {
    showingListeners.toList().forEach { listener -> listener() }
  }
}
