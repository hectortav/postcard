// System-webview native bridge, macOS leg.
//
// Embeds a WKWebView in an AWT Canvas via JAWT and reports back over JNI.
// Runs entirely on the AWT event thread (which owns the AppKit main thread),
// so there is no NSApp loop to manage and no -XstartOnFirstThread requirement.
//
// Callbacks into MacWebview.Host, all on the event thread:
//   shouldOpenExternally(url) -> jboolean (caller also opens it externally)
//   downloadDestination(suggestedName) -> String (absolute path, DownloadTarget-resolved)
//   onDownloadComplete(fileName) -> void
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#import <jni.h>
#import <jawt.h>
#import <jawt_md.h>
#import <objc/runtime.h>

#import "io_postcard_desktop_MacWebview.h"

static JavaVM *gJvm;

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    gJvm = vm;
    return JNI_VERSION_1_6;
}

typedef struct {
    WKWebView *wv;
    jobject host;          // global ref to MacWebview.Host
    jmethodID shouldOpenExternally;
    jmethodID downloadDestination;
    jmethodID onDownloadComplete;
} WebviewHandle;

static JNIEnv *jniEnv(void) {
    JNIEnv *env = NULL;
    (*gJvm)->GetEnv(gJvm, (void **)&env, JNI_VERSION_1_6);
    return env;
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
@property (nonatomic) WebviewHandle *h;
@end

@implementation PostcardNavDelegate

- (void)webView:(WKWebView *)wv
        decidePolicyForNavigationAction:(WKNavigationAction *)action
        decisionHandler:(void (^)(WKNavigationActionPolicy))handler {
    JNIEnv *env = jniEnv();
    NSString *target = [[[action request] URL] absoluteString] ?: @"";
    jboolean external = (*env)->CallBooleanMethod(env, self.h->host,
        self.h->shouldOpenExternally, nsStringToJstr(env, target));
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
    // Remember the suggested name for the completion callback below.
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
    jstring dest = (*env)->CallObjectMethod(env, self.h->host,
        self.h->downloadDestination, nsStringToJstr(env, name));
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
    (*env)->CallVoidMethod(env, self.h->host,
        self.h->onDownloadComplete, nsStringToJstr(env, name));
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

- (void)download:(WKDownload *)download didFailWithError:(NSError *)error
        resumeData:(NSData *)resumeData {
    (void)download; (void)resumeData;
    NSLog(@"postcard: webview download failed (%@)", [error localizedDescription]);
}

@end

/*
 * Class:     io_postcard_desktop_MacWebview
 * Method:    attach
 */
JNIEXPORT jlong JNICALL Java_io_postcard_desktop_MacWebview_attach(
        JNIEnv *env, jclass cls, jobject canvas, jstring url, jobject host) {
    (void)cls;

    JAWT awt;
    awt.version = JAWT_VERSION_9;
    if (JAWT_GetAWT(env, &awt) == JNI_FALSE) return 0;

    JAWT_DrawingSurface *ds = (*awt.GetDrawingSurface)(env, canvas);
    if (!ds) return 0;
    if ((*ds->Lock)(ds) & JAWT_LOCK_ERROR) {
        (*awt.FreeDrawingSurface)(ds);
        return 0;
    }
    JAWT_DrawingSurfaceInfo *dsi = (*ds->GetDrawingSurfaceInfo)(ds);
    // Since JDK 7 the header no longer names a concrete peer type: platformInfo is
    // documented only as "an NSObject conforming to JAWT_SurfaceLayers". In practice it
    // has always been the peer NSView itself (CPlatformView), which is what lets a
    // webview be added as a subview with the Canvas bounds. The isKindOfClass guard
    // keeps this honest: if a future JDK changes the implementation, attach fails
    // gracefully (no window, server keeps running) instead of crashing.
    NSView *parent = nil;
    if (dsi && dsi->platformInfo) {
        NSObject *peer = (__bridge NSObject *)dsi->platformInfo;
        if ([peer isKindOfClass:[NSView class]]) parent = (NSView *)peer;
    }

    jlong result = 0;
    if (parent) {
        WebviewHandle *h = calloc(1, sizeof(WebviewHandle));
        h->host = (*env)->NewGlobalRef(env, host);
        jclass hostCls = (*env)->GetObjectClass(env, host);
        h->shouldOpenExternally = (*env)->GetMethodID(env, hostCls,
            "shouldOpenExternally", "(Ljava/lang/String;)Z");
        h->downloadDestination = (*env)->GetMethodID(env, hostCls,
            "downloadDestination", "(Ljava/lang/String;)Ljava/lang/String;");
        h->onDownloadComplete = (*env)->GetMethodID(env, hostCls,
            "onDownloadComplete", "(Ljava/lang/String;)V");

        WKWebViewConfiguration *cfg = [[WKWebViewConfiguration alloc] init];
        WKWebView *wv = [[WKWebView alloc] initWithFrame:[parent bounds]
                                          configuration:cfg];
        wv.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        PostcardNavDelegate *del = [[PostcardNavDelegate alloc] init];
        del.h = h;
        wv.navigationDelegate = del;
        // The delegate must survive as long as the webview; the webview holds
        // it weakly, so pin it as an associated object of the webview itself.
        objc_setAssociatedObject(wv, "postcardDelegate", del,
            OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        h->wv = wv;
        [parent addSubview:wv];
        [wv loadRequest:[NSURLRequest requestWithURL:
            [NSURL URLWithString:jstrToNSString(env, url)]]];
        result = (jlong)(intptr_t)h;
    }

    (*ds->FreeDrawingSurfaceInfo)(dsi);
    (*ds->Unlock)(ds);
    (*awt.FreeDrawingSurface)(ds);
    return result;
}

/*
 * Class:     io_postcard_desktop_MacWebview
 * Method:    detach
 */
JNIEXPORT void JNICALL Java_io_postcard_desktop_MacWebview_detach(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    if (handle == 0) return;
    WebviewHandle *h = (WebviewHandle *)(intptr_t)handle;
    [h->wv removeFromSuperview];
    h->wv = nil;
    if (h->host) (*env)->DeleteGlobalRef(env, h->host);
    free(h);
}
