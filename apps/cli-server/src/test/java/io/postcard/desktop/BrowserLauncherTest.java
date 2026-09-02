package io.postcard.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BrowserLauncherTest {

    private static final Path HOME = Path.of("/Users/someone");

    @Test void findReturnsTheFirstCandidateThatExists() {
        var candidates = List.of(Path.of("/a/chrome"), Path.of("/b/edge"), Path.of("/c/brave"));
        var present = Set.of(Path.of("/b/edge"), Path.of("/c/brave"));
        assertEquals(Path.of("/b/edge"),
            BrowserLauncher.find(candidates, present::contains).orElseThrow(),
            "detection must honour candidate order, not filesystem order");
    }

    @Test void findReturnsEmptyWhenNoCandidateExists() {
        var candidates = List.of(Path.of("/a/chrome"), Path.of("/b/edge"));
        assertTrue(BrowserLauncher.find(candidates, p -> false).isEmpty(),
            "a machine with only Safari or Firefox must fall back, not crash");
    }

    @Test void macCandidatesPreferChromeAndAreAllApplicationBinaries() {
        var candidates = BrowserLauncher.defaultCandidates("Mac OS X", HOME);
        assertFalse(candidates.isEmpty());
        assertTrue(candidates.getFirst().toString().contains("Google Chrome"),
            "Chrome is the first choice, was " + candidates.getFirst());
        assertTrue(candidates.stream().allMatch(p -> p.toString().contains("/Contents/MacOS/")),
            "macOS candidates must point at the executable inside the bundle");
    }

    @Test void linuxCandidatesAreUnixBinPaths() {
        var candidates = BrowserLauncher.defaultCandidates("Linux", HOME);
        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().allMatch(p -> p.toString().startsWith("/")),
            "expected absolute paths, got " + candidates);
        assertTrue(candidates.stream().anyMatch(p -> p.toString().contains("chromium")));
    }

    @Test void windowsCandidatesAreExecutables() {
        var candidates = BrowserLauncher.defaultCandidates("Windows 11", HOME);
        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().allMatch(p -> p.toString().endsWith(".exe")),
            "expected .exe paths, got " + candidates);
    }

    @Test void appWindowCommandRequestsAChromelessWindowInTheUsersOwnChrome() {
        var cmd = BrowserLauncher.appWindowCommand(
            Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
            "http://192.168.1.5:8080/");

        assertEquals("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", cmd.getFirst(),
            "the browser executable must be argv[0]");
        assertTrue(cmd.contains("--app=http://192.168.1.5:8080/"),
            "--app is what removes the tab strip and omnibox; got " + cmd);
        // A dedicated --user-data-dir would give us a process whose lifetime tracks the
        // window, but it starts a SECOND Chrome application instance, which shows up as
        // its own generic Chrome icon in the Dock next to postcard's. Clicking that icon
        // opens the empty profile's homepage. Window lifetime is tracked by WebSocket
        // idleness instead (see IdleWatcher), so the flag must stay off.
        assertFalse(cmd.stream().anyMatch(a -> a.startsWith("--user-data-dir")),
            "must reuse the user's own Chrome profile; got " + cmd);
    }

    @Test void appWindowCommandRejectsABlankUrl() {
        var browser = Path.of("/usr/bin/chromium");
        assertThrows(IllegalArgumentException.class,
            () -> BrowserLauncher.appWindowCommand(browser, "  "));
        assertThrows(NullPointerException.class,
            () -> BrowserLauncher.appWindowCommand(browser, null));
    }
}
