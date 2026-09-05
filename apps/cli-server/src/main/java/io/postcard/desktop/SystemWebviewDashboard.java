package io.postcard.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.EventQueue;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
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
 * <p>Embedding goes through JAWT into a {@link Canvas} inside a {@link JFrame}: AWT
 * already owns the AppKit main thread and pumps its runloop, so unlike a hand-rolled
 * {@code NSApp} loop this needs no {@code -XstartOnFirstThread} and no runloop
 * management of our own (both were spike findings).
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

    private JFrame frame;
    private long handle;

    private SystemWebviewDashboard(String url, Path nativesDir, Runnable onQuitRequested, Runnable onTerminated,
                                   Consumer<String> externalLinks, Consumer<Notifier.Event> notify) {
        this.url = url;
        this.nativesDir = nativesDir;
        this.onQuitRequested = onQuitRequested;
        this.onTerminated = onTerminated;
        this.externalLinks = externalLinks;
        this.notify = notify;
    }

    /**
     * @param onQuitRequested run when the user asks postcard to quit, by closing the window.
     * @param onTerminated    run when the window is gone and the process is free to end.
     * @param externalLinks   opener for links that would otherwise replace the dashboard.
     * @param notify          channel for "saved X" notifications after a download completes.
     */
    public static Dashboard create(String url, Path nativesDir, Runnable onQuitRequested, Runnable onTerminated,
                                   Consumer<String> externalLinks, Consumer<Notifier.Event> notify) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(nativesDir, "nativesDir");
        Objects.requireNonNull(onQuitRequested, "onQuitRequested");
        Objects.requireNonNull(onTerminated, "onTerminated");
        Objects.requireNonNull(externalLinks, "externalLinks");
        Objects.requireNonNull(notify, "notify");
        return new SystemWebviewDashboard(url, nativesDir, onQuitRequested, onTerminated,
            externalLinks, notify);
    }

    @Override
    public synchronized void open() {
        if (frame != null) {
            JFrame f = frame;
            EventQueue.invokeLater(() -> { f.setVisible(true); f.toFront(); f.requestFocus(); });
            return;
        }
        try {
            build();
        } catch (Exception | LinkageError e) {
            // A browser that refuses to start must never take down a running file server: the
            // URL and QR code are already on stdout, and phones can still reach the server.
            // LinkageError is caught too — a missing native library must degrade the same way.
            log.error("postcard: could not start the system webview ({})", e.getMessage(), e);
        }
    }

    private void build() throws Exception {
        MacWebview.ensureLoaded(nativesDir);
        EventQueue.invokeAndWait(() -> {
            JFrame f = new JFrame("postcard");
            f.setIconImage(TrayIconFactory.create(256));
            Canvas canvas = new Canvas();
            f.getContentPane().add(canvas, BorderLayout.CENTER);
            f.setSize(1000, 720);
            f.setLocationRelativeTo(null);
            f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            f.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    log.info("postcard: dashboard window closed");
                    onQuitRequested.run();
                }
            });
            f.setVisible(true); // realizes the Canvas peer, which attach() requires
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
            };
            handle = MacWebview.attach(canvas, url, host);
            frame = f;
            log.info("postcard: system dashboard opening ({})", url);
        });
    }

    private static Path downloadsDir() {
        Path home = Path.of(System.getProperty("user.home", "."));
        Path downloads = home.resolve("Downloads");
        return Files.isDirectory(downloads) ? downloads : home;
    }

    @Override
    public synchronized boolean isOpen() {
        return frame != null && frame.isVisible();
    }

    @Override
    public synchronized void close() {
        if (frame != null) {
            JFrame f = frame;
            frame = null;
            long h = handle;
            handle = 0;
            EventQueue.invokeLater(() -> {
                if (h != 0) {
                    try { MacWebview.detach(h); } catch (Exception | LinkageError e) {
                        log.warn("postcard: webview detach failed ({})", e.getMessage());
                    }
                }
                f.dispose();
            });
        }
        // No helper processes to reap: termination is immediate.
        onTerminated.run();
    }
}
