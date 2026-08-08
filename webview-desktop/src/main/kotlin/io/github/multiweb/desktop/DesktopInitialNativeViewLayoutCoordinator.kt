package io.github.multiweb.desktop

import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 可参与 JCEF 初始布局同步的原生视图。
 *
 * 该内部抽象将 AWT 事件与布局操作隔离，使首次挂载的时序可以在不创建真实 JCEF 浏览器的单元测试中验证。
 */
internal interface DesktopNativeViewLayoutTarget {
  /** 视图是否已与原生窗口关联。 */
  val isDisplayable: Boolean
  /** 视图是否已实际显示。 */
  val isShowing: Boolean
  /** 当前布局宽度。 */
  val width: Int
  /** 当前布局高度。 */
  val height: Int
  /** 当前实际可见区域宽度。 */
  val visibleWidth: Int
  /** 当前实际可见区域高度。 */
  val visibleHeight: Int

  /** 注册视图进入 showing 状态时的回调。 */
  fun addShowingListener(listener: () -> Unit)

  /** 移除先前注册的 showing 状态回调。 */
  fun removeShowingListener(listener: () -> Unit)

  /** 注册组件首次 showing 或尺寸变化时的回调。 */
  fun addLayoutChangedListener(listener: () -> Unit)

  /** 移除先前注册的组件布局变化回调。 */
  fun removeLayoutChangedListener(listener: () -> Unit)

  /** 请求 AWT 重新执行布局。 */
  fun revalidate()

  /**
   * 以当前边界重新设置原生视图尺寸。
   *
   * JCEF 的 windowed 视图会在自身的 `setBounds()` 中将尺寸同步给 Chromium。Compose SwingPanel
   * 可能在浏览器创建前已经完成过一次布局，因此首次原生浏览器就绪后需要无尺寸变化地重新执行该路径。
   */
  fun synchronizeNativeViewSize()

  /** 同步进入原生 Swing 视图的绘制流程。 */
  fun paintImmediately()

  /** 请求 AWT 重绘原生视图。 */
  fun repaint()
}

/** 将 JCEF 暴露的 AWT [Component] 适配为可测试的布局目标。 */
internal class ComponentDesktopNativeViewLayoutTarget(
  private val component: Component,
) : DesktopNativeViewLayoutTarget {
  private val hierarchyListeners = mutableMapOf<() -> Unit, HierarchyListener>()
  private val layoutChangedListeners = mutableMapOf<() -> Unit, LayoutChangedListenerRegistration>()

  override val isDisplayable: Boolean
    get() = component.isDisplayable

  override val isShowing: Boolean
    get() = component.isShowing

  override val width: Int
    get() = component.width

  override val height: Int
    get() = component.height

  override val visibleWidth: Int
    get() = component.visibleSize().width

  override val visibleHeight: Int
    get() = component.visibleSize().height

  override fun addShowingListener(listener: () -> Unit) {
    if (hierarchyListeners.containsKey(listener)) {
      return
    }
    val hierarchyListener = HierarchyListener { event ->
      if (
        event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L &&
        component.isShowing
      ) {
        listener()
      }
    }
    hierarchyListeners[listener] = hierarchyListener
    component.addHierarchyListener(hierarchyListener)
  }

  override fun removeShowingListener(listener: () -> Unit) {
    hierarchyListeners.remove(listener)?.let(component::removeHierarchyListener)
  }

  override fun addLayoutChangedListener(listener: () -> Unit) {
    if (layoutChangedListeners.containsKey(listener)) {
      return
    }
    val componentListener = object : ComponentAdapter() {
      override fun componentResized(event: ComponentEvent) {
        listener()
      }

      override fun componentShown(event: ComponentEvent) {
        listener()
      }
    }
    val hierarchyBoundsListener = object : HierarchyBoundsAdapter() {
      override fun ancestorResized(event: HierarchyEvent) {
        listener()
      }

      override fun ancestorMoved(event: HierarchyEvent) {
        listener()
      }
    }
    layoutChangedListeners[listener] = LayoutChangedListenerRegistration(
      componentListener = componentListener,
      hierarchyBoundsListener = hierarchyBoundsListener,
    )
    component.addComponentListener(componentListener)
    component.addHierarchyBoundsListener(hierarchyBoundsListener)
  }

  override fun removeLayoutChangedListener(listener: () -> Unit) {
    layoutChangedListeners.remove(listener)?.let { registration ->
      component.removeComponentListener(registration.componentListener)
      component.removeHierarchyBoundsListener(registration.hierarchyBoundsListener)
    }
  }

  override fun revalidate() {
    component.revalidate()
  }

  override fun synchronizeNativeViewSize() {
    component.setBounds(component.bounds)
  }

  /**
   * 同步绘制 JCEF 暴露的 JPanel，触发其内部的 `doUpdate()` 与原生浏览器子窗口绑定。
   *
   * Compose `SwingPanel` 的首次 [JComponent.paintImmediately] 不保证会实际调用嵌入组件重写的 `paint()`；
   * 而 JCEF 恰好在该方法中安排带原生父窗口的浏览器创建。因此在正常的立即绘制请求之后，再使用极小的离屏
   * Graphics 明确执行一次真实绘制。JCEF 使用组件自身的可见区域计算布局，离屏图像尺寸不会影响浏览器尺寸。
   */
  override fun paintImmediately() {
    check(SwingUtilities.isEventDispatchThread()) {
      "JCEF 初始同步绘制必须在 Swing EDT 执行。"
    }
    val width = component.width
    val height = component.height
    if (!component.isDisplayable || !component.isShowing || width <= 0 || height <= 0) {
      return
    }
    val swingComponent = component as? JComponent
    if (swingComponent == null) {
      component.repaint()
      return
    }
    swingComponent.paintImmediately(0, 0, width, height)
    JcefInitialPaintDispatcher.paint(swingComponent)
  }

  override fun repaint() {
    component.repaint()
  }

  private fun Component.visibleSize() = (this as? JComponent)?.visibleRect?.size ?: size

  private data class LayoutChangedListenerRegistration(
    val componentListener: ComponentAdapter,
    val hierarchyBoundsListener: HierarchyBoundsAdapter,
  )
}

