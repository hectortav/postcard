package io.postcard.desktop;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one place postcard decides <em>when</em> the JVM may die.
 *
 * <p>An embedded Chromium makes quitting a two-party affair. JCEF's contract is that the
 * application ends itself once {@code CefApp} reaches {@code TERMINATED}: asking it to shut
 * down only <em>schedules</em> the teardown, which then runs on the AWT event thread across
 * several turns while CEF reaps its helper processes. Both ways of getting that wrong are
 * user-visible:
 *
 * <ul>
 *   <li>Exiting too early — {@code System.exit} in the same event that requested the shutdown —
 *       kills the JVM before CEF's native shutdown runs. The {@code jcef Helper} processes are
 *       orphaned and die of {@code SIGBUS} once the framework they mapped goes away.</li>
 *   <li>Never exiting — leaving {@code stateHasChanged} unhandled — leaves a live JVM with no
 *       window and no way to quit after a Cmd+Q, because CEF has already torn everything down.
 *       Only a force-quit ends it, which orphans the helpers all over again.</li>
 * </ul>
 *
 * <p>So the rule is: exit once both the browser has reported termination and the server-side
 * teardown has finished — or at a deadline, so a wedged Chromium can never hold the app open.
 *
 * <p>Every collaborator is a seam, which is what lets the policy be tested without a Chromium
 * on the test classpath: {@code background} is where the blocking teardown runs (never the
 * event thread, which is the thread CEF needs), {@code delay} is the deadline timer, and
 * {@code exit} is {@code System.exit}.
 */
public final class QuitSequence {
    /** "Run this once, later." Production passes a timer; tests fire it by hand. */
    public interface Delay {
        void schedule(long millis, Runnable action);
    }

    private final Executor background;
    private final Runnable teardown;
    private final Runnable stopBrowser;
    private final Delay delay;
    private final long graceMillis;
    private final Runnable exit;

    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean torndown = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final AtomicBoolean exited = new AtomicBoolean();

    private QuitSequence(Executor background, Runnable teardown, Runnable stopBrowser,
                         Delay delay, long graceMillis, Runnable exit) {
        this.background = background;
        this.teardown = teardown;
        this.stopBrowser = stopBrowser;
        this.delay = delay;
        this.graceMillis = graceMillis;
        this.exit = exit;
    }

    /**
     * @param background  where {@code teardown} and the exit itself run; never the AWT event
     *                    thread, and able to run more than one task at a time — the exit must
     *                    not be queued behind a teardown that is taking its time
     * @param teardown    stop the server, drain transfers, remove temporary files
     * @param stopBrowser ask the embedded browser to shut down; returns before it has
     * @param delay       schedules the deadline
     * @param graceMillis how long the browser gets to terminate before the app leaves anyway
     * @param exit        ends the process
     */
    public static QuitSequence create(Executor background, Runnable teardown, Runnable stopBrowser,
                                      Delay delay, long graceMillis, Runnable exit) {
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(teardown, "teardown");
        Objects.requireNonNull(stopBrowser, "stopBrowser");
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(exit, "exit");
        return new QuitSequence(background, teardown, stopBrowser, delay, graceMillis, exit);
    }

    /**
     * Begin quitting. Idempotent: the window's close button, the tray's Quit item and a Cmd+Q
     * can all arrive, and two of them can arrive together.
     *
     * <p>Safe to call on the event thread — nothing here blocks.
     */
    public void request() {
        if (!requested.compareAndSet(false, true)) return;
        stopBrowser.run();
        background.execute(() -> {
            try {
                teardown.run();
            } finally {
                torndown.set(true);
                exitWhenReady();
            }
        });
        delay.schedule(graceMillis, this::exitNow);
    }

    /** The embedded browser has finished shutting down. */
    public void browserTerminated() {
        terminated.set(true);
        exitWhenReady();
    }

    /**
     * Both conditions met, so the process may go — but not on this thread. Termination is
     * reported on the AWT event thread, and {@code System.exit} there deadlocks: it runs the
     * shutdown hooks and waits for them, while AWT's tray-icon removal inside a hook waits for
     * the event thread to dispose the icon's window. Neither side can move. The deadline is
     * exempt because it already runs on a thread of its own.
     */
    private void exitWhenReady() {
        if (requested.get() && terminated.get() && torndown.get()) background.execute(this::exitNow);
    }

    private void exitNow() {
        if (exited.compareAndSet(false, true)) exit.run();
    }
}
