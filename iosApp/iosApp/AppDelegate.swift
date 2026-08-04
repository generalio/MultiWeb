import UIKit
import MultiWebSample

/** iOS 宿主入口，将 KMP Compose 页面作为根视图控制器展示。 */
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
  var window: UIWindow?

  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?,
  ) -> Bool {
    let window = UIWindow(frame: UIScreen.main.bounds)
    window.rootViewController = MainViewControllerKt.MainViewController()
    window.makeKeyAndVisible()
    self.window = window
    return true
  }
}
