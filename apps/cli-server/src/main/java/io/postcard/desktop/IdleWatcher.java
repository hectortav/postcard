package io.postcard.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Shuts postcard down once nobody is using it.
 *
 * <p>Replaces watching the browser process. Launching a browser with a dedicated
 * {@code --user-data-dir} does give a process whose lifetime tracks the window, but it starts a
 * second Chrome <em>application instance</em> that appears as its own generic Chrome icon in the
 * Dock beside postcard's — clicking which opens that empty profile's homepage. Reusing the
 * user's own Chrome avoids all of that, at the cost of the launched process exiting immediately,
 * so window lifetime has to be observed some other way.
 *
 * <p>Connected WebSocket clients are a better signal anyway. "Nobody has the page open" is the
 * condition we actually care about, and it is the right answer in cases process-watching gets
 * wrong: if a phone still has the dashboard open, the server should stay up even though the host
 * closed its window.
 *
 * <p>Two details keep it from misfiring: the watcher does not arm until it has seen at least one
 * client (at startup none is connected yet, because the browser has not loaded the page), and a
 * reconnect inside the grace period cancels a pending shutdown, so a Wi-Fi blip does not kill the
 * server.
 */
public final class IdleWatcher {
    private static final Logger log = LoggerFactory.getLogger(IdleWatcher.class);

    private IdleWatcher() {}

    /**
     * Start watching on a daemon thread.
     *
     * @param clientCount live client count, e.g. {@code hub::size}
     * @param poll        how often to sample
     * @param grace       how long the count must stay at zero before firing
     * @param onIdle      run once, when everyone has gone away
     * @return the thread, so callers (and tests) can interrupt it
     */
    public static Thread start(IntSupplier clientCount, Duration poll, Duration grace, Runnable onIdle) {
        Objects.requireNonNull(clientCount, "clientCount");
        Objects.requireNonNull(poll, "poll");
        Objects.requireNonNull(grace, "grace");
        Objects.requireNonNull(onIdle, "onIdle");
        if (poll.isZero() || poll.isNegative()) throw new IllegalArgumentException("poll must be positive");
        if (grace.isNegative()) throw new IllegalArgumentException("grace must not be negative");

        var thread = new Thread(() -> watch(clientCount, poll, grace, onIdle), "postcard-idle-watcher");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void watch(IntSupplier clientCount, Duration poll, Duration grace, Runnable onIdle) {
        boolean armed = false;            // has anyone ever connected?
        long emptySince = -1L;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(poll.toMillis());
                int live = clientCount.getAsInt();
                if (live > 0) {
                    armed = true;
                    emptySince = -1L;     // a reconnect cancels any pending shutdown
                    continue;
                }
                if (!armed) continue;     // browser has not loaded the page yet
                long now = System.nanoTime();
                if (emptySince < 0) {
                    emptySince = now;
                } else if (Duration.ofNanos(now - emptySince).compareTo(grace) >= 0) {
                    log.info("postcard: no clients for {}s, shutting down", grace.toSeconds());
                    onIdle.run();
                    return;               // fire at most once
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
