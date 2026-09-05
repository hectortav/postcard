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
    /**
     * How long Chromium gets to shut down before postcard leaves without it. Comfortably over
     * the teardown's own worst case (a 3s drain plus a 1s forced close) so the deadline only
     * ever fires for a browser that is genuinely stuck, and short enough that a stuck one still
     * feels like quitting rather than hanging.
     */
    private static final long QUIT_GRACE_MILLIS = 5_000;

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
        // The dashboard is reached through a reference because the shutdown wiring is built
        // before it: the window's close button needs the quit sequence, and the quit sequence
        // needs to stop the browser. Same reason for the notifier, which only exists once the
        // tray is installed.
        final var dashboardRef = new java.util.concurrent.atomic.AtomicReference<io.postcard.desktop.Dashboard>();
        final var notify = new java.util.concurrent.atomic.AtomicReference<
            java.util.function.Consumer<io.postcard.desktop.Notifier.Event>>(
                io.postcard.desktop.Notifier.sink(_ -> {}));

        // Server-side teardown. Idempotent because it has two callers that can both fire on the
        // way out: the quit sequence, and the JVM shutdown hook that covers Ctrl-C and headless
        // runs. It never calls System.exit — only the quit sequence decides when the JVM dies.
        final var tornDown = new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable cleanup = () -> {
            if (!tornDown.compareAndSet(false, true)) return;
            try { app.jettyServer().stop(); } catch (Exception _) {}   // stop accepting connections
            Shutdown.drain(3, () -> {}, () -> server.hub().close());   // drain in-flight, close hub
            if (server.tempDir()) deleteTree(server.store().dir());
            log.info("postcard: goodbye");
        };

        if (!opts.headless) {
            // Quitting is a two-party affair once Chromium is in the process: the JVM may not
            // exit until CEF says it is done, or its helper processes are orphaned and crash.
            // Teardown runs on its own thread because the event thread is the one CEF shuts
            // down on, and the deadline is a daemon thread for the same reason — a wedged
            // event thread must not be able to hold the app open.
            final var quitSequence = io.postcard.desktop.QuitSequence.create(
                task -> new Thread(task, "postcard-quit").start(),
                cleanup,
                () -> { var d = dashboardRef.get(); if (d != null) d.close(); },
                (millis, action) -> {
                    var timer = new Thread(() -> {
                        try { Thread.sleep(millis); } catch (InterruptedException _) { return; }
                        log.warn("postcard: quit is taking longer than {}ms; leaving now", millis);
                        action.run();
                    }, "postcard-quit-deadline");
                    timer.setDaemon(true);
                    timer.start();
                },
                QUIT_GRACE_MILLIS,
                () -> System.exit(0));
            final Runnable quit = quitSequence::request;

            // Closing the dashboard quits postcard, unconditionally. Deliberate, and it has
            // a cost: a phone mid-transfer is not consulted, so closing the window kills its
            // download. The simpler rule was chosen over that guarantee.
            // macOS renders the dashboard in the OS webview; other platforms stay on the
            // embedded Chromium until their native legs land, at which point EmbeddedDashboard
            // and the whole JCEF bundle go away.
            var dashboard = isMacOs()
                ? io.postcard.desktop.SystemWebviewDashboard.create(
                    url,
                    io.postcard.desktop.WebviewNatives.locate(),
                    quit,
                    quitSequence::browserTerminated,
                    io.postcard.desktop.DesktopIntegration::browse,
                    event -> notify.get().accept(event))
                : io.postcard.desktop.EmbeddedDashboard.create(
                    url,
                    io.postcard.desktop.CefNatives.locate(),
                    quit,
                    quitSequence::browserTerminated,
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

    private static boolean isMacOs() { return System.getProperty("os.name", "").startsWith("Mac OS X"); }
}
