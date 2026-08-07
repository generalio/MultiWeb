package io.github.multiweb.desktop

import java.awt.Component
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener

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

  /** 请求 AWT 重新执行布局。 */
  fun revalidate()

  /** 请求 AWT 重绘原生视图。 */
  fun repaint()
}

/** 将 JCEF 暴露的 AWT [Component] 适配为可测试的布局目标。 */
internal class ComponentDesktopNativeViewLayoutTarget(
  private val component: Component,
) : DesktopNativeViewLayoutTarget {
  private val hierarchyListeners = mutableMapOf<() -> Unit, HierarchyListener>()

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

  override fun revalidate() {
    component.revalidate()
  }

  override fun repaint() {
    component.repaint()
  }
}

/**
 * 协调 JCEF 就绪与 Swing 视图首次 showing 的布局同步。
 *
 * windowed JCEF 的原生子窗口需要在 Swing 已完成首次绘制后同步尺寸；因此只在控制器明确请求、视图可显示且尺寸
 * 有效时执行一次 [DesktopNativeViewLayoutTarget.revalidate] 与 [DesktopNativeViewLayoutTarget.repaint]。调用方必须在
 * Swing EDT 调用本类。
 */
internal class DesktopInitialNativeViewLayoutCoordinator(
  private val target: DesktopNativeViewLayoutTarget,
  private val isControllerDisposed: () -> Boolean,
) {
  private var isDisposed = false
  private var isShowingListenerRegistered = false
  private var isInitialLayoutRequested = false
  private var isInitialLayoutSynchronized = false
  private val showingListener: () -> Unit = ::synchronizeNativeViewLayoutIfReady

  /** 注册一次 showing 监听；视图先于 JCEF 就绪时也可安全调用。 */
  fun registerShowingListener() {
    if (isUnavailable() || isShowingListenerRegistered) {
      return
    }
    target.addShowingListener(showingListener)
    isShowingListenerRegistered = true
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
   * 释放 showing 监听，禁止后续事件触发原生重绘。
   *
   * 控制器先设置自身已销毁状态也不会影响这里的移除操作。
   */
  fun dispose() {
    if (isDisposed) {
      return
    }
    isDisposed = true
    if (isShowingListenerRegistered) {
      target.removeShowingListener(showingListener)
      isShowingListenerRegistered = false
    }
  }

  /** 仅在 JCEF 已请求、Swing 已显示且布局尺寸有效时执行一次同步。 */
  private fun synchronizeNativeViewLayoutIfReady() {
    if (
      isUnavailable() ||
      !isInitialLayoutRequested ||
      isInitialLayoutSynchronized ||
      !target.isDisplayable ||
      !target.isShowing ||
      target.width <= 0 ||
      target.height <= 0
    ) {
      return
    }
    target.revalidate()
    target.repaint()
    isInitialLayoutSynchronized = true
  }

  private fun isUnavailable(): Boolean = isDisposed || isControllerDisposed()
}
