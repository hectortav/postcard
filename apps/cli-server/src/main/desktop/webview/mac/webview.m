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
#include <unistd.h>

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
static jmethodID gOnQuitRequested;
static id gAppDelegate;
static BOOL gFirstLoadFired = NO;
// Set while AppKit is tearing the window down on its own (the red close button). Java's
// shutdown path also calls closeWindow, and closing a window from inside its own
// -windowShouldClose: is re-entrant: AppKit then finishes closing a window that has already
// been closed and, with releasedWhenClosed, deallocated. That is the crash on quit.
static BOOL gClosing = NO;

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    gJvm = vm;
    return JNI_VERSION_1_6;
}

// NSLog writes to the macOS unified log, which persists to disk and is readable by any
// local user via `log show`. The dashboard URL's fragment carries the AES key and the PIN,
// so every URL that reaches a log line is truncated at the '#' first.
static NSString *redactFragment(NSString *url) {
    if (!url) return @"";
    NSRange hash = [url rangeOfString:@"#"];
    if (hash.location == NSNotFound) return url;
    return [[url substringToIndex:hash.location] stringByAppendingString:@"#<redacted>"];
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
    NSLog(@"postcard: webview finished loading (%@)", redactFragment([[wv URL] absoluteString]));
    if (!gFirstLoadFired) {
        gFirstLoadFired = YES;
        JNIEnv *env = attachMain();
        if (!env || !gHost) return;
        (*env)->CallVoidMethod(env, gHost, gOnFirstLoad);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
}

- (void)webView:(WKWebView *)wv
        didFailNavigation:(WKNavigation *)nav
        withError:(NSError *)error {
    (void)nav;
    NSLog(@"postcard: webview navigation failed (%@): %@",
        redactFragment([[wv URL] absoluteString]), [error localizedDescription]);
}

- (void)webView:(WKWebView *)wv
        didFailProvisionalNavigation:(WKNavigation *)nav
        withError:(NSError *)error {
    (void)nav;
    NSLog(@"postcard: webview provisional navigation failed (%@): %@",
        redactFragment([[wv URL] absoluteString]), [error localizedDescription]);
}

- (void)webView:(WKWebView *)wv
        decidePolicyForNavigationAction:(WKNavigationAction *)action
        decisionHandler:(void (^)(WKNavigationActionPolicy))handler {
    JNIEnv *env = attachMain();
    if (!env || !gHost) { handler(WKNavigationActionPolicyAllow); return; }
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
    JNIEnv *env = attachMain();
    if (!env || !gHost) { completionHandler(nil); return; }
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
    JNIEnv *env = attachMain();
    if (!env || !gHost) return;
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
    // Closing quits postcard. AppKit is already closing this window, so mark it: the Java
    // teardown that this call kicks off must not turn around and close it a second time.
    gClosing = YES;
    JNIEnv *env = attachMain();
    if (env && gHost) {
        (*env)->CallVoidMethod(env, gHost, gOnWindowClosed);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    return YES;
}

- (void)windowWillClose:(NSNotification *)note {
    (void)note;
    // The window is going away for good; drop our references so nothing touches a dead
    // object later. Releasing the JNI global ref here would race the teardown still running
    // on another thread, so that stays in closeWindow.
    gClosing = YES;
    gWv = nil;
    gWin = nil;
}
@end

@interface PostcardAppDelegate : NSObject <NSApplicationDelegate>
@end

@implementation PostcardAppDelegate

/**
 * Cmd-Q, the Dock menu's Quit, and "Quit postcard" from the menu bar all arrive here.
 *
 * Without a delegate of our own, macOS terminates the process outright: no drain, no tray
 * removal, no deleting the temporary share directory, and any transfer in flight dies
 * unannounced. AWT's own quit handler reports itself installed but never sees the event,
 * because thread 0 is inside our [NSApp run] rather than AWT's.
 *
 * Answering NSTerminateCancel hands control back to postcard, which then ends the process
 * itself once the teardown is done.
 */
- (NSApplicationTerminateReply)applicationShouldTerminate:(NSApplication *)sender {
    (void)sender;
    JNIEnv *env = attachMain();
    if (env && gHost && gOnQuitRequested) {
        (*env)->CallVoidMethod(env, gHost, gOnQuitRequested);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    return NSTerminateCancel;
}

/** A Dock click on the running app. Raises the window rather than doing nothing. */
- (BOOL)applicationShouldHandleReopen:(NSApplication *)sender hasVisibleWindows:(BOOL)visible {
    (void)sender; (void)visible;
    if (gWin) {
        if ([NSApp isHidden]) [NSApp unhide:nil];
        if ([gWin isMiniaturized]) [gWin deminiaturize:nil];
        [gWin makeKeyAndOrderFront:nil];
        [NSApp activateIgnoringOtherApps:YES];
    }
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
    gOnQuitRequested = (*env)->GetMethodID(env, hostCls,
        "onQuitRequested", "()V");
}

/*
 * Class:     io_postcard_desktop_MacWebview
 * Method:    terminateNow
 */
JNIEXPORT void JNICALL Java_io_postcard_desktop_MacWebview_terminateNow(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    // End the process here, without unwinding AppKit or running atexit handlers.
    //
    // By the time this is called postcard's own teardown has finished and said so: the server
    // is stopped, transfers are drained, the hub is closed and a temporary share directory is
    // already deleted. What is left is only the runtime's own exit path, and that is the part
    // that misbehaves. Asking the event loop to return does not work once AWT is up for the
    // tray icon -- AWT takes over the main run loop, so -stop: never brings our [NSApp run]
    // back and the app sat for two seconds waiting for it. Unwinding through exit() instead
    // is what the earlier design found could hang for seconds inside the ObjC runtime lock.
    //
    // _exit is the one option with no waiting in it.
    _exit(0);
}

/*
 * Class:     io_postcard_desktop_MacWebview
 * Method:    installAppDelegate
 */
JNIEXPORT void JNICALL Java_io_postcard_desktop_MacWebview_installAppDelegate(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    runOnMain(^{
        // Installed after the tray is up, deliberately. Initialising AWT sets an application
        // delegate of its own, so claiming this any earlier just gets overwritten.
        if (!gAppDelegate) gAppDelegate = [[PostcardAppDelegate alloc] init];
        [NSApp setDelegate:gAppDelegate];
    });
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
        // Programmatically created NSWindows default to releasedWhenClosed = YES. Under ARC
        // the static above is already a strong reference, so letting -close release it too
        // is an over-release: the second one lands on freed memory.
        [gWin setReleasedWhenClosed:NO];
        [gWin center];
        gClosing = NO;
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
        NSLog(@"postcard: webview window opened (%@)", redactFragment(nsUrl));
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
    // On the main queue, not the calling thread. NSApp is AppKit state and -stop: from a
    // background thread is simply ignored -- which is what made closing the window take two
    // seconds: the quit sequence calls this from its teardown thread, the loop never came
    // back, and the process sat until the Java-side fallback timer gave up and halted it.
    //
    // async, not sync: the caller may be a thread the main thread is itself waiting on.
    dispatch_async(dispatch_get_main_queue(), ^{
        [NSApp stop:nil];
        // -stop: only takes effect once the loop finishes dispatching an event, so give it
        // one even when nothing else is queued.
        NSEvent *dummy = [NSEvent otherEventWithType:NSEventTypeApplicationDefined
            location:NSZeroPoint modifierFlags:0 timestamp:0
            windowNumber:0 context:nil subtype:0 data1:0 data2:0];
        [NSApp postEvent:dummy atStart:NO];
    });
}

/*
 * Class:     io_postcard_desktop_MacWebview
 * Method:    closeWindow
 */
JNIEXPORT void JNICALL Java_io_postcard_desktop_MacWebview_closeWindow(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls; (void)handle;
    runOnMain(^{
        // When the user clicked the red button, AppKit is mid-close and -close here would be
        // re-entrant. Only tear the window down when something else asked us to (tray Quit,
        // Ctrl-C), which is the case the flag leaves false.
        if (!gClosing) {
            if (gWv) { [gWv removeFromSuperview]; gWv = nil; }
            if (gWin) { [gWin close]; gWin = nil; }
        }
        JNIEnv *e = attachMain();
        if (e && gHost) { (*e)->DeleteGlobalRef(e, gHost); }
        gHost = NULL;
    });
}

/*
 * Class:     io_postcard_desktop_MacWebview
 * Method:    raiseWindow
 */
JNIEXPORT jboolean JNICALL Java_io_postcard_desktop_MacWebview_raiseWindow(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    __block jboolean raised = JNI_FALSE;
    runOnMain(^{
        if (!gWin) return;
        // A Dock click on a running app delivers a reopen event, not a new launch. The window
        // may be minimized or the app hidden, so ordering it front is not enough on its own.
        if ([NSApp isHidden]) [NSApp unhide:nil];
        if ([gWin isMiniaturized]) [gWin deminiaturize:nil];
        [gWin makeKeyAndOrderFront:nil];
        [NSApp activateIgnoringOtherApps:YES];
        raised = JNI_TRUE;
    });
    return raised;
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
