// System-webview native bridge, macOS leg.
//
// Owns a single top-level dashboard window hosting a WKWebView, and parks thread 0
// in the AppKit event loop on request. No AWT is involved on this side at all.
//
// Ordering constraints (each verified empirically; violating any of them stalls
// WebKit's launch permanently with no error):
//   - Nothing AWT may initialize before the first page load completes. The Java side
//     therefore installs the tray only from onFirstLoad.
//   - Thread 0 must run a real [NSApp run] loop: AWT's private pumping can sustain a
//     loaded page but cannot launch one, and manual runMode pumping starves replies.
//   - The window must be built on thread 0; other threads marshal via runOnMain.
//
// Callbacks into MacWebview.Host (all on thread 0):
//   shouldOpenExternally(url) -> jboolean (caller also opens it externally)
//   downloadDestination(suggestedName) -> String (absolute path, DownloadTarget-resolved)
//   onDownloadComplete(fileName) -> void
//   onWindowClosed() -> void (the X button; Java quits the app)
//   onFirstLoad() -> void (first main-frame load completed; installs the tray)
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#import <jni.h>
#import <objc/runtime.h>

#import "io_postcard_desktop_MacWebview.h"

static JavaVM *gJvm;
static NSWindow *gWin;
static WKWebView *gWv;
static jobject gHost;
static jmethodID gShouldOpenExternally;
static jmethodID gDownloadDestination;
static jmethodID gOnDownloadComplete;
static jmethodID gOnWindowClosed;
static jmethodID gOnFirstLoad;
static BOOL gFirstLoadFired = NO;

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    gJvm = vm;
    return JNI_VERSION_1_6;
}

static JNIEnv *jniEnv(void) {
    JNIEnv *env = NULL;
    (*gJvm)->GetEnv(gJvm, (void **)&env, JNI_VERSION_1_6);
    return env;
}

// Thread 0 is not a JVM thread (no -XstartOnFirstThread: AWT needs a stock
// runtime), so any JNI use there must attach first. Thread 0 lives forever,
// hence attach-once without detach.
static JNIEnv *attachMain(void) {
    JNIEnv *env = jniEnv();
    if (env) return env;
    if ((*gJvm)->AttachCurrentThread(gJvm, (void **)&env, NULL) != JNI_OK) return NULL;
    return env;
}

// Runs the block on thread 0, synchronously. Safe from any thread, including
// thread 0 itself (dispatch_sync to the main queue from the main thread would
// deadlock, hence the fast path). Requires thread 0's runloop to be pumping
// when called off thread — guaranteed once runEventLoop is parked or AWT is up.
static void runOnMain(void (^block)(void)) {
    if ([NSThread isMainThread]) block();
    else dispatch_sync(dispatch_get_main_queue(), block);
}

static NSString *jstrToNSString(JNIEnv *env, jstring s) {
    if (!s) return @"";
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    NSString *r = [NSString stringWithUTF8String:c ?: ""];
    (*env)->ReleaseStringUTFChars(env, s, c);
    return r;
}

static jstring nsStringToJstr(JNIEnv *env, NSString *s) {
    return (*env)->NewStringUTF(env, [s UTF8String]);
}

@interface PostcardNavDelegate : NSObject <WKNavigationDelegate, WKDownloadDelegate>
@end

@implementation PostcardNavDelegate

