package io.sendme.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.net.URI;
import java.util.Optional;

/**
 * Installs and removes the sendme menu-bar / system-tray icon.
 *
 * <p>The icon has three menu items:
 * <ol>
 *   <li><b>Open Dashboard</b> — re-invokes {@link java.awt.Desktop#browse(URI)} on the
 *       server URL.</li>
 *   <li><b>Copy Local URL</b> — places the server URL on the system clipboard.</li>
 *   <li><b>Quit</b> — invokes the caller-supplied shutdown {@link Runnable}. The
 *       controller deliberately does not call {@link System#exit(int)} itself; the
 *       caller owns the shutdown sequence.</li>
 * </ol>
 *
 * <p>All AWT access is confined to {@link #install(String, Runnable)} and the
 * action listeners. The class does not hold any AWT state as fields, so the JVM
 * class loader will not pull in {@code java.awt.SystemTray} until {@code install()}
 * is actually invoked. Gate the call behind a {@code !headless} check at the call
 * site to keep GraalVM native-image builds clean.
 */
public final class SystemTrayController {
    private static final Logger log = LoggerFactory.getLogger(SystemTrayController.class);

    private SystemTrayController() {}

    /**
     * Install the tray icon. Returns {@link Optional#empty()} when the platform
     * does not support {@link SystemTray} (headless Linux without a status-notifier
     * host, SSH sessions, CI). The caller should treat the empty result as "no
     * tray available, continue without it".
     */
    public static Optional<TrayIcon> install(String url, Runnable onQuit) {
        try {
            if (!SystemTray.isSupported()) {
                log.info("SystemTray not supported on this platform; running without tray icon");
                return Optional.empty();
            }
            SystemTray tray = SystemTray.getSystemTray();

            TrayIcon icon = buildIcon(url, onQuit);
            try {
                tray.add(icon);
            } catch (java.awt.AWTException e) {
                log.info("SystemTray add failed ({}); running without tray icon", e.getMessage());
                return Optional.empty();
            }
            log.info("sendme tray icon installed (url={})", url);
            return Optional.of(icon);
        } catch (InternalError | RuntimeException e) {
            // JDK-8303011: SystemTray on Linux can throw InternalError when the
            // indicator host is missing. Treat as "unsupported" and continue.
            log.info("SystemTray unavailable ({}); running without tray icon", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Remove a previously installed tray icon. No-op when the icon is null.
     * Safe to call from a shutdown hook.
     */
    public static void remove(TrayIcon icon) {
        if (icon == null) return;
        try {
            SystemTray tray = SystemTray.getSystemTray();
            tray.remove(icon);
            log.info("sendme tray icon removed");
        } catch (Exception e) {
            // Best-effort: tray may already be gone, or the platform may have
            // dropped support mid-session. Don't propagate from shutdown.
            log.warn("Failed to remove tray icon: {}", e.getMessage());
        }
    }

    private static TrayIcon buildIcon(String url, Runnable onQuit) {
        Image image = TrayIconFactory.create(64);
        TrayIcon icon = new TrayIcon(image, "sendme — " + url);
        icon.setToolTip("sendme — " + url);
        icon.setImageAutoSize(true);

        PopupMenu menu = new PopupMenu();

        MenuItem open = new MenuItem("Open Dashboard");
        open.addActionListener((ActionEvent e) -> openDashboard(url, icon));
        menu.add(open);

        MenuItem copy = new MenuItem("Copy Local URL");
        copy.addActionListener((ActionEvent e) -> copyUrl(url));
        menu.add(copy);

        menu.addSeparator();

        MenuItem quit = new MenuItem("Quit");
        quit.addActionListener((ActionEvent e) -> {
            log.info("sendme tray: quit requested");
            if (onQuit != null) onQuit.run();
        });
        menu.add(quit);

        icon.setPopupMenu(menu);
        return icon;
    }

    private static void openDashboard(String url, TrayIcon icon) {
        try {
            // java.awt.Desktop may throw HeadlessException in some CI/test envs
            // and UnsupportedOperationException on platforms without a registered
            // handler (rare). Both are logged at WARN; the user can copy the URL
            // via the next menu item.
            java.awt.Desktop.getDesktop().browse(URI.create(url));
            log.info("sendme tray: opened dashboard ({})", url);
        } catch (Exception e) {
            log.warn("sendme tray: could not open browser ({}); user should copy URL from menu", e.getMessage());
            // Best-effort user feedback: pop a tray balloon if the EDT is up.
            try {
                java.awt.EventQueue.invokeLater(() ->
                    icon.displayMessage("sendme", "Could not open browser: " + url, TrayIcon.MessageType.INFO));
            } catch (Exception ignored) {
                // EventQueue not available (e.g. test env) — already logged above.
            }
        }
    }

    private static void copyUrl(String url) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
            log.info("sendme tray: copied URL to clipboard");
        } catch (Exception e) {
            log.warn("sendme tray: clipboard unavailable ({}); user must copy URL manually", e.getMessage());
        }
    }
}
