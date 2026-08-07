package io.github.multiweb.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopInitialNativeViewLayoutCoordinatorTest {

  @Test
  fun `视图尚未 showing 时不会立即同步`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = false)
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target) { false }

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()

    assertEquals(0, target.revalidateCount)
    assertEquals(0, target.repaintCount)
  }

  @Test
  fun `收到 showing 事件后只同步一次`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = false)
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target) { false }

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    target.isShowing = true
    target.dispatchShowingChanged()
    target.dispatchShowingChanged()

    assertEquals(1, target.revalidateCount)
    assertEquals(1, target.repaintCount)
  }

  @Test
  fun `销毁后不再同步且移除监听`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = true)
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target) { false }

    coordinator.registerShowingListener()
    coordinator.dispose()
    coordinator.requestInitialNativeViewLayout()
    target.dispatchShowingChanged()

    assertEquals(1, target.removeListenerCount)
    assertEquals(0, target.revalidateCount)
    assertEquals(0, target.repaintCount)
  }

  @Test
  fun `控制器已销毁时不执行同步`() {
    val isControllerDisposed = true
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = true)
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target) { isControllerDisposed }

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    target.dispatchShowingChanged()

    assertEquals(0, target.addListenerCount)
    assertEquals(0, target.revalidateCount)
    assertEquals(0, target.repaintCount)
  }

  @Test
  fun `多次注册不会重复添加 showing 监听`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = true)
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target) { false }

    coordinator.registerShowingListener()
    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    target.dispatchShowingChanged()

    assertEquals(1, target.addListenerCount)
    assertEquals(1, target.revalidateCount)
    assertEquals(1, target.repaintCount)
  }
}

private class FakeDesktopNativeViewLayoutTarget(
  override var isDisplayable: Boolean = true,
  override var isShowing: Boolean,
  override var width: Int = 100,
  override var height: Int = 100,
) : DesktopNativeViewLayoutTarget {
  private val showingListeners = mutableSetOf<() -> Unit>()

  var addListenerCount = 0
    private set
  var removeListenerCount = 0
    private set
  var revalidateCount = 0
    private set
  var repaintCount = 0
    private set

  override fun addShowingListener(listener: () -> Unit) {
    addListenerCount++
    showingListeners += listener
  }

  override fun removeShowingListener(listener: () -> Unit) {
    removeListenerCount++
    showingListeners -= listener
  }

  override fun revalidate() {
    revalidateCount++
  }

  override fun repaint() {
    repaintCount++
  }

  fun dispatchShowingChanged() {
    showingListeners.toList().forEach { listener -> listener() }
  }
}
