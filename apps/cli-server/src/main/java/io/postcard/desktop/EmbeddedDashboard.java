package io.postcard.desktop;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.handler.CefDownloadHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The dashboard, rendered by an embedded Chromium (JCEF) in a window postcard owns.
 *
 * <p>Replaces launching the user's own browser with {@code --app=}. Owning the window is what
 * makes the Dock icon raise something real, makes window lifetime directly observable, and
 * stops a second application appearing in the Dock beside postcard — every press of the Dock
 * icon used to exec Chrome again.
 *
 * <p><b>Initialization is lazy.</b> Constructing this starts nothing; the first {@link #open()}
 * builds the {@link CefApp}, client, browser and frame. That is what makes {@code --no-browser}
 * meaningful: postcard runs with a tray and no Chromium in the process tree until the user
 * actually asks for the dashboard, and pays the startup cost only then.
 *
 * <p>Deliberately <em>not</em> set: {@code cache_path}. The URL carries {@code #key=} and
 * possibly {@code &pin=}, so the profile stays in memory and nothing carrying a key is written
 * to disk. It also means there is no profile directory to clean up on exit.
 */
public final class EmbeddedDashboard implements Dashboard {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedDashboard.class);

    private final String url;
    private final Path nativesDir;
    private final Runnable onQuitRequested;
    private final Runnable onTerminated;
    private final Consumer<String> externalLinks;
    private final Consumer<Notifier.Event> notify;

    private CefApp cefApp;
    private CefClient client;
    private JFrame frame;

    private EmbeddedDashboard(String url, Path nativesDir, Runnable onQuitRequested, Runnable onTerminated,
                              Consumer<String> externalLinks, Consumer<Notifier.Event> notify) {
        this.url = url;
        this.nativesDir = nativesDir;
        this.onQuitRequested = onQuitRequested;
        this.onTerminated = onTerminated;
        this.externalLinks = externalLinks;
        this.notify = notify;
    }

    /**
     * @param onQuitRequested run when the user asks postcard to quit, either by closing the
     *                        window or through Cmd+Q. Closing the dashboard quits.
     * @param onTerminated    run when Chromium has finished shutting down and the process is
     *                        free to end. Nothing else may end it: see {@link QuitSequence}.
     * @param externalLinks   opener for links that would otherwise replace the dashboard
     * @param notify          channel for "saved X" notifications after a download completes
     */
    public static Dashboard create(String url, Path nativesDir, Runnable onQuitRequested, Runnable onTerminated,
                                   Consumer<String> externalLinks, Consumer<Notifier.Event> notify) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(nativesDir, "nativesDir");
        Objects.requireNonNull(onQuitRequested, "onQuitRequested");
        Objects.requireNonNull(onTerminated, "onTerminated");
        Objects.requireNonNull(externalLinks, "externalLinks");
        Objects.requireNonNull(notify, "notify");
        return new EmbeddedDashboard(url, nativesDir, onQuitRequested, onTerminated, externalLinks, notify);
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
        } catch (Exception e) {
            // A browser that refuses to start must never take down a running file server: the
            // URL and QR code are already on stdout, and phones can still reach the server.
            log.error("postcard: could not start the embedded browser ({})", e.getMessage(), e);
        }
    }

    private void build() throws Exception {
        var builder = new CefAppBuilder();
        builder.setInstallDir(nativesDir.toFile());
        builder.setSkipInstallation(true);                              // natives are bundled
        builder.getCefSettings().windowless_rendering_enabled = false;  // windowed: native drag-and-drop
        builder.setAppHandler(new MavenCefAppHandlerAdapter() {         // never CefApp.addAppHandler
            /**
             * Cmd+Q, the Dock's Quit and a SIGTERM all arrive here rather than through the
             * window listener. Returning true claims the request: CefApp would otherwise
             * dispose itself behind our back, tearing down the browser while the file server
             * kept running and leaving nothing able to end the process.
             */
            @Override public boolean onBeforeTerminate() {
                onQuitRequested.run();
                return true;
            }

            /**
             * The only signal that Chromium has finished and its helper processes are reaped.
             * Ending the JVM before this orphans them; not ending it at all leaves a windowless
             * process no one can quit.
             */
            @Override public void stateHasChanged(CefApp.CefAppState state) {
                super.stateHasChanged(state);
                if (state == CefApp.CefAppState.TERMINATED) onTerminated.run();
            }
        });

        cefApp = builder.build();
        client = cefApp.createClient();
        client.addDownloadHandler(downloadHandler());
        client.addLifeSpanHandler(popupBlocker());

        CefBrowser browser = client.createBrowser(url, false, false);

        EventQueue.invokeLater(() -> {
            JFrame f = new JFrame("postcard");
            f.setIconImage(TrayIconFactory.create(256));
            f.getContentPane().add(browser.getUIComponent(), BorderLayout.CENTER);
            f.setSize(1000, 720);
            f.setLocationRelativeTo(null);
            f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            f.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    log.info("postcard: dashboard window closed");
                    onQuitRequested.run();
                }
            });
            f.setVisible(true);
            synchronized (EmbeddedDashboard.this) { frame = f; }
        });
        log.info("postcard: embedded dashboard opening ({})", url);
    }

    /** Without a download handler CEF silently drops every download. */
    private CefDownloadHandlerAdapter downloadHandler() {
        return new CefDownloadHandlerAdapter() {
            @Override
            public boolean onBeforeDownload(CefBrowser browser, CefDownloadItem item,
                                            String suggestedName, CefBeforeDownloadCallback callback) {
                Path target = DownloadTarget.resolve(downloadsDir(), suggestedName, Files::exists);
                log.info("postcard: saving download to {}", target);
                callback.Continue(target.toString(), false);   // false: no per-file save dialog
                return true;
            }

            @Override
            public void onDownloadUpdated(CefBrowser browser, CefDownloadItem item,
                                          CefDownloadItemCallback callback) {
                if (item.isComplete()) notify.accept(Notifier.saved(item.getSuggestedFileName()));
            }
        };
    }

    /** Outward links open in the user's real browser rather than replacing the dashboard. */
    private CefLifeSpanHandlerAdapter popupBlocker() {
        return new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser browser, CefFrame frame, String targetUrl,
                                         String targetFrameName) {
                externalLinks.accept(targetUrl);
                return true;   // true: do not open a popup window inside the app
            }
        };
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

    /**
     * Asks Chromium to shut down and returns before it has. Disposing a {@link CefApp} only
     * schedules the teardown; it completes over several turns of the event thread, and reports
     * itself through the {@code onTerminated} callback. Blocking here would deadlock — the
     * thread we would block is the one CEF needs in order to finish.
     */
    @Override
    public synchronized void close() {
        if (frame != null) {
            JFrame f = frame;
            frame = null;
            EventQueue.invokeLater(f::dispose);
        }
        if (client != null) { client.dispose(); client = null; }
        if (cefApp != null) {
            cefApp.dispose();
            cefApp = null;
            log.info("postcard: embedded dashboard shutting down");
        } else {
            // --no-browser, or a Chromium that never started: there is nothing to wait for,
            // and waiting anyway would stall every quit by the full grace period.
            onTerminated.run();
        }
    }
}
