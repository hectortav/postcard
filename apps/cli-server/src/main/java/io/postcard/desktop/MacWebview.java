package io.postcard.desktop;

import java.awt.Canvas;
import java.nio.file.Path;
import java.util.Objects;

/**
 * JNI binding for the per-OS system-webview library (macOS: WKWebView embedded in the
 * AWT window via JAWT; Windows/Linux legs to follow).
 *
 * <p>Thin shell over native code by design: every decidable rule lives in tested Java
 * ({@link WebviewPolicy}, {@link DownloadTarget}) and the native side only renders and
 * reports back through {@link Host}. Loading or attaching must never take down the file
 * server, so callers treat every failure here as "no window" and carry on headless.
 */
public final class MacWebview {
    /** Callbacks the native side invokes on the AWT event thread. */
    public interface Host {
        /** Pure navigation rule; when true the caller also opens the URL externally. */
        boolean shouldOpenExternally(String url);

        /** Absolute destination path for a download, resolved via {@link DownloadTarget}. */
        String downloadDestination(String suggestedName);

        /** A download the host saved through the window finished. */
        void onDownloadComplete(String fileName);
    }

    private MacWebview() {}

    /** Loads {@code libpostcard-webview.dylib} from the natives directory. */
    public static void ensureLoaded(Path nativesDir) {
        Objects.requireNonNull(nativesDir, "nativesDir");
        System.load(nativesDir.resolve("libpostcard-webview.dylib").toAbsolutePath().toString());
    }

    /**
     * Embeds a webview in an already-realized {@link Canvas} and loads the URL.
     * Must be called on the AWT event thread with a display present.
     *
     * @return an opaque native handle for {@link #detach(long)}
     */
    public static native long attach(Canvas canvas, String url, Host host);

    /** Removes the webview and releases the native handle. Safe with handle 0 (no-op). */
    public static native void detach(long handle);
}
