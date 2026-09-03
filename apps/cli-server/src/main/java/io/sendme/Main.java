package io.sendme;

import io.sendme.net.HotspotInstructions;
import io.sendme.net.HotspotLauncher;
import io.sendme.net.NetworkInterfaceSelector;
import io.sendme.server.SendmeOptions;
import io.sendme.server.Server;
import io.sendme.util.Shutdown;
import picocli.CommandLine;
import java.net.Inet4Address;

public final class Main {
    public static void main(String[] args) throws Exception {
        var log = org.slf4j.LoggerFactory.getLogger(Main.class);
        var opts = new SendmeOptions();
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
        if (opts.authToken != null) url += (url.contains("?") ? "&" : "?") + "token=" + opts.authToken;
        System.out.println("BIND " + url);
        System.out.println(io.sendme.qr.QrRenderer.ansi(url));
        if (!opts.noBrowser) java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        if (!opts.headless) {
            // Install the menu-bar / system-tray icon. install() returns
            // Optional.empty() on platforms without a status-notifier host
            // (headless Linux, SSH sessions, CI). The desktop integration is
            // gated behind !opts.headless so GraalVM native builds can stay
            // AWT-free by passing --headless at run time.
            java.util.Optional<java.awt.TrayIcon> trayIcon =
                io.sendme.desktop.SystemTrayController.install(url, () -> {
                    // Mirror the shutdown sequence from the JVM hook below so
                    // the user can quit the daemon cleanly from the menu bar.
                    log.info("sendme: tray quit invoked");
                    try { app.jettyServer().stop(); } catch (Exception ignored) {}
                    Shutdown.drain(3, () -> {}, () -> server.hub().close());
                    if (server.tempDir()) {
                        try {
                            java.nio.file.Files.walk(server.store().dir())
                                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
                        } catch (Exception ignored) {}
                    }
                    log.info("sendme: goodbye");
                    System.exit(0);
                });
            trayIcon.ifPresent(icon -> Runtime.getRuntime().addShutdownHook(new Thread(() ->
                io.sendme.desktop.SystemTrayController.remove(icon), "sendme-tray-remove")));
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
            log.info("sendme: goodbye");
        }));
    }
    private static int parsePortOrZero(String p) { return p.equalsIgnoreCase("auto") ? 0 : Integer.parseInt(p); }
}
