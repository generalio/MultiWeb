package io.github.multiweb.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopInitialNativeViewLayoutCoordinatorTest {

  @Test
  fun `视图尚未 showing 时不会立即同步`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = false)
    val coordinator = createCoordinator(target)

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()

    assertSynchronizationPerformed(target, expectedCount = 0)
  }

  @Test
  fun `可见裁剪区域无效时不会提前同步`() {
    val target = FakeDesktopNativeViewLayoutTarget(
      isShowing = true,
      visibleWidth = 0,
      visibleHeight = 0,
    )
    val coordinator = createCoordinator(target)

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()

    assertSynchronizationPerformed(target, expectedCount = 0)
  }

  @Test
  fun `收到 showing 事件后会开始稳定同步`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = false)
    val delayedRetries = mutableListOf<() -> Unit>()
    val coordinator = createCoordinator(target) { action -> delayedRetries += action }

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    target.isShowing = true
    target.dispatchShowingChanged()
    delayedRetries.removeFirst().invoke()

    assertSynchronizationPerformed(target, expectedCount = 2)
  }

  @Test
  fun `可见裁剪区域稳定后会执行两次同步`() {
    val target = FakeDesktopNativeViewLayoutTarget(
      isShowing = true,
      visibleWidth = 0,
      visibleHeight = 0,
    )
    val delayedRetries = mutableListOf<() -> Unit>()
    val coordinator = createCoordinator(target) { action -> delayedRetries += action }

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    target.visibleWidth = 100
    target.visibleHeight = 100
    delayedRetries.removeFirst().invoke()

    assertSynchronizationPerformed(target, expectedCount = 1)
    assertEquals(1, delayedRetries.size)
    assertEquals(0, target.removeShowingListenerCount)
    assertEquals(0, target.removeLayoutChangedListenerCount)

    delayedRetries.removeFirst().invoke()

    assertSynchronizationPerformed(target, expectedCount = 2)
    assertEquals(1, target.removeShowingListenerCount)
    assertEquals(1, target.removeLayoutChangedListenerCount)
  }

  @Test
  fun `JCEF 就绪后会安排第二次稳定布局同步`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = true)
    val delayedRetries = mutableListOf<() -> Unit>()
    val coordinator = createCoordinator(target) { action -> delayedRetries += action }

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()

    assertSynchronizationPerformed(target, expectedCount = 1)
    assertEquals(1, delayedRetries.size)

    delayedRetries.removeFirst().invoke()

    assertSynchronizationPerformed(target, expectedCount = 2)
    assertEquals(1, target.removeShowingListenerCount)
    assertEquals(1, target.removeLayoutChangedListenerCount)
  }

  @Test
  fun `真实布局事件可在延迟重试前完成稳定同步`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = true)
    val delayedRetries = mutableListOf<() -> Unit>()
    val coordinator = createCoordinator(target) { action -> delayedRetries += action }

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    target.dispatchLayoutChanged()
    delayedRetries.single().invoke()

    assertSynchronizationPerformed(target, expectedCount = 2)
    assertEquals(1, target.removeShowingListenerCount)
    assertEquals(1, target.removeLayoutChangedListenerCount)
  }

  @Test
  fun `同步完成后多次 resize 不会重复绘制`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = true)
    val delayedRetries = mutableListOf<() -> Unit>()
    val coordinator = createCoordinator(target) { action -> delayedRetries += action }

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    delayedRetries.removeFirst().invoke()
    target.dispatchLayoutChanged()
    target.dispatchLayoutChanged()

    assertSynchronizationPerformed(target, expectedCount = 2)
  }

  @Test
  fun `销毁后不再执行已排队的稳定同步`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = true)
    val delayedRetries = mutableListOf<() -> Unit>()
    val coordinator = createCoordinator(target) { action -> delayedRetries += action }

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    coordinator.dispose()
    delayedRetries.single().invoke()

    assertSynchronizationPerformed(target, expectedCount = 1)
    assertEquals(1, target.removeShowingListenerCount)
    assertEquals(1, target.removeLayoutChangedListenerCount)
  }

  @Test
  fun `控制器已销毁时不执行同步`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = true)
    val coordinator = createCoordinator(target, isControllerDisposed = { true })

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    target.dispatchShowingChanged()

    assertEquals(0, target.addShowingListenerCount)
    assertEquals(0, target.addLayoutChangedListenerCount)
    assertSynchronizationPerformed(target, expectedCount = 0)
  }
}

