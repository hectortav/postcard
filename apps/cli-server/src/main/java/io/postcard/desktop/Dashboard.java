package io.postcard.desktop;

/**
 * The dashboard window, behind a seam.
 *
 * <p>Exists so {@code Main}'s wiring and the tray's menu actions can be tested against a fake
 * with no CEF on the test classpath. Loading Chromium in CI would mean every build job pulling
 * a 300 MB native bundle to run unit tests.
 *
 * <p>Implementations are expected to initialize lazily: constructing one starts nothing, and
 * the first {@link #open()} builds the window. That is what makes {@code --no-browser}
 * meaningful — postcard runs with a tray and no Chromium in the process tree until the user
 * actually asks for the dashboard.
 */
public interface Dashboard extends AutoCloseable {
    /** Show and focus the window, building it on first call. Idempotent. */
    void open();

    boolean isOpen();

    /** Dispose the window and the browser. Safe to call when never opened. */
    @Override
    void close();
}
