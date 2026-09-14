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

    /** Bad command line: an unparsable flag, or a --port that is neither a number nor 'auto'. */
    static final int EXIT_BAD_ARGS = 2;
    /** The requested address and port could not be bound. */
    static final int EXIT_PORT_UNAVAILABLE = 3;
    /** No LAN address to serve on, and no hotspot fallback. */
    static final int EXIT_NO_INTERFACE = 4;
    /** --pin was supplied but could not be armed. */
    static final int EXIT_PIN_FAILED = 5;

    /** Innermost message of a cause chain; bind failures wrap the useful part several deep. */
    static String rootCauseMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        var m = c.getMessage();
        return (m == null || m.isBlank()) ? c.getClass().getSimpleName() : m;
    }

    public static void main(String[] args) throws Exception {
        var opts = new PostcardOptions();
        var cmd = new CommandLine(opts);
        // execute() returns ExitCode.OK when it has *handled* --help or --version, so the
        // old `!= 0` guard fell through and started a real server -- binding a port, creating
        // a temp directory and opening a window -- after printing the usage text.
        if (cmd.execute(args) != 0) System.exit(EXIT_BAD_ARGS);
        var parsed = cmd.getParseResult();
        if (parsed.isUsageHelpRequested() || parsed.isVersionHelpRequested()) return;
        // Before the first logger exists: logback reads this when it configures itself and
        // will not revisit it.
        String level = opts.resolvedLogLevel();
        System.setProperty("postcard.log.level", level);
        // Jetty and Javalin stay quiet unless debug output was actually asked for.
        System.setProperty("postcard.log.level.libraries",
            (level.equals("DEBUG") || level.equals("TRACE")) ? level : "WARN");
        var log = org.slf4j.LoggerFactory.getLogger(Main.class);
        var server = new Server(opts);
        server.init();
        // A previous run killed mid-upload can leave spooled bodies behind; nothing is in
        // flight yet, so this is the one safe moment to reclaim them.
        server.sweepUploadSpool();
        Inet4Address bind = null;
        if (opts.host != null) bind = (Inet4Address) java.net.InetAddress.getByName(opts.host);
        else bind = NetworkInterfaceSelector.selectPrimary();
        if (bind == null) {
            HotspotLauncher.Result hs = HotspotLauncher.attempt();
            if (hs != null && hs.interfaceIp() != null) { bind = hs.interfaceIp(); server.setMode("hotspot"); }
            else {
                System.err.println(hs == null
                    ? "postcard: no usable LAN address found.\n"
                      + "  Connect to Wi-Fi or Ethernet, or name an address yourself with --host <addr>."
                    : hs.instructions().text());
                System.exit(EXIT_NO_INTERFACE);
            }
        }
        // PIN management is owner-only (see Server.isOwnerIp): the dashboard call
        // comes from this same address, receivers from theirs.
        server.setBindHost(bind.getHostAddress());
        var app = server.build();
        int port;
        try {
            port = parsePortOrZero(opts.port);
        } catch (NumberFormatException e) {
            System.err.println("postcard: --port must be a number or 'auto' (got '" + opts.port + "')");
            System.exit(EXIT_BAD_ARGS);
            return;
        }
        try {
            app.start(bind.getHostAddress(), port);
        } catch (Exception e) {
            // Javalin wraps a bind failure; the stack trace it used to print told the user
            // nothing they could act on.
            System.err.println("postcard: could not bind " + bind.getHostAddress() + ":" + port
                + " (" + rootCauseMessage(e) + ")\n"
                + "  Another process may already hold that port. Try --port auto.");
            System.exit(EXIT_PORT_UNAVAILABLE);
            return;
        }
        int actual = app.port();
        server.setBindPort(actual);
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
                // Same path the dashboard's PIN settings use at runtime: validates
                // the shape, ensures the KDF secret and arms the gate.
                server.enablePin(pin);
                url += (url.contains("#") ? "&" : "#") + "pin=" + pin;
                System.out.println("PIN: " + pin);
            } catch (Exception e) {
                System.err.println("postcard: --pin wiring failed: " + e.getMessage());
                System.exit(EXIT_PIN_FAILED);
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
            // Spooled upload bodies live under the shared directory. In --path mode that
            // directory is the user's own and must survive, but the spool must not.
            server.sweepUploadSpool();
            try { java.nio.file.Files.deleteIfExists(server.uploadSpoolDir()); } catch (Exception _) {}
            if (server.tempDir()) deleteTree(server.store().dir());
            log.info("postcard: goodbye");
        };

        // macOS renders the dashboard in the OS webview and parks thread 0 in the AppKit
        // event loop. Decided here because it changes how the process is allowed to exit.
        final boolean macWindow = isMacOs() && !opts.noBrowser;
        // Set once the JVM has begun its own shutdown (Ctrl-C, SIGTERM), so thread 0 knows to
        // let that finish instead of calling System.exit, which blocks forever mid-shutdown.
        final var shuttingDown = new java.util.concurrent.atomic.AtomicBoolean();

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
                macWindow
                    // Teardown has already finished by the time this runs, so there is
                    // nothing left to wait for. Earlier revisions asked the AppKit event loop
                    // to return and then exited from main; that never came back once AWT was
                    // up for the tray, so every window close sat on a two-second fallback
                    // timer before giving up. See MacWebview.terminateNow.
                    ? () -> {
                        try {
                            io.postcard.desktop.MacWebview.terminateNow();
                        } catch (Throwable t) {
                            // No native library, or it refused to load. Leave the ordinary way.
                            log.warn("postcard: leaving without the native exit ({})", t.getMessage());
                            Runtime.getRuntime().halt(0);
                        }
                    }
                    : () -> System.exit(0));
            final Runnable quit = quitSequence::request;

            // Closing the dashboard quits postcard, unconditionally. Deliberate, and it has
            // a cost: a phone mid-transfer is not consulted, so closing the window kills its
            // download. The simpler rule was chosen over that guarantee.
            //
            // macOS renders the dashboard in the OS webview; other platforms stay on the
            // embedded Chromium until their native legs land, at which point EmbeddedDashboard
            // and the whole JCEF bundle go away. macOS with --no-browser also stays on the
            // lazy JCEF dashboard: its window only ever opens from the tray, long after AWT
            // is up, which is exactly the order the system webview cannot tolerate.
            final String dashboardUrl = url;
            final java.net.Inet4Address bindAddr = bind;
            final Runnable installTray = () -> installTray(dashboardUrl, dashboardRef.get(), quit,
                notify, server, bindAddr);
            var dashboard = macWindow
                ? io.postcard.desktop.SystemWebviewDashboard.create(
                    url,
                    io.postcard.desktop.WebviewNatives.locate(),
                    quit,
                    quitSequence::browserTerminated,
                    io.postcard.desktop.DesktopIntegration::browse,
                    event -> notify.get().accept(event),
                    installTray)
                : io.postcard.desktop.EmbeddedDashboard.create(
                    url,
                    io.postcard.desktop.CefNatives.locate(),
                    quit,
                    quitSequence::browserTerminated,
                    io.postcard.desktop.DesktopIntegration::browse,
                    event -> notify.get().accept(event));
            dashboardRef.set(dashboard);

            // Nothing is built until open() is called, so --no-browser costs no browser.
            if (!opts.noBrowser) dashboard.open();

            if (!macWindow) installTray.run();

            // macOS does not re-run main() when the user clicks the Dock icon of an
            // already-running bundled app; it sends a reopen event instead. Raising our own
            // window is the whole point: this used to exec a second browser every press.
            // (On the system-webview path this installs with the tray, on first load.)
            if (macWindow) {
                final Runnable shutdownMac = () -> {
                    shuttingDown.set(true);
                    cleanup.run();
                    try { io.postcard.desktop.MacWebview.stopEventLoop(); } catch (Throwable _) {}
                };
                Runtime.getRuntime().addShutdownHook(new Thread(shutdownMac, "postcard-shutdown"));
                // Park thread 0 in the AppKit loop; the window, WebKit and (later) AWT
                // all live off it. Ctrl-C unparks via the hook above.
                io.postcard.desktop.MacWebview.runEventLoop();
                // Unparked: either the user quit, or the JVM is already on its way out. In the
                // second case System.exit would block here until the process died anyway, so
                // just return and let the hook finish.
                if (!shuttingDown.get()) {
                    cleanup.run();
                    System.exit(0);
                }
            } else {
                Runtime.getRuntime().addShutdownHook(new Thread(cleanup, "postcard-shutdown"));
            }
        } else {
            Runtime.getRuntime().addShutdownHook(new Thread(cleanup, "postcard-shutdown"));
        }
    }

    /**
     * Menu-bar icon, Dock-reopen handling and upload/download announcements. On the
     * system-webview path this runs on first page load (initializing AWT any earlier
     * wedges WebKit's launch); everywhere else it runs during startup.
     */
    private static void installTray(String url, io.postcard.desktop.Dashboard dashboard, Runnable quit,
            java.util.concurrent.atomic.AtomicReference<
                java.util.function.Consumer<io.postcard.desktop.Notifier.Event>> notify,
            io.postcard.server.Server server, java.net.Inet4Address bind) {
        io.postcard.desktop.DesktopIntegration.installReopenHandler(url, _ -> dashboard.open());
        io.postcard.desktop.DesktopIntegration.installQuitHandler(quit);

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
