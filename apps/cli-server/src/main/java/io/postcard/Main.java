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
        if (!opts.noBrowser) io.postcard.desktop.DesktopIntegration.browse(url);
        if (!opts.headless) {
            // macOS does not re-run main() when the user clicks the Dock icon of an
            // already-running bundled app; it sends a reopen event instead. Without
            // this the app looks dead once the browser tab is closed.
            io.postcard.desktop.DesktopIntegration.installReopenHandler(
                url, io.postcard.desktop.DesktopIntegration::browse);
            // Install the menu-bar / system-tray icon. install() returns
            // Optional.empty() on platforms without a status-notifier host
            // (headless Linux, SSH sessions, CI). The desktop integration is
            // gated behind !opts.headless so GraalVM native builds can stay
            // AWT-free by passing --headless at run time.
            java.util.Optional<java.awt.TrayIcon> trayIcon =
                io.postcard.desktop.SystemTrayController.install(url, () -> {
                    // Mirror the shutdown sequence from the JVM hook below so
                    // the user can quit the daemon cleanly from the menu bar.
                    log.info("postcard: tray quit invoked");
                    try { app.jettyServer().stop(); } catch (Exception ignored) {}
                    Shutdown.drain(3, () -> {}, () -> server.hub().close());
                    if (server.tempDir()) {
                        try {
                            java.nio.file.Files.walk(server.store().dir())
                                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
                        } catch (Exception ignored) {}
                    }
                    log.info("postcard: goodbye");
                    System.exit(0);
                });
            trayIcon.ifPresent(icon -> Runtime.getRuntime().addShutdownHook(new Thread(() ->
                io.postcard.desktop.SystemTrayController.remove(icon), "postcard-tray-remove")));
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                // 1. stop accepting new connections
                app.jettyServer().stop();
            } catch (Exception ignored) {}
            // 2. drain in-flight handlers (3s budget) + 3. 1s upper bound on wait once budget is exhausted
            Shutdown.drain(3, () -> {}, () -> {
                // 4. close WebSocket hub
                server.hub().close();
            });
            // 5. delete temp dir + log goodbye
            if (server.tempDir()) { try { java.nio.file.Files.walk(server.store().dir()).sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} }); } catch (Exception ignored) {} }
            log.info("postcard: goodbye");
        }));
    }
    private static int parsePortOrZero(String p) { return p.equalsIgnoreCase("auto") ? 0 : Integer.parseInt(p); }
}