- (void)webView:(WKWebView *)wv didFinishNavigation:(WKNavigation *)nav {
    (void)nav;
    NSLog(@"postcard: webview finished loading (%@)", [[wv URL] absoluteString]);
    if (!gFirstLoadFired) {
        gFirstLoadFired = YES;
        JNIEnv *env = jniEnv();
        (*env)->CallVoidMethod(env, gHost, gOnFirstLoad);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
}

- (void)webView:(WKWebView *)wv
        didFailNavigation:(WKNavigation *)nav
        withError:(NSError *)error {
    (void)nav;
    NSLog(@"postcard: webview navigation failed (%@): %@",
        [[wv URL] absoluteString], [error localizedDescription]);
}

- (void)webView:(WKWebView *)wv
        didFailProvisionalNavigation:(WKNavigation *)nav
        withError:(NSError *)error {
    (void)nav;
    NSLog(@"postcard: webview provisional navigation failed (%@): %@",
        [[wv URL] absoluteString], [error localizedDescription]);
}

- (void)webView:(WKWebView *)wv
        decidePolicyForNavigationAction:(WKNavigationAction *)action
        decisionHandler:(void (^)(WKNavigationActionPolicy))handler {
    JNIEnv *env = jniEnv();
    NSString *target = [[[action request] URL] absoluteString] ?: @"";
    jboolean external = (*env)->CallBooleanMethod(env, gHost,
        gShouldOpenExternally, nsStringToJstr(env, target));
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    handler(external ? WKNavigationActionPolicyCancel : WKNavigationActionPolicyAllow);
}

- (void)webView:(WKWebView *)wv
        decidePolicyForNavigationResponse:(WKNavigationResponse *)response
        decisionHandler:(void (^)(WKNavigationResponsePolicy))handler {
    // Anything the engine cannot show (an attachment download) becomes a download.
    handler([response canShowMIMEType] ? WKNavigationResponsePolicyAllow
                                       : WKNavigationResponsePolicyDownload);
}

- (void)webView:(WKWebView *)wv
        navigationResponse:(WKNavigationResponse *)response
        didBecomeDownload:(WKDownload *)download {
    NSString *name = [[response response] suggestedFilename] ?: @"download";
    objc_setAssociatedObject(download, "postcardName", name,
        OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    [download setDelegate:self];
}

- (void)download:(WKDownload *)download
        decideDestinationUsingResponse:(NSURLResponse *)response
        suggestedFilename:(NSString *)suggestedFilename
        completionHandler:(void (^)(NSURL * _Nullable))completionHandler {
    (void)response;
    JNIEnv *env = jniEnv();
    NSString *name = suggestedFilename ?: @"download";
    objc_setAssociatedObject(download, "postcardName", name,
        OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    jstring dest = (*env)->CallObjectMethod(env, gHost,
        gDownloadDestination, nsStringToJstr(env, name));
    NSURL *url = nil;
    if (!(*env)->ExceptionCheck(env) && dest) {
        url = [NSURL fileURLWithPath:jstrToNSString(env, dest)];
    } else {
        (*env)->ExceptionClear(env);
    }
    completionHandler(url);
}

- (void)downloadDidFinish:(WKDownload *)download {
    JNIEnv *env = jniEnv();
    NSString *name = objc_getAssociatedObject(download, "postcardName") ?: @"download";
    (*env)->CallVoidMethod(env, gHost,
        gOnDownloadComplete, nsStringToJstr(env, name));
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

- (void)download:(WKDownload *)download didFailWithError:(NSError *)error
        resumeData:(NSData *)resumeData {
    (void)download; (void)resumeData;
    NSLog(@"postcard: webview download failed (%@)", [error localizedDescription]);
}

@end

@interface PostcardWindowDelegate : NSObject <NSWindowDelegate>
@end

@implementation PostcardWindowDelegate
- (BOOL)windowShouldClose:(NSWindow *)sender {
    (void)sender;
    // Closing quits postcard; teardown runs through QuitSequence on the Java side,
    // which destroys this window via closeWindow.
    JNIEnv *env = jniEnv();
    (*env)->CallVoidMethod(env, gHost, gOnWindowClosed);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return YES;
}
@end

static void cacheHostMethods(JNIEnv *env, jobject hostGlobal) {
    // Takes ownership of hostGlobal (a global ref created on the calling thread:
    // local refs must never cross the dispatch boundary into thread 0).
    if (gHost) (*env)->DeleteGlobalRef(env, gHost);
    gHost = hostGlobal;
    jclass hostCls = (*env)->GetObjectClass(env, hostGlobal);
    gShouldOpenExternally = (*env)->GetMethodID(env, hostCls,
        "shouldOpenExternally", "(Ljava/lang/String;)Z");
    gDownloadDestination = (*env)->GetMethodID(env, hostCls,
        "downloadDestination", "(Ljava/lang/String;)Ljava/lang/String;");
    gOnDownloadComplete = (*env)->GetMethodID(env, hostCls,
        "onDownloadComplete", "(Ljava/lang/String;)V");
    gOnWindowClosed = (*env)->GetMethodID(env, hostCls,
        "onWindowClosed", "()V");
    gOnFirstLoad = (*env)->GetMethodID(env, hostCls,
        "onFirstLoad", "()V");
}

/*
 * Class:     io_postcard_desktop_MacWebview
 * Method:    openWindow
 */
JNIEXPORT jlong JNICALL Java_io_postcard_desktop_MacWebview_openWindow(
        JNIEnv *env, jclass cls, jstring url, jobject host) {
    (void)cls;
    NSString *nsUrl = jstrToNSString(env, url);
    // Local refs die with this call and belong to this thread: promote first.
    // Ownership transfers to gHost on success; freed below on failure.
    __block jobject hostGlobal = (*env)->NewGlobalRef(env, host);
    if (!hostGlobal) return 0;
    __block jlong result = 0;
    runOnMain(^{
        if (gWin) {
            [gWin makeKeyAndOrderFront:nil];
            [NSApp activateIgnoringOtherApps:YES];
            result = 1;
            return;
        }
        JNIEnv *e = attachMain();
        if (!e) {
            result = 0;
            return;
        }
        cacheHostMethods(e, hostGlobal);
        hostGlobal = NULL; // ownership transferred to gHost
        gFirstLoadFired = NO;

        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
        NSRect frame = NSMakeRect(0, 0, 1000, 720);
        gWin = [[NSWindow alloc] initWithContentRect:frame
            styleMask:(NSWindowStyleMaskTitled | NSWindowStyleMaskClosable |
                       NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable)
            backing:NSBackingStoreBuffered defer:NO];
        [gWin setTitle:@"postcard"];
        [gWin center];
        PostcardWindowDelegate *wdel = [[PostcardWindowDelegate alloc] init];
        [gWin setDelegate:wdel];
        objc_setAssociatedObject(gWin, "postcardWindowDelegate", wdel,
            OBJC_ASSOCIATION_RETAIN_NONATOMIC);

        WKWebViewConfiguration *cfg = [[WKWebViewConfiguration alloc] init];
        gWv = [[WKWebView alloc] initWithFrame:[[gWin contentView] bounds]
                                 configuration:cfg];
        [gWv setAutoresizingMask:NSViewWidthSizable | NSViewHeightSizable];
        PostcardNavDelegate *ndel = [[PostcardNavDelegate alloc] init];
        [gWv setNavigationDelegate:ndel];
        objc_setAssociatedObject(gWv, "postcardNavDelegate", ndel,
            OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        [[gWin contentView] addSubview:gWv];
        [gWin makeKeyAndOrderFront:nil];
        [NSApp activateIgnoringOtherApps:YES];
        [gWv loadRequest:[NSURLRequest requestWithURL:[NSURL URLWithString:nsUrl]]];
        NSLog(@"postcard: webview window opened (%@)", nsUrl);
        result = 1;
    });
    if (result == 0 && hostGlobal) (*env)->DeleteGlobalRef(env, hostGlobal);
    return result;
}

/*
 * Class:     io_postcard_desktop_MacWebview
 * Method:    runEventLoop
 */
JNIEXPORT void JNICALL Java_io_postcard_desktop_MacWebview_runEventLoop(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    [NSApp run];
}

/*
 * Class:     io_postcard_desktop_MacWebview
 * Method:    stopEventLoop
 */
JNIEXPORT void JNICALL Java_io_postcard_desktop_MacWebview_stopEventLoop(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    // stop: alone only takes effect once the loop wakes; the dummy event makes
    // Ctrl-C shutdown prompt even when nothing else is queued.
    [NSApp stop:nil];
    NSEvent *dummy = [NSEvent otherEventWithType:NSEventTypeApplicationDefined
        location:NSZeroPoint modifierFlags:0 timestamp:0
        windowNumber:0 context:nil subtype:0 data1:0 data2:0];
    [NSApp postEvent:dummy atStart:NO];
}

/*
 * Class:     io_postcard_desktop_MacWebview
 * Method:    closeWindow
 */
JNIEXPORT void JNICALL Java_io_postcard_desktop_MacWebview_closeWindow(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls; (void)handle;
    runOnMain(^{
        if (gWv) { [gWv removeFromSuperview]; gWv = nil; }
        if (gWin) { [gWin close]; gWin = nil; }
        if (gHost) { (*jniEnv())->DeleteGlobalRef(jniEnv(), gHost); gHost = NULL; }
    });
}

/*
 * Class:     io_postcard_desktop_MacWebview
 * Method:    isVisible
 */
JNIEXPORT jboolean JNICALL Java_io_postcard_desktop_MacWebview_isVisible(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls; (void)handle;
    __block jboolean visible = JNI_FALSE;
    runOnMain(^{
        visible = (gWin && [gWin isVisible]) ? JNI_TRUE : JNI_FALSE;
    });
    return visible;
}
