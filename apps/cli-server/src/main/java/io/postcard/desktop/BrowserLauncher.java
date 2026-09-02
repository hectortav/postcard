package io.postcard.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Opens the postcard dashboard in a chromeless browser window — a real OS window with its own
 * Dock/taskbar entry, close button and Cmd+Q, but rendered by the user's own browser, so the
 * page is pixel-identical to a normal tab and drag-and-drop, downloads and WebCrypto all keep
 * working. This is the same mechanism Chrome uses for installed PWAs.
 *
 * <p>Two flags do the work:
 * <ul>
 *   <li>{@code --app=<url>} creates the window without a tab strip or omnibox.</li>
 *   <li>{@code --no-first-run} / {@code --no-default-browser-check} suppress Chrome's welcome
 *       and default-browser prompts, which would otherwise appear inside the app window.</li>
 * </ul>
 *
 * <p>Deliberately <em>not</em> passed: {@code --user-data-dir}. A dedicated profile would give a
 * process whose lifetime tracks the window, but it starts a second Chrome application instance
 * with its own generic Chrome icon in the Dock next to postcard's, and clicking that icon opens
 * the empty profile's homepage. Reusing the user's own Chrome keeps the Dock clean; window
 * lifetime is observed by {@link IdleWatcher} instead.
 *
 * <p>Only Chromium-family browsers support {@code --app}. When none is installed the caller
 * should fall back to {@link DesktopIntegration#browse(String)} and a normal tab.
 */
public final class BrowserLauncher {
    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    private BrowserLauncher() {}

    /**
     * Candidate browser executables for the given OS, in preference order.
     * Chrome first, then Chromium, Edge, Brave, Vivaldi.
     */
    public static List<Path> defaultCandidates(String osName, Path home) {
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(home, "home");
        if (osName.startsWith("Mac")) {
            return macCandidates(home);
        }
        if (osName.startsWith("Windows")) {
            return windowsCandidates(home);
        }
        return List.of(
            Path.of("/usr/bin/google-chrome"),
            Path.of("/usr/bin/google-chrome-stable"),
            Path.of("/usr/bin/chromium"),
            Path.of("/usr/bin/chromium-browser"),
            Path.of("/usr/bin/microsoft-edge"),
            Path.of("/usr/bin/brave-browser"),
            Path.of("/usr/bin/vivaldi"));
    }

    private static List<Path> macCandidates(Path home) {
        var names = List.of(
            "Google Chrome.app/Contents/MacOS/Google Chrome",
            "Chromium.app/Contents/MacOS/Chromium",
            "Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            "Brave Browser.app/Contents/MacOS/Brave Browser",
            "Vivaldi.app/Contents/MacOS/Vivaldi");
        // System-wide installs win over per-user ones, matching how macOS resolves apps.
        return names.stream()
            .<Path>mapMulti((name, sink) -> {
                sink.accept(Path.of("/Applications").resolve(name));
                sink.accept(home.resolve("Applications").resolve(name));
            })
            .toList();
    }

    private static List<Path> windowsCandidates(Path home) {
        var roots = List.of(
            Path.of("C:\\Program Files"),
            Path.of("C:\\Program Files (x86)"),
            home.resolve("AppData\\Local"));
        var names = List.of(
            "Google\\Chrome\\Application\\chrome.exe",
            "Chromium\\Application\\chrome.exe",
            "Microsoft\\Edge\\Application\\msedge.exe",
            "BraveSoftware\\Brave-Browser\\Application\\brave.exe",
            "Vivaldi\\Application\\vivaldi.exe");
        return names.stream()
            .<Path>mapMulti((name, sink) -> roots.forEach(root -> sink.accept(root.resolve(name))))
            .toList();
    }

    /** First candidate that exists, honouring preference order. */
    public static Optional<Path> find(List<Path> candidates, Predicate<Path> exists) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(exists, "exists");
        return candidates.stream().filter(exists).findFirst();
    }

    /** Convenience overload probing the real filesystem for the current platform. */
    public static Optional<Path> findInstalled() {
        var os = System.getProperty("os.name", "");
        var home = Path.of(System.getProperty("user.home", "/"));
        return find(defaultCandidates(os, home), Files::isExecutable);
    }

    /** The argv for a chromeless window. Split out from launching so it can be asserted directly. */
    public static List<String> appWindowCommand(Path browser, String url) {
        Objects.requireNonNull(browser, "browser");
        Objects.requireNonNull(url, "url");
        if (url.isBlank()) throw new IllegalArgumentException("url must not be blank");
        return List.of(
            browser.toString(),
            "--app=" + url,
            "--no-first-run",
            "--no-default-browser-check");
    }

    /**
     * Launch the dashboard in a chromeless window.
     *
     * <p>The returned process is <em>not</em> a window handle: when the user's browser is already
     * running it delegates and exits immediately. Window lifetime is observed by
     * {@link IdleWatcher} instead. The return value only reports whether the launch was attempted.
     *
     * @return the launched process, or empty when no Chromium-family browser is installed or the
     *         launch failed. Never throws.
     */
    public static Optional<Process> launchAppWindow(String url) {
        try {
            var browser = findInstalled();
            if (browser.isEmpty()) {
                log.info("postcard: no Chromium-family browser found; opening a normal tab");
                return Optional.empty();
            }
            var cmd = appWindowCommand(browser.get(), url);
            log.info("postcard: opening app window via {}", browser.get());
            return Optional.of(new ProcessBuilder(cmd).redirectErrorStream(true).start());
        } catch (Exception e) {
            log.info("postcard: could not open an app window ({}); opening a normal tab", e.getMessage());
            return Optional.empty();
        }
    }
}
