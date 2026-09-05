package io.postcard.desktop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class QuitSequenceTest {
    /** Captures the deadline so a test can decide when it fires. */
    private static final class ManualDelay implements QuitSequence.Delay {
        long millis = -1;
        Runnable action;

        @Override public void schedule(long millis, Runnable action) {
            this.millis = millis;
            this.action = action;
        }

        void fire() { action.run(); }
    }

    private static QuitSequence sequence(Executor background, Runnable teardown, Runnable stopBrowser,
                                         QuitSequence.Delay delay, List<String> exits) {
        return QuitSequence.create(background, teardown, stopBrowser, delay, 5_000, () -> exits.add("exit"));
    }

    @Test void stopsTheBrowserWhenQuitIsRequested() {
        var stops = new ArrayList<String>();
        var q = sequence(Runnable::run, () -> {}, () -> stops.add("stop"), new ManualDelay(), new ArrayList<>());

        q.request();

        assertEquals(List.of("stop"), stops);
    }

    @Test void doesNotExitWhileTheBrowserIsStillShuttingDown() {
        var exits = new ArrayList<String>();
        var q = sequence(Runnable::run, () -> {}, () -> {}, new ManualDelay(), exits);

        q.request();

        assertEquals(List.of(), exits, "exiting here orphans the Chromium helper processes");
    }

    @Test void exitsWhenTheBrowserReportsTermination() {
        var exits = new ArrayList<String>();
        var q = sequence(Runnable::run, () -> {}, () -> {}, new ManualDelay(), exits);
        q.request();

        q.browserTerminated();

        assertEquals(List.of("exit"), exits, "nothing else ends the JVM once CEF has torn down AWT's window");
    }

    @Test void exitsAtTheDeadlineWhenTerminationNeverArrives() {
        var exits = new ArrayList<String>();
        var delay = new ManualDelay();
        var q = sequence(Runnable::run, () -> {}, () -> {}, delay, exits);
        q.request();

        delay.fire();

        assertEquals(List.of("exit"), exits);
        assertEquals(5_000, delay.millis);
    }

    @Test void exitsOnlyOnceWhenTerminationAndTheDeadlineBothFire() {
        var exits = new ArrayList<String>();
        var delay = new ManualDelay();
        var q = sequence(Runnable::run, () -> {}, () -> {}, delay, exits);
        q.request();

        q.browserTerminated();
        delay.fire();

        assertEquals(List.of("exit"), exits);
    }

    @Test void doesNotExitOnTerminationBeforeQuitWasRequested() {
        var exits = new ArrayList<String>();
        var q = sequence(Runnable::run, () -> {}, () -> {}, new ManualDelay(), exits);

        q.browserTerminated();

        assertEquals(List.of(), exits, "CEF reaching TERMINATED without a quit request is not a reason to die");
    }

    @Test void runsTeardownOnTheBackgroundExecutor() {
        var calls = new ArrayList<String>();
        Executor background = task -> { calls.add("submitted"); task.run(); };
        var q = sequence(background, () -> calls.add("teardown"), () -> {}, new ManualDelay(), new ArrayList<>());

        q.request();

        assertEquals(List.of("submitted", "teardown"), calls,
            "teardown blocks; running it on the caller's thread would stall the EDT that CEF shuts down on");
    }

    @Test void waitsForTeardownBeforeExiting() {
        var exits = new ArrayList<String>();
        var pending = new ArrayList<Runnable>();
        var q = sequence(pending::add, () -> {}, () -> {}, new ManualDelay(), exits);
        q.request();

        q.browserTerminated();
        assertEquals(List.of(), exits, "the temp directory is still being deleted");

        drain(pending);
        assertEquals(List.of("exit"), exits);
    }

    @Test void handsTheExitOffTheThreadThatReportedTermination() {
        var exits = new ArrayList<String>();
        var pending = new ArrayList<Runnable>();
        var q = sequence(pending::add, () -> {}, () -> {}, new ManualDelay(), exits);
        q.request();
        pending.removeFirst().run();          // teardown finishes

        q.browserTerminated();

        assertEquals(List.of(), exits,
            "termination is reported on the event thread, and System.exit there deadlocks "
                + "against any shutdown hook that needs it — AWT's tray removal does");
        assertEquals(1, pending.size(), "the exit should have been handed to the worker instead");
        drain(pending);
        assertEquals(List.of("exit"), exits);
    }

    /** Runs queued work, including anything it queues in turn. */
    private static void drain(List<Runnable> pending) {
        while (!pending.isEmpty()) pending.removeFirst().run();
    }

    @Test void runsTeardownOnceWhenQuitIsRequestedTwice() {
        var calls = new ArrayList<String>();
        var q = sequence(Runnable::run, () -> calls.add("teardown"), () -> {}, new ManualDelay(), new ArrayList<>());

        q.request();
        q.request();

        assertEquals(List.of("teardown"), calls);
    }

    @Test void exitsAtTheDeadlineEvenWhileTeardownIsStillRunning() {
        var exits = new ArrayList<String>();
        var delay = new ManualDelay();
        var q = sequence(task -> {}, () -> {}, () -> {}, delay, exits);
        q.request();

        delay.fire();

        assertEquals(List.of("exit"), exits, "a wedged teardown must not hold the app open forever");
    }
}