private fun createCoordinator(
  target: FakeDesktopNativeViewLayoutTarget,
  isControllerDisposed: () -> Boolean = { false },
  scheduleDelayedRetry: ((() -> Unit) -> Unit) = {},
): DesktopInitialNativeViewLayoutCoordinator {
  return DesktopInitialNativeViewLayoutCoordinator(
    target = target,
    isControllerDisposed = isControllerDisposed,
    scheduleRetry = scheduleDelayedRetry,
  )
}

private fun assertSynchronizationPerformed(
  target: FakeDesktopNativeViewLayoutTarget,
  expectedCount: Int,
) {
  assertEquals(expectedCount, target.revalidateCount)
  assertEquals(expectedCount, target.nativeViewSizeSynchronizationCount)
  assertEquals(expectedCount, target.immediatePaintCount)
  assertEquals(expectedCount, target.repaintCount)
  assertEquals(
    List(expectedCount) {
      listOf("revalidate", "synchronizeNativeViewSize", "paintImmediately", "repaint")
    }.flatten(),
    target.synchronizationOperations,
  )
}

private class FakeDesktopNativeViewLayoutTarget(
  override var isDisplayable: Boolean = true,
  override var isShowing: Boolean,
  override var width: Int = 100,
  override var height: Int = 100,
  override var visibleWidth: Int = 100,
  override var visibleHeight: Int = 100,
) : DesktopNativeViewLayoutTarget {
  private val showingListeners = mutableSetOf<() -> Unit>()
  private val layoutChangedListeners = mutableSetOf<() -> Unit>()

  var addShowingListenerCount = 0
    private set
  var removeShowingListenerCount = 0
    private set
  var addLayoutChangedListenerCount = 0
    private set
  var removeLayoutChangedListenerCount = 0
    private set
  var revalidateCount = 0
    private set
  var nativeViewSizeSynchronizationCount = 0
    private set
  var immediatePaintCount = 0
    private set
  var repaintCount = 0
    private set
  val synchronizationOperations = mutableListOf<String>()

  override fun addShowingListener(listener: () -> Unit) {
    addShowingListenerCount++
    showingListeners += listener
  }

  override fun removeShowingListener(listener: () -> Unit) {
    removeShowingListenerCount++
    showingListeners -= listener
  }

  override fun addLayoutChangedListener(listener: () -> Unit) {
    addLayoutChangedListenerCount++
    layoutChangedListeners += listener
  }

  override fun removeLayoutChangedListener(listener: () -> Unit) {
    removeLayoutChangedListenerCount++
    layoutChangedListeners -= listener
  }

  override fun revalidate() {
    revalidateCount++
    synchronizationOperations += "revalidate"
  }

  override fun synchronizeNativeViewSize() {
    nativeViewSizeSynchronizationCount++
    synchronizationOperations += "synchronizeNativeViewSize"
  }

  override fun paintImmediately() {
    immediatePaintCount++
    synchronizationOperations += "paintImmediately"
  }

  override fun repaint() {
    repaintCount++
    synchronizationOperations += "repaint"
  }

  fun dispatchShowingChanged() {
    showingListeners.toList().forEach { listener -> listener() }
  }

  fun dispatchLayoutChanged() {
    layoutChangedListeners.toList().forEach { listener -> listener() }
  }
}
