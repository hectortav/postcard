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
        // One shutdown sequence, three callers: the tray's Quit item, the dashboard window's
        // close button, and the JVM shutdown hook. `cleanup` is safe inside a shutdown hook
        // (no System.exit); `quit` is the user-initiated variant that also exits.
        //
        // The dashboard is reached through a reference because the shutdown runnables are built
        // before it: the window's close button needs `quit`, and `cleanup` needs to dispose
        // CEF. Same reason for the notifier, which only exists once the tray is installed.
        final var dashboardRef = new java.util.concurrent.atomic.AtomicReference<io.postcard.desktop.Dashboard>();
        final var notify = new java.util.concurrent.atomic.AtomicReference<
            java.util.function.Consumer<io.postcard.desktop.Notifier.Event>>(
                io.postcard.desktop.Notifier.sink(_ -> {}));

        final Runnable cleanup = () -> {
            var d = dashboardRef.get();
            if (d != null) d.close();                                  // stop Chromium first
            try { app.jettyServer().stop(); } catch (Exception _) {}   // stop accepting connections
            Shutdown.drain(3, () -> {}, () -> server.hub().close());          // drain in-flight, close hub
            if (server.tempDir()) deleteTree(server.store().dir());
            log.info("postcard: goodbye");
        };
        final Runnable quit = () -> { cleanup.run(); System.exit(0); };

        if (!opts.headless) {
            // Closing the dashboard quits postcard, unconditionally. Deliberate, and it has
            // a cost: a phone mid-transfer is not consulted, so closing the window kills its
            // download. The simpler rule was chosen over that guarantee.
            var dashboard = io.postcard.desktop.EmbeddedDashboard.create(
                url,
                io.postcard.desktop.CefNatives.locate(),
                quit,
                io.postcard.desktop.DesktopIntegration::browse,
                event -> notify.get().accept(event));
            dashboardRef.set(dashboard);

            // Nothing is built until open() is called, so --no-browser costs no Chromium.
            if (!opts.noBrowser) dashboard.open();

            // macOS does not re-run main() when the user clicks the Dock icon of an
            // already-running bundled app; it sends a reopen event instead. Raising our own
            // window is the whole point: this used to exec a second browser every press.
            io.postcard.desktop.DesktopIntegration.installReopenHandler(url, _ -> dashboard.open());

            // Install the menu-bar / system-tray icon. install() returns
            // Optional.empty() on platforms without a status-notifier host
            // (headless Linux, SSH sessions, CI). The desktop integration is
            // gated behind !opts.headless so GraalVM native builds can stay
            // AWT-free by passing --headless at run time.
            java.util.Optional<java.awt.TrayIcon> trayIcon =
                io.postcard.desktop.SystemTrayController.install(url, dashboard::open, quit);
            trayIcon.ifPresent(icon -> Runtime.getRuntime().addShutdownHook(new Thread(() ->
                io.postcard.desktop.SystemTrayController.remove(icon), "postcard-tray-remove")));

            // Announce peer uploads and downloads through the tray. The browser's
            // Notification API is unavailable here: it requires a secure context and
            // postcard serves over http on a LAN address.
            notify.set(io.postcard.desktop.Notifier.forTray(trayIcon));
            server.setNotifier(event -> notify.get().accept(event), bind.getHostAddress());
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
