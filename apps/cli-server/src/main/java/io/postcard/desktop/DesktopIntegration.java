package io.postcard.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.awt.desktop.AppReopenedListener;
import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Desktop-shell integration that is not the tray icon.
 *
 * <p>Exists because of a macOS launch-model detail: a {@code jpackage} {@code .app} is a normal
 * single-instance bundled application. Clicking its Dock or Finder icon while the process is
 * already running does <em>not</em> start a second process and does not re-run {@code main()} —
 * macOS instead delivers a "reopen" Apple Event. An app that only calls
 * {@link Desktop#browse(URI)} once during startup therefore appears dead on every click after
 * the user closes the browser tab.
 *
 * <p>{@link #installReopenHandler} subscribes to that event and re-opens the server URL, which
 * is the behaviour a user expects from a menu-bar app: the window comes back. Windows and Linux
 * do not deliver the event ({@code APP_EVENT_REOPENED} is unsupported there) and the installer
 * reports {@code false} rather than failing — on those platforms relaunching the executable
 * starts a fresh process, which is already the correct behaviour.
 */
public final class DesktopIntegration {
    private static final Logger log = LoggerFactory.getLogger(DesktopIntegration.class);

    private DesktopIntegration() {}

    /** Default opener — hands the URL to the platform browser, never throwing. */
    public static void browse(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            log.info("postcard: could not open a browser for {} ({})", url, e.getMessage());
        }
    }

    /**
     * The action to run when the app is reopened. Split out from
     * {@link #installReopenHandler} so it is testable without an AWT desktop: the listener
     * registration is platform-gated, but the behaviour it triggers is not.
     *
     * <p>A failing opener is swallowed — a browser that refuses to launch must never take
     * down a running file server.
     */
    public static Runnable reopenAction(String url, Consumer<String> opener) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(opener, "opener");
        return () -> {
            log.info("postcard: reopened, re-opening {}", url);
            try {
                opener.accept(url);
            } catch (RuntimeException e) {
                log.info("postcard: reopen could not open a browser ({})", e.getMessage());
            }
        };
    }

    /**
     * Subscribe to the platform's "application reopened" event.
     *
     * @return {@code true} if the handler was installed (macOS), {@code false} on platforms
     *         that do not deliver the event or in a headless JVM. Never throws, so the caller
     *         can invoke it unguarded.
     */
    public static boolean installReopenHandler(String url, Consumer<String> opener) {
        var action = reopenAction(url, opener);
        try {
            if (!Desktop.isDesktopSupported()) return false;
            var desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) return false;
            desktop.addAppEventListener((AppReopenedListener) e -> action.run());
            log.info("postcard: reopen handler installed");
            return true;
        } catch (Throwable t) {
            // HeadlessException, UnsupportedOperationException, or a linkage error on a JRE
            // built without the desktop module. None of these are worth failing startup over.
            log.info("postcard: reopen handler unavailable ({})", t.getMessage());
            return false;
        }
    }
}
