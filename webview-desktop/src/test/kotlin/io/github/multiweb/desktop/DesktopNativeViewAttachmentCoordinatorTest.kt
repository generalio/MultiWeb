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
    assertEquals(1, target.removeLayoutChangedListenerCount)
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
    assertEquals(1, target.removeLayoutChangedListenerCount)
  }

  @Test
  fun `原生视图尺寸有效后才创建浏览器`() {
    val target = FakeAttachmentTarget(
      isShowing = true,
      width = 0,
      height = 0,
    )
    var createCount = 0
    val coordinator = DesktopNativeViewAttachmentCoordinator(
      target = target,
      isControllerDisposed = { false },
      createBrowser = { createCount++ },
    )

    coordinator.registerShowingListener()
    coordinator.requestBrowserCreation()

    assertEquals(0, createCount)

    target.width = 100
    target.height = 100
    target.dispatchLayoutChanged()

    assertEquals(1, createCount)
    assertEquals(1, target.removeShowingListenerCount)
    assertEquals(1, target.removeLayoutChangedListenerCount)
  }

  @Test
  fun `实际可见区域有效后才创建浏览器`() {
    val target = FakeAttachmentTarget(
      isShowing = true,
      visibleWidth = 0,
      visibleHeight = 0,
    )
    var createCount = 0
    val coordinator = DesktopNativeViewAttachmentCoordinator(
      target = target,
      isControllerDisposed = { false },
      createBrowser = { createCount++ },
    )

    coordinator.registerShowingListener()
    coordinator.requestBrowserCreation()

    assertEquals(0, createCount)

    target.visibleWidth = 100
    target.visibleHeight = 100
    target.dispatchLayoutChanged()

    assertEquals(1, createCount)
    assertEquals(1, target.removeShowingListenerCount)
    assertEquals(1, target.removeLayoutChangedListenerCount)
  }
}

private class FakeAttachmentTarget(
  override var isDisplayable: Boolean = true,
  override var isShowing: Boolean,
  override var width: Int = 100,
  override var height: Int = 100,
  override var visibleWidth: Int = 100,
  override var visibleHeight: Int = 100,
) : DesktopNativeViewLayoutTarget {
  private val showingListeners = mutableSetOf<() -> Unit>()
  private val layoutChangedListeners = mutableSetOf<() -> Unit>()

  var removeShowingListenerCount = 0
    private set
  var removeLayoutChangedListenerCount = 0
    private set

  override fun addShowingListener(listener: () -> Unit) {
    showingListeners += listener
  }

  override fun removeShowingListener(listener: () -> Unit) {
    removeShowingListenerCount++
    showingListeners -= listener
  }

  override fun addLayoutChangedListener(listener: () -> Unit) {
    layoutChangedListeners += listener
  }

  override fun removeLayoutChangedListener(listener: () -> Unit) {
    removeLayoutChangedListenerCount++
    layoutChangedListeners -= listener
  }

  override fun revalidate() = Unit

  override fun synchronizeNativeViewSize() = Unit

  override fun paintImmediately() = Unit

  override fun repaint() = Unit

  fun dispatchShowingChanged() {
    showingListeners.toList().forEach { listener -> listener() }
  }

  fun dispatchLayoutChanged() {
    layoutChangedListeners.toList().forEach { listener -> listener() }
  }
}
