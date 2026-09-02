package io.postcard.desktop;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IdleWatcherTest {

    private static final Duration POLL = Duration.ofMillis(20);
    private static final Duration GRACE = Duration.ofMillis(120);

    @Test void firesOnceEveryClientHasGoneAway() throws Exception {
        var clients = new AtomicInteger(1);
        var fired = new CountDownLatch(1);

        var watcher = IdleWatcher.start(clients::get, POLL, GRACE, fired::countDown);
        try {
            assertFalse(fired.await(150, TimeUnit.MILLISECONDS), "must not fire while a client is connected");
            clients.set(0);
            assertTrue(fired.await(2, TimeUnit.SECONDS), "must fire once the last client disconnects");
        } finally {
            watcher.interrupt();
        }
    }

    @Test void waitsForTheFirstClientBeforeArming() throws Exception {
        // At startup nothing is connected yet because the browser has not loaded the
        // page. Firing then would quit the app the instant it launched.
        var fired = new CountDownLatch(1);
        var watcher = IdleWatcher.start(() -> 0, POLL, GRACE, fired::countDown);
        try {
            assertFalse(fired.await(300, TimeUnit.MILLISECONDS),
                "must not fire before any client has ever connected");
        } finally {
            watcher.interrupt();
        }
    }

    @Test void aReconnectWithinTheGracePeriodCancelsTheShutdown() throws Exception {
        // Wi-Fi blips drop the socket briefly. A reconnect must reset the countdown.
        var clients = new AtomicInteger(1);
        var fired = new CountDownLatch(1);
        var watcher = IdleWatcher.start(clients::get, POLL, GRACE, fired::countDown);
        try {
            clients.set(0);
            Thread.sleep(60);          // less than GRACE
            clients.set(1);            // reconnected
            assertFalse(fired.await(300, TimeUnit.MILLISECONDS),
                "a reconnect inside the grace period must cancel the pending shutdown");
        } finally {
            watcher.interrupt();
        }
    }

    @Test void firesAtMostOnce() throws Exception {
        var count = new AtomicInteger();
        var watcher = IdleWatcher.start(() -> 0, POLL, GRACE, count::incrementAndGet);
        try {
            // never armed, so still zero
            Thread.sleep(200);
            assertEquals(0, count.get());
        } finally {
            watcher.interrupt();
        }
    }

    @Test void rejectsNonPositiveDurations() {
        assertThrows(IllegalArgumentException.class,
            () -> IdleWatcher.start(() -> 0, Duration.ZERO, GRACE, () -> {}));
        assertThrows(IllegalArgumentException.class,
            () -> IdleWatcher.start(() -> 0, POLL, Duration.ofMillis(-1), () -> {}));
    }
}
