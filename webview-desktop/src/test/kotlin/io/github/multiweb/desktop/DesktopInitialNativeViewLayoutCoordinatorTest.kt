package io.github.multiweb.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopInitialNativeViewLayoutCoordinatorTest {

  @Test
  fun `视图尚未 showing 时不会立即同步`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = false)
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target, { false })

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()

    assertEquals(0, target.revalidateCount)
    assertEquals(0, target.immediatePaintCount)
    assertEquals(0, target.repaintCount)
  }

  @Test
  fun `收到 showing 事件后按同步顺序绘制一次`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = false)
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target, { false })

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    target.isShowing = true
    target.dispatchShowingChanged()
    target.dispatchShowingChanged()

    assertEquals(1, target.revalidateCount)
    assertEquals(1, target.immediatePaintCount)
    assertEquals(1, target.repaintCount)
    assertEquals(
      listOf("revalidate", "paintImmediately", "repaint"),
      target.synchronizationOperations,
    )
  }

  @Test
  fun `尺寸无效时不会同步绘制`() {
    val target = FakeDesktopNativeViewLayoutTarget(
      isShowing = true,
      width = 0,
    )
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target, { false })

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()

    assertEquals(0, target.revalidateCount)
    assertEquals(0, target.immediatePaintCount)
    assertEquals(0, target.repaintCount)
  }

  @Test
  fun `首次尺寸变为有效后会同步绘制一次`() {
    val target = FakeDesktopNativeViewLayoutTarget(
      isShowing = true,
      width = 0,
      height = 0,
    )
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target, { false })

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    target.width = 100
    target.height = 100
    target.dispatchLayoutChanged()

    assertEquals(1, target.revalidateCount)
    assertEquals(1, target.immediatePaintCount)
    assertEquals(1, target.repaintCount)
    assertEquals(
      listOf("revalidate", "paintImmediately", "repaint"),
      target.synchronizationOperations,
    )
  }

  @Test
  fun `同一事件循环内尺寸变为有效时延迟重试会同步绘制`() {
    val target = FakeDesktopNativeViewLayoutTarget(
      isShowing = true,
      width = 0,
      height = 0,
    )
    val delayedRetries = mutableListOf<() -> Unit>()
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(
      target,
      { false },
      { action -> delayedRetries += action },
    )

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    target.width = 100
    target.height = 100
    delayedRetries.single().invoke()

    assertSynchronizationPerformedOnce(target)
  }

  @Test
  fun `Swing 先 showing 后 JCEF 就绪时会同步绘制一次`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = true)
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target, { false })

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()

    assertSynchronizationPerformedOnce(target)
  }

  @Test
  fun `初始同步完成后多次 resize 不会重复绘制`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = true)
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target, { false })

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    target.dispatchLayoutChanged()
    target.dispatchLayoutChanged()

    assertSynchronizationPerformedOnce(target)
    assertEquals(1, target.removeShowingListenerCount)
    assertEquals(1, target.removeLayoutChangedListenerCount)
  }

  @Test
  fun `销毁后不再同步且移除监听`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = true)
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target, { false })

    coordinator.registerShowingListener()
    coordinator.dispose()
    coordinator.requestInitialNativeViewLayout()
    target.dispatchShowingChanged()

    assertEquals(1, target.removeShowingListenerCount)
    assertEquals(1, target.removeLayoutChangedListenerCount)
    assertEquals(0, target.revalidateCount)
    assertEquals(0, target.immediatePaintCount)
    assertEquals(0, target.repaintCount)
  }

  @Test
  fun `控制器已销毁时不执行同步`() {
    val isControllerDisposed = true
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = true)
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target, { isControllerDisposed })

    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    target.dispatchShowingChanged()

    assertEquals(0, target.addListenerCount)
    assertEquals(0, target.addLayoutChangedListenerCount)
    assertEquals(0, target.revalidateCount)
    assertEquals(0, target.immediatePaintCount)
    assertEquals(0, target.repaintCount)
  }

  @Test
  fun `多次注册不会重复添加 showing 监听`() {
    val target = FakeDesktopNativeViewLayoutTarget(isShowing = true)
    val coordinator = DesktopInitialNativeViewLayoutCoordinator(target, { false })

    coordinator.registerShowingListener()
    coordinator.registerShowingListener()
    coordinator.requestInitialNativeViewLayout()
    target.dispatchShowingChanged()

    assertEquals(1, target.addListenerCount)
    assertEquals(1, target.addLayoutChangedListenerCount)
    assertEquals(1, target.revalidateCount)
    assertEquals(1, target.immediatePaintCount)
    assertEquals(1, target.repaintCount)
  }
}

private fun assertSynchronizationPerformedOnce(target: FakeDesktopNativeViewLayoutTarget) {
  assertEquals(1, target.revalidateCount)
  assertEquals(1, target.immediatePaintCount)
  assertEquals(1, target.repaintCount)
  assertEquals(
    listOf("revalidate", "paintImmediately", "repaint"),
    target.synchronizationOperations,
  )
}

private class FakeDesktopNativeViewLayoutTarget(
  override var isDisplayable: Boolean = true,
  override var isShowing: Boolean,
  override var width: Int = 100,
  override var height: Int = 100,
) : DesktopNativeViewLayoutTarget {
  private val showingListeners = mutableSetOf<() -> Unit>()
  private val layoutChangedListeners = mutableSetOf<() -> Unit>()

  var addListenerCount = 0
    private set
  var removeShowingListenerCount = 0
    private set
  var addLayoutChangedListenerCount = 0
    private set
  var removeLayoutChangedListenerCount = 0
    private set
  var revalidateCount = 0
    private set
  var immediatePaintCount = 0
    private set
  var repaintCount = 0
    private set
  val synchronizationOperations = mutableListOf<String>()

  override fun addShowingListener(listener: () -> Unit) {
    addListenerCount++
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
