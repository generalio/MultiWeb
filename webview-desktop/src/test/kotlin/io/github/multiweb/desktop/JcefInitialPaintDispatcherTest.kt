package io.github.multiweb.desktop

import java.awt.Graphics
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals

class JcefInitialPaintDispatcherTest {

  @Test
  fun `离屏绘制会调用 JCEF 组件的真实 paint`() {
    val component = PaintTrackingPanel()

    JcefInitialPaintDispatcher.paint(component)

    assertEquals(1, component.paintCount)
  }

  private class PaintTrackingPanel : JPanel() {
    var paintCount = 0
      private set

    override fun paint(graphics: Graphics) {
      paintCount++
      super.paint(graphics)
    }
  }
}
