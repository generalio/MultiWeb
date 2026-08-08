package io.github.multiweb.desktop

import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JComponent
import javax.swing.SwingUtilities

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
  private val componentListeners = mutableMapOf<() -> Unit, ComponentAdapter>()

  override val isDisplayable: Boolean
    get() = component.isDisplayable

  override val isShowing: Boolean
    get() = component.isShowing

  override val width: Int
    get() = component.width

  override val height: Int
    get() = component.height

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
    if (componentListeners.containsKey(listener)) {
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
    componentListeners[listener] = componentListener
    component.addComponentListener(componentListener)
  }

  override fun removeLayoutChangedListener(listener: () -> Unit) {
    componentListeners.remove(listener)?.let(component::removeComponentListener)
  }

  override fun revalidate() {
    component.revalidate()
  }

  /**
   * 同步绘制 JCEF 暴露的 JPanel，触发其内部的 `doUpdate()` 与原生浏览器子窗口绑定。
   *
   * JCEF windowed 模式下普通 [repaint] 只会异步请求 Swing 绘制，Compose `SwingPanel` 的首次挂载可能不会立刻
   * 进入 `paint()`；这里仅在 Swing EDT 和可见有效边界内强制执行一次。
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
    (component as? JComponent)?.paintImmediately(0, 0, width, height) ?: component.repaint()
  }

  override fun repaint() {
    component.repaint()
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
  }

  /** 请求创建浏览器；仅在组件已显示且拥有原生父视图时执行。 */
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
    removeShowingListener()
  }

  private fun createBrowserIfReady() {
    if (
      isUnavailable() ||
      !isBrowserCreationRequested ||
      isBrowserCreated ||
      isBrowserCreationStarted ||
      !target.isDisplayable ||
      !target.isShowing
    ) {
      return
    }
    isBrowserCreationStarted = true
    try {
      createBrowser()
      isBrowserCreated = true
      removeShowingListener()
    } finally {
      isBrowserCreationStarted = false
    }
  }

  private fun removeShowingListener() {
    if (!isShowingListenerRegistered) {
      return
    }
    target.removeShowingListener(showingListener)
    isShowingListenerRegistered = false
  }

  private fun isUnavailable(): Boolean = isDisposed || isControllerDisposed()
}

/**
 * 协调 JCEF 就绪与 Swing 视图首次 showing 的布局同步。
 *
 * Compose `SwingPanel` 的首次有效尺寸可能晚于 JCEF 创建或 showing 事件；因此在控制器明确请求后同时监听 showing 与
 * 尺寸变化，并在 Swing EDT 延迟重试一次。仅当视图可显示且尺寸有效时，才执行一次
 * [DesktopNativeViewLayoutTarget.revalidate]、[DesktopNativeViewLayoutTarget.paintImmediately] 与
 * [DesktopNativeViewLayoutTarget.repaint]。调用方必须在 Swing EDT 调用本类。
 */
internal class DesktopInitialNativeViewLayoutCoordinator(
  private val target: DesktopNativeViewLayoutTarget,
  private val isControllerDisposed: () -> Boolean,
  private val scheduleDelayedRetry: ((() -> Unit) -> Unit) = { action ->
    SwingUtilities.invokeLater { action() }
  },
) {
  private var isDisposed = false
  private var isShowingListenerRegistered = false
  private var isLayoutChangedListenerRegistered = false
  private var isInitialLayoutRequested = false
  private var isInitialLayoutSynchronized = false
  private var isSynchronizing = false
  private var isDelayedRetryScheduled = false
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

  /** 初始同步成功后立即移除全部监听，窗口后续缩放不得再次触发同步绘制。 */
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

  /** 仅在 JCEF 已请求、Swing 已显示且布局尺寸有效时执行一次同步。 */
  private fun synchronizeNativeViewLayoutIfReady() {
    if (
      isUnavailable() ||
      !isInitialLayoutRequested ||
      isInitialLayoutSynchronized ||
      isSynchronizing
    ) {
      return
    }
    if (
      !target.isDisplayable ||
      !target.isShowing ||
      target.width <= 0 ||
      target.height <= 0
    ) {
      scheduleOneDelayedRetry()
      return
    }
    isSynchronizing = true
    try {
      target.revalidate()
      target.paintImmediately()
      target.repaint()
      isInitialLayoutSynchronized = true
      removeInitialLayoutListeners()
    } finally {
      isSynchronizing = false
    }
  }

  /** 覆盖同一 EDT 事件循环内才完成的首次布局；未就绪时仍等待下一次真实视图事件。 */
  private fun scheduleOneDelayedRetry() {
    if (isDelayedRetryScheduled || isUnavailable() || isInitialLayoutSynchronized) {
      return
    }
    isDelayedRetryScheduled = true
    scheduleDelayedRetry {
      isDelayedRetryScheduled = false
      synchronizeNativeViewLayoutIfReady()
    }
  }

  private fun isUnavailable(): Boolean = isDisposed || isControllerDisposed()
}