/**
 * 显式调用 JCEF Windowed 组件的 [JComponent.paint]。
 *
 * JCEF 的内部 `delayedUpdate` 会在本次绘制后以当前 macOS 原生窗口句柄创建浏览器。不能使用
 * `createImmediately()` 兜底，否则浏览器可能在未绑定父窗口时创建，并再次退化为只能在窗口 resize 后显示。
 */
internal object JcefInitialPaintDispatcher {
  fun paint(component: JComponent) {
    val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
      graphics.transform = component.graphicsConfiguration
        ?.defaultTransform
        ?.let(::AffineTransform)
        ?: AffineTransform()
      component.paint(graphics)
    } finally {
      graphics.dispose()
    }
  }
}

/**
 * 协调 JCEF 浏览器创建与 Compose Swing 组件挂载。
 *
 * windowed JCEF 会在 [org.cef.browser.CefBrowser.createImmediately] 时创建原生子窗口；如果此时 Compose 的
 * `SwingPanel` 尚未进入原生窗口层级，首帧可能不会获得有效的父视图，直到用户缩放窗口才触发后续原生布局。
 * 因此必须等待目标组件真正 showing 后再创建浏览器。
 */
internal class DesktopNativeViewAttachmentCoordinator(
  private val target: DesktopNativeViewLayoutTarget,
  private val isControllerDisposed: () -> Boolean,
  private val createBrowser: () -> Unit,
) {
  private var isDisposed = false
  private var isShowingListenerRegistered = false
  private var isLayoutChangedListenerRegistered = false
  private var isBrowserCreationRequested = false
  private var isBrowserCreationStarted = false
  private var isBrowserCreated = false
  private val showingListener: () -> Unit = ::createBrowserIfReady

  /** 注册 showing 监听；浏览器创建请求可以先于原生视图挂载到达。 */
  fun registerShowingListener() {
    if (isUnavailable() || isShowingListenerRegistered) {
      return
    }
    target.addShowingListener(showingListener)
    isShowingListenerRegistered = true
    target.addLayoutChangedListener(showingListener)
    isLayoutChangedListenerRegistered = true
  }

  /** 请求创建浏览器；仅在组件已显示、已完成可见裁剪且拥有原生父视图时执行。 */
  fun requestBrowserCreation() {
    if (isUnavailable()) {
      return
    }
    isBrowserCreationRequested = true
    createBrowserIfReady()
  }

  /** 取消等待中的创建请求；已开始创建的浏览器由控制器自身关闭。 */
  fun dispose() {
    if (isDisposed) {
      return
    }
    isDisposed = true
    removeViewReadyListeners()
  }

  private fun createBrowserIfReady() {
    if (
      isUnavailable() ||
      !isBrowserCreationRequested ||
      isBrowserCreated ||
      isBrowserCreationStarted ||
      !target.isDisplayable ||
      !target.isShowing ||
      target.width <= 0 ||
      target.height <= 0 ||
      target.visibleWidth <= 0 ||
      target.visibleHeight <= 0
    ) {
      return
    }
    isBrowserCreationStarted = true
    try {
      createBrowser()
      isBrowserCreated = true
      removeViewReadyListeners()
    } finally {
      isBrowserCreationStarted = false
    }
  }

  private fun removeViewReadyListeners() {
    if (isShowingListenerRegistered) {
      target.removeShowingListener(showingListener)
      isShowingListenerRegistered = false
    }
    if (isLayoutChangedListenerRegistered) {
      target.removeLayoutChangedListener(showingListener)
      isLayoutChangedListenerRegistered = false
    }
  }

  private fun isUnavailable(): Boolean = isDisposed || isControllerDisposed()
}

/**
 * 协调 JCEF 就绪与 Swing 视图首次 showing 的布局同步。
 *
 * Compose `SwingPanel` 的首次有效可见区域可能晚于 JCEF 创建或 showing 事件；因此在控制器明确请求后同时监听 showing 与
 * 祖先布局变化，并在 JCEF 的原生子窗口完成绑定后再延迟同步一次。仅当视图可显示、尺寸与裁剪区域均有效时，才执行
 * [DesktopNativeViewLayoutTarget.revalidate]、[DesktopNativeViewLayoutTarget.paintImmediately] 与
 * [DesktopNativeViewLayoutTarget.repaint]。调用方必须在 Swing EDT 调用本类。
 */
