package io.postcard;

import io.postcard.net.HotspotInstructions;
import io.postcard.net.HotspotLauncher;
import io.postcard.net.NetworkInterfaceSelector;
import io.postcard.server.PostcardOptions;
import io.postcard.server.Server;
import io.postcard.util.Shutdown;
import picocli.CommandLine;
import java.net.Inet4Address;

public final class Main {
    public static void main(String[] args) throws Exception {
        var log = org.slf4j.LoggerFactory.getLogger(Main.class);
        var opts = new PostcardOptions();
        if (new CommandLine(opts).execute(args) != 0) System.exit(1);
        var server = new Server(opts);
        server.init();
        Inet4Address bind = null;
        if (opts.host != null) bind = (Inet4Address) java.net.InetAddress.getByName(opts.host);
        else bind = NetworkInterfaceSelector.selectPrimary();
        if (bind == null) {
            HotspotLauncher.Result hs = HotspotLauncher.attempt();
            if (hs != null && hs.interfaceIp() != null) { bind = hs.interfaceIp(); server.setMode("hotspot"); }
            else { System.err.println(hs == null ? "No LAN IPv4 and no usable hotspot." : hs.instructions().text()); System.exit(1); }
        }
        var app = server.build();
        int port = parsePortOrZero(opts.port);
        app.start(bind.getHostAddress(), port);
        int actual = app.port();
        var url = "http://" + bind.getHostAddress() + ":" + actual + "/";
        if (server.keyMaterial() != null) url += "#key=" + server.keyB64Url();

        // --pin wiring. The CLI derives the same AES key the receiver will
        // derive in the browser, stores it on the server as the "expected"
        // value, and prints the PIN to stdout so the sender can read it
        // aloud or paste it. The URL fragment gains &pin=... so the receiver
        // also has the PIN without an out-of-band channel.
        String pin = null;
        if (opts.pin != null) {
            if (opts.pin.isBlank()) {
                pin = String.format("%04d", (int) (Math.random() * 10000));
            } else {
                pin = opts.pin;
            }
            try {
                byte[] secret = server.secretBytes();
                String salt = io.postcard.security.PinSecurityEngine.saltFor(secret);
                byte[] expected = io.postcard.security.PinSecurityEngine.deriveKey(secret, pin, salt).getEncoded();
                server.setExpectedDerivedKey(expected);
                server.setPinRequired(true);
                url += (url.contains("#") ? "&" : "#") + "pin=" + pin;
                System.out.println("PIN: " + pin);
            } catch (Exception e) {
                System.err.println("postcard: --pin wiring failed: " + e.getMessage());
                System.exit(1);
            }
        }

        if (opts.authToken != null) url += (url.contains("?") ? "&" : "?") + "token=" + opts.authToken;
        System.out.println("BIND " + url);
        System.out.println(io.postcard.qr.QrRenderer.ansi(url));
        // One shutdown sequence, three callers: the tray's Quit item, the idle watcher,
        // and the JVM shutdown hook. `cleanup` is safe inside a shutdown hook (no
        // System.exit); `quit` is the user-initiated variant that also exits.
        final Runnable cleanup = () -> {
            try { app.jettyServer().stop(); } catch (Exception _) {}   // stop accepting connections
            Shutdown.drain(3, () -> {}, () -> server.hub().close());          // drain in-flight, close hub
            if (server.tempDir()) deleteTree(server.store().dir());
            log.info("postcard: goodbye");
        };
        final Runnable quit = () -> { cleanup.run(); System.exit(0); };

        // Open the dashboard as a chromeless window where possible, falling back to an
        // ordinary tab when no Chromium-family browser is installed.
        final java.util.function.Consumer<String> openDashboard = u -> {
            if (io.postcard.desktop.BrowserLauncher.launchAppWindow(u).isEmpty()) {
                io.postcard.desktop.DesktopIntegration.browse(u);
            }
        };

        if (!opts.noBrowser) openDashboard.accept(url);
        if (!opts.headless) {
            // macOS does not re-run main() when the user clicks the Dock icon of an
            // already-running bundled app; it sends a reopen event instead.
            io.postcard.desktop.DesktopIntegration.installReopenHandler(url, openDashboard);
            // Install the menu-bar / system-tray icon. install() returns
            // Optional.empty() on platforms without a status-notifier host
            // (headless Linux, SSH sessions, CI). The desktop integration is
            // gated behind !opts.headless so GraalVM native builds can stay
            // AWT-free by passing --headless at run time.
            java.util.Optional<java.awt.TrayIcon> trayIcon =
                io.postcard.desktop.SystemTrayController.install(url, quit);
            trayIcon.ifPresent(icon -> Runtime.getRuntime().addShutdownHook(new Thread(() ->
                io.postcard.desktop.SystemTrayController.remove(icon), "postcard-tray-remove")));
            // Announce peer uploads and downloads through the tray. The browser's
            // Notification API is unavailable here: it requires a secure context and
            // postcard serves over http on a LAN address.
            server.setNotifier(io.postcard.desktop.Notifier.forTray(trayIcon), bind.getHostAddress());
        }
        // Closing the last dashboard makes postcard quit. Connected clients -- including a
        // phone that still has the page open -- keep it alive, which is the behaviour
        // watching the browser process could never get right.
        if (!opts.noBrowser && !opts.headless) {
            io.postcard.desktop.IdleWatcher.start(
                () -> server.hub().size(),
                java.time.Duration.ofSeconds(2),
                java.time.Duration.ofSeconds(20),
                quit);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(cleanup, "postcard-shutdown"));
    }

    /** Best-effort recursive delete; depth-first so directories empty before removal. */
    private static void deleteTree(java.nio.file.Path root) {
        try (var walk = java.nio.file.Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception _) {} });
        } catch (Exception _) {}
    }

    private static int parsePortOrZero(String p) { return p.equalsIgnoreCase("auto") ? 0 : Integer.parseInt(p); }
}
