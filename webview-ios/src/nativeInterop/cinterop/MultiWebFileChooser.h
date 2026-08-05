#import <Foundation/Foundation.h>

/**
 * iOS 18.4 新增的 WKUIDelegate 文件面板回调。
 *
 * Kotlin 2.1.21 的系统 WebKit 绑定尚未导出该协议成员；此处仅以 Objective-C 对象 ABI 声明选择器，避免把
 * 整个工程升级到尚未验证兼容性的 Kotlin/Compose 版本。WebKit 只会在支持该 API 的系统上调用该选择器。
 */
@protocol MultiWebFileChooserDelegate
- (void)webView:(id)webView
runOpenPanelWithParameters:(id)parameters
initiatedByFrame:(id)frame
completionHandler:(void (^)(NSArray<NSURL *> * _Nullable URLs))completionHandler;
@end

/** 仅在 WebKit 已调用上述 iOS 18.4+ 选择器时读取参数，旧系统不会执行这些消息。 */
@interface NSObject (MultiWebFileChooserParameters)
- (BOOL)allowsMultipleSelection;
- (BOOL)allowsDirectories;
@end

static inline BOOL MultiWebFileChooserAllowsMultipleSelection(id parameters) {
  return [parameters allowsMultipleSelection];
}

static inline BOOL MultiWebFileChooserAllowsDirectories(id parameters) {
  return [parameters allowsDirectories];
}