internal class DesktopInitialNativeViewLayoutCoordinator(
  private val target: DesktopNativeViewLayoutTarget,
  private val isControllerDisposed: () -> Boolean,
  private val scheduleRetry: ((() -> Unit) -> Unit) = { action ->
    Timer(INITIAL_LAYOUT_RETRY_DELAY_MILLIS) { action() }.apply {
      isRepeats = false
      start()
    }
  },
) {
  private var isDisposed = false
  private var isShowingListenerRegistered = false
  private var isLayoutChangedListenerRegistered = false
  private var isInitialLayoutRequested = false
  private var isInitialLayoutCompleted = false
  private var isSynchronizing = false
  private var isDelayedRetryScheduled = false
  private var invalidLayoutRetryCount = 0
  private var synchronizationPassCount = 0
  private val initialLayoutListener: () -> Unit = ::synchronizeNativeViewLayoutIfReady

  /** 注册一次 showing 监听；视图先于 JCEF 就绪时也可安全调用。 */
  fun registerShowingListener() {
    if (isUnavailable() || isShowingListenerRegistered) {
      return
    }
    target.addShowingListener(initialLayoutListener)
    isShowingListenerRegistered = true
    target.addLayoutChangedListener(initialLayoutListener)
    isLayoutChangedListenerRegistered = true
  }

  /** 标记 JCEF 已就绪，并在视图已显示时立即完成首次布局同步。 */
  fun requestInitialNativeViewLayout() {
    if (isUnavailable()) {
      return
    }
    isInitialLayoutRequested = true
    synchronizeNativeViewLayoutIfReady()
  }

  /**
   * 释放 showing 与布局监听，禁止后续事件触发原生重绘。
   *
   * 控制器先设置自身已销毁状态也不会影响这里的移除操作。
   */
  fun dispose() {
    if (isDisposed) {
      return
    }
    isDisposed = true
    removeInitialLayoutListeners()
  }

  /** 初始同步完成后移除全部监听，窗口后续缩放不得再次触发同步绘制。 */
  private fun removeInitialLayoutListeners() {
    if (isShowingListenerRegistered) {
      target.removeShowingListener(initialLayoutListener)
      isShowingListenerRegistered = false
    }
    if (isLayoutChangedListenerRegistered) {
      target.removeLayoutChangedListener(initialLayoutListener)
      isLayoutChangedListenerRegistered = false
    }
  }

  /** 仅在 JCEF 已请求、Swing 已显示且布局尺寸与可见裁剪区域有效时执行同步。 */
  private fun synchronizeNativeViewLayoutIfReady() {
    if (
      isUnavailable() ||
      !isInitialLayoutRequested ||
      isInitialLayoutCompleted ||
      isSynchronizing
    ) {
      return
    }
    if (
      !target.isDisplayable ||
      !target.isShowing ||
      target.width <= 0 ||
      target.height <= 0 ||
      target.visibleWidth <= 0 ||
      target.visibleHeight <= 0
    ) {
      scheduleRetryForInvalidLayout()
      return
    }
    isSynchronizing = true
    try {
      target.revalidate()
      target.synchronizeNativeViewSize()
      target.paintImmediately()
      target.repaint()
      synchronizationPassCount++
      if (synchronizationPassCount == INITIAL_LAYOUT_SYNCHRONIZATION_PASSES) {
        isInitialLayoutCompleted = true
        removeInitialLayoutListeners()
      } else {
        scheduleStabilizationRetry()
      }
    } finally {
      isSynchronizing = false
    }
  }

  /** 布局尚未稳定时的有限重试；次数耗尽后仍保留布局监听等待真实事件。 */
  private fun scheduleRetryForInvalidLayout() {
    if (invalidLayoutRetryCount == MAX_INVALID_LAYOUT_RETRIES) {
      return
    }
    invalidLayoutRetryCount++
    scheduleDelayedRetry()
  }

  /** JCEF 创建原生子窗口后，再执行一次布局同步以覆盖异步 parent 绑定。 */
  private fun scheduleStabilizationRetry() {
    scheduleDelayedRetry()
  }

  private fun scheduleDelayedRetry() {
    if (isDelayedRetryScheduled || isUnavailable() || isInitialLayoutCompleted) {
      return
    }
    isDelayedRetryScheduled = true
    scheduleRetry {
      isDelayedRetryScheduled = false
      synchronizeNativeViewLayoutIfReady()
    }
  }

  private fun isUnavailable(): Boolean = isDisposed || isControllerDisposed()

  private companion object {
    const val INITIAL_LAYOUT_RETRY_DELAY_MILLIS = 100
    const val INITIAL_LAYOUT_SYNCHRONIZATION_PASSES = 2
    const val MAX_INVALID_LAYOUT_RETRIES = 3
  }
}
