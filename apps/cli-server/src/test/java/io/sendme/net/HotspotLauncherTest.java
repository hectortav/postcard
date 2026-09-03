package io.sendme.net;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the Linux-only hotspot launcher contract:
 *  - non-Linux hosts return null (we don't try to launch on macOS/Windows),
 *  - missing nmcli returns manual-instructions only (no auto-launch).
 *
 * <p>Note: `java.lang.Process` is `final` in JDK 21 and Mockito 5.x without
 * the inline mock-maker cannot mock final classes. The brief's `mock(Process.class)`
 * pattern therefore uses a real `ProcessBuilder` instead: `true` exits 0,
 * `false` exits 1, and `waitFor(timeout, unit)` works on the real process.
 * The contract (return a `Process` whose `waitFor` / `exitValue` the launcher
 * can read) is identical; only the construction site is real.
 */
class HotspotLauncherTest {

    /**
     * Build a real {@link Process} that exits with the given code. `true` and
     * `false` are POSIX no-op commands with deterministic exit codes (0 / 1),
     * and are present on macOS and Linux dev hosts. The test only cares about
     * `waitFor(timeout, unit)` and `exitValue()`; stdin/stdout are unused.
     *
     * <p>Wrapped to throw {@link RuntimeException} (via {@link UncheckedIOException})
     * so the runner lambda can be a plain {@link Function} without a `throws`
     * clause, matching the production runner signature.
     */
    static Process fakeProcess(int exitValue) {
        String cmd = exitValue == 0 ? "true" : "false";
        try {
            return new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void nullOnNonLinux() {
        var orig = System.getProperty("os.name");
        System.setProperty("os.name", "Mac OS X");
        try {
            assertNull(HotspotLauncher.attempt());
        } finally {
            System.setProperty("os.name", orig);
        }
    }

    @Test
    void nmcliMissingReturnsInstructions() {
        var orig = System.getProperty("os.name");
        System.setProperty("os.name", "Linux");
        try {
            Process whichFails = fakeProcess(1);
            Process okProcess = fakeProcess(0);
            Function<String[], Process> runner = cmd -> cmd[0].equals("which") ? whichFails : okProcess;
            var r = HotspotLauncher.attempt(runner);
            assertNotNull(r);
            assertNull(r.interfaceIp());
            assertNotNull(r.instructions().text());
            assertTrue(r.instructions().text().toLowerCase().contains("nmcli"),
                    "instructions must mention nmcli so the user knows what to install");
        } finally {
            System.setProperty("os.name", orig);
        }
    }
}
