package io.postcard.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The dashboard, rendered by the operating system's webview in a window postcard owns.
 *
 * <p>Same contract as the JCEF implementation it replaces: the window is owned (Dock
 * presses raise it, closing it quits), initialization is lazy (constructing this starts
 * nothing; the first {@link #open()} builds the window, which is what keeps
 * {@code --no-browser} free of any browser), and a browser that refuses to start must
 * never take down a running file server.
 *
 * <p>The window is built natively as a top-level titled window: modern JDKs no longer
 * expose the peer NSView through JAWT (so there is nothing to embed into), and a
 * WKWebView created before AWT has shown a visible window never loads. AWT therefore
 * stays completely uninitialized until {@link MacWebview.Host#onFirstLoad} fires, at
 * which point the tray installs itself. The main thread parks in the AppKit event loop
 * ({@link MacWebview#runEventLoop()}) right after opening.
 *
 * <p>Shutdown needs no Chromium-style grace period — there are no helper processes to
 * reap — so {@code close()} reports termination immediately, exactly like the
 * never-opened path of the old implementation.
 */
public final class SystemWebviewDashboard implements Dashboard {
    private static final Logger log = LoggerFactory.getLogger(SystemWebviewDashboard.class);

    private final String url;
    private final Path nativesDir;
    private final Runnable onQuitRequested;
    private final Runnable onTerminated;
    private final Consumer<String> externalLinks;
    private final Consumer<Notifier.Event> notify;
    private final Runnable onFirstLoad;

    private long handle;
    private boolean opening;
    private boolean closed;
    private final AtomicBoolean trayInstalled = new AtomicBoolean();

    private SystemWebviewDashboard(String url, Path nativesDir, Runnable onQuitRequested, Runnable onTerminated,
                                   Consumer<String> externalLinks, Consumer<Notifier.Event> notify,
                                   Runnable onFirstLoad) {
        this.url = url;
        this.nativesDir = nativesDir;
        this.onQuitRequested = onQuitRequested;
        this.onTerminated = onTerminated;
        this.externalLinks = externalLinks;
        this.notify = notify;
        this.onFirstLoad = onFirstLoad;
    }

    /**
     * @param onQuitRequested run when the user asks postcard to quit, by closing the window.
     * @param onTerminated    run when the window is gone and the process is free to end.
     * @param externalLinks   opener for links that would otherwise replace the dashboard.
     * @param notify          channel for "saved X" notifications after a download completes.
     * @param onFirstLoad     runs once the first page load completes; installs the tray.
     */
    public static Dashboard create(String url, Path nativesDir, Runnable onQuitRequested, Runnable onTerminated,
                                   Consumer<String> externalLinks, Consumer<Notifier.Event> notify,
                                   Runnable onFirstLoad) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(nativesDir, "nativesDir");
        Objects.requireNonNull(onQuitRequested, "onQuitRequested");
        Objects.requireNonNull(onTerminated, "onTerminated");
        Objects.requireNonNull(externalLinks, "externalLinks");
        Objects.requireNonNull(notify, "notify");
        Objects.requireNonNull(onFirstLoad, "onFirstLoad");
        return new SystemWebviewDashboard(url, nativesDir, onQuitRequested, onTerminated,
            externalLinks, notify, onFirstLoad);
    }

    @Override
    public synchronized void open() {
        if (handle != 0 || opening || closed) return;
        opening = true;
        try {
            MacWebview.ensureLoaded(nativesDir);
            MacWebview.Host host = new MacWebview.Host() {
                @Override public boolean shouldOpenExternally(String navUrl) {
                    boolean external =
                        WebviewPolicy.decide(navUrl, url) == WebviewPolicy.Decision.OPEN_EXTERNALLY;
                    if (external) externalLinks.accept(navUrl);
                    return external;
                }

                @Override public String downloadDestination(String suggestedName) {
                    return DownloadTarget.resolve(downloadsDir(), suggestedName, Files::exists)
                        .toString();
                }

                @Override public void onDownloadComplete(String fileName) {
                    notify.accept(Notifier.saved(fileName));
                }

                @Override public void onWindowClosed() {
                    log.info("postcard: dashboard window closed");
                    onQuitRequested.run();
                }

                @Override public void onFirstLoad() {
                    // Once-only: didFinish can fire for subframes; tray installs once.
                    if (trayInstalled.compareAndSet(false, true)) onFirstLoad.run();
                }
            };
            // Synchronous by design: on thread 0 this runs the native build directly
            // (no blocking wait for another thread, which deadlocked in an earlier
            // revision). Off thread 0 the native side marshals and returns.
            long h = MacWebview.openWindow(url, host);
            opening = false;
            if (h == 0) {
                log.warn("postcard: webview open returned no handle");
            } else {
                handle = h;
                log.info("postcard: system dashboard opening ({})", url);
            }
        } catch (Exception | LinkageError e) {
            // A browser that refuses to start must never take down a running file server: the
            // URL and QR code are already on stdout, and phones can still reach the server.
            // LinkageError is caught too — a missing native library must degrade the same way.
            log.error("postcard: could not start the system webview ({})", e.getMessage(), e);
            opening = false;
        }
    }

    private static Path downloadsDir() {
        Path home = Path.of(System.getProperty("user.home", "."));
        Path downloads = home.resolve("Downloads");
        return Files.isDirectory(downloads) ? downloads : home;
    }

    @Override
    public synchronized boolean isOpen() {
        if (handle == 0) return false;
        try {
            return MacWebview.isVisible(handle);
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        opening = false;
        long h = handle;
        handle = 0;
        if (h != 0) {
            try { MacWebview.closeWindow(h); } catch (Exception | LinkageError e) {
                log.warn("postcard: webview close failed ({})", e.getMessage());
            }
        }
        // No helper processes to reap: termination is immediate.
        onTerminated.run();
    }
}
