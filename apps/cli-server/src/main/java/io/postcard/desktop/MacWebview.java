package io.postcard.desktop;

import java.nio.file.Path;
import java.util.Objects;

/**
 * JNI binding for the per-OS system-webview library (macOS: WKWebView in a window the
 * native side owns; Windows/Linux legs to follow).
 *
 * <p>Thin shell over native code by design: every decidable rule lives in tested Java
 * ({@link WebviewPolicy}, {@link DownloadTarget}) and the native side only renders and
 * reports back through {@link Host}. Loading or opening must never take down the file
 * server, so callers treat every failure here as "no window" and carry on headless.
 *
 * <p>Threading: AppKit requires window work on thread 0. The macOS launch carries
 * {@code -XstartOnFirstThread} so {@code main} runs there; the first
 * {@link #openWindow} already runs on thread 0, and later calls are marshalled there
 * inside the native code (serviced by AWT's pumping once the tray is up, or by the
 * parked event loop). Callers must not assume which thread they are on and must never
 * block thread 0 waiting for another thread that needs thread 0.
 *
 * <p>Lifecycle ordering (hard-won, verified across a dozen configurations): nothing
 * AWT may be initialized before the first page load completes — not the toolkit, not
 * a window — or WebKit's launch stalls permanently with no error. Hence the main
 * thread parks in {@link #runEventLoop()} immediately after opening, and AWT (tray)
 * only initializes on {@link Host#onFirstLoad}.
 */
public final class MacWebview {
    /** Callbacks the native side invokes on thread 0. */
    public interface Host {
        /** Pure navigation rule; when true the caller also opens the URL externally. */
        boolean shouldOpenExternally(String url);

        /** Absolute destination path for a download, resolved via {@link DownloadTarget}. */
        String downloadDestination(String suggestedName);

        /** A download the host saved through the window finished. */
        void onDownloadComplete(String fileName);

        /** The user closed the window (the X button). */
        void onWindowClosed();

        /** The first page load completed; safe to initialize AWT now. Fires once. */
        void onFirstLoad();
    }

    private MacWebview() {}

    /** Loads {@code libpostcard-webview.dylib} from the natives directory. */
    public static void ensureLoaded(Path nativesDir) {
        Objects.requireNonNull(nativesDir, "nativesDir");
        System.load(nativesDir.resolve("libpostcard-webview.dylib").toAbsolutePath().toString());
    }

    /**
     * Opens the dashboard window and starts loading the URL. Returns immediately; the
     * load proceeds once {@link #runEventLoop()} is pumping thread 0.
     *
     * @return 1 when the window exists (newly built or re-shown), 0 when it could not
     *         be built
     */
    public static native long openWindow(String url, Host host);

    /**
     * Parks the calling thread in the AppKit event loop until {@link #stopEventLoop}.
     * Must be called on thread 0 (macOS launch flag guarantees this for
     * {@code main}).
     */
    public static native void runEventLoop();

    /** Unparks {@link #runEventLoop()}. Safe to call when it is not parked. */
    public static native void stopEventLoop();

    /** Destroys the window. Safe with handle 0 (no-op). */
    public static native void closeWindow(long handle);

    /** Whether the window exists and is visible. Safe with handle 0 (false). */
    public static native boolean isVisible(long handle);
}
