package io.sendme.security;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PinRateLimiterTest {

    /** A Clock that returns a fixed instant, mutable via offset(). */
    static final class FakeClock extends Clock {
        private Instant now;
        FakeClock(Instant start) { this.now = start; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @Test
    void firstFailureDecrementsRemaining() {
        var limiter = new PinRateLimiter(new FakeClock(Instant.parse("2026-01-01T00:00:00Z")));
        var r = limiter.recordFailure("1.2.3.4");
        assertTrue(r.allowed());
        assertEquals(PinRateLimiter.MAX_FAILS - 1, r.remainingAttempts());
        assertEquals(0L, r.lockoutMsRemaining());
    }

    @Test
    void secondFailureStillAllowed() {
        var limiter = new PinRateLimiter(new FakeClock(Instant.parse("2026-01-01T00:00:00Z")));
        limiter.recordFailure("1.2.3.4");
        var r = limiter.recordFailure("1.2.3.4");
        assertTrue(r.allowed());
        assertEquals(PinRateLimiter.MAX_FAILS - 2, r.remainingAttempts());
    }

    @Test
    void thirdFailureLocks() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        var r = limiter.recordFailure("1.2.3.4");
        assertFalse(r.allowed());
        assertEquals(0, r.remainingAttempts());
        assertTrue(r.lockoutMsRemaining() > 0L);
        assertTrue(limiter.isLocked("1.2.3.4"));
    }

    @Test
    void furtherFailuresDuringLockoutStillReportLocked() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("1.2.3.4");
        var r = limiter.recordFailure("1.2.3.4");
        assertFalse(r.allowed());
        assertEquals(0, r.remainingAttempts());
        assertTrue(r.lockoutMsRemaining() > 0L);
    }

    @Test
    void isLockedAutoExpiresAfterLockout() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("1.2.3.4");
        assertTrue(limiter.isLocked("1.2.3.4"));
        clock.advance(PinRateLimiter.LOCKOUT.plusSeconds(1));
        assertFalse(limiter.isLocked("1.2.3.4"));
    }

    @Test
    void recordSuccessClearsCounter() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        limiter.recordSuccess("1.2.3.4");
        // After clearing, a fresh failure has MAX_FAILS-1 remaining again.
        var r = limiter.recordFailure("1.2.3.4");
        assertTrue(r.allowed());
        assertEquals(PinRateLimiter.MAX_FAILS - 1, r.remainingAttempts());
    }

    @Test
    void perIpIsolation() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("1.2.3.4");
        // Different IP — unaffected.
        assertFalse(limiter.isLocked("5.6.7.8"));
        var r = limiter.recordFailure("5.6.7.8");
        assertTrue(r.allowed());
        assertEquals(PinRateLimiter.MAX_FAILS - 1, r.remainingAttempts());
    }

    @Test
    void unknownIpIsNotLocked() {
        var limiter = new PinRateLimiter();
        assertFalse(limiter.isLocked("9.9.9.9"));
    }

    @Test
    void resetClearsAll() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("1.2.3.4");
        assertTrue(limiter.isLocked("1.2.3.4"));
        limiter.reset();
        assertFalse(limiter.isLocked("1.2.3.4"));
    }

    @Test
    void concurrentFailuresFromOneIpDoNotDoubleCount() throws Exception {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        int threads = 16;
        int callsPerThread = 50;
        var pool = Executors.newFixedThreadPool(threads);
        var lockCount = new AtomicInteger();
        var allowedCount = new AtomicInteger();
        var barrier = new CountDownLatch(threads);
        var done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    barrier.countDown();
                    barrier.await();
                    for (int i = 0; i < callsPerThread; i++) {
                        var r = limiter.recordFailure("1.2.3.4");
                        if (r.allowed()) allowedCount.incrementAndGet();
                        else lockCount.incrementAndGet();
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { done.countDown(); }
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        // Once locked, the IP must remain locked. We should see at most
        // (MAX_FAILS - 1) allowed results from the contention window.
        assertTrue(allowedCount.get() <= PinRateLimiter.MAX_FAILS,
            "allowed count " + allowedCount.get() + " must be <= " + PinRateLimiter.MAX_FAILS);
        assertEquals(threads * callsPerThread, allowedCount.get() + lockCount.get());
        assertTrue(limiter.isLocked("1.2.3.4"));
    }

    @Test
    void lockoutMsShrinksAsTimePasses() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("1.2.3.4");
        var r1 = limiter.recordFailure("1.2.3.4");
        long firstMs = r1.lockoutMsRemaining();
        clock.advance(Duration.ofMinutes(5));
        var r2 = limiter.recordFailure("1.2.3.4");
        assertTrue(r2.lockoutMsRemaining() < firstMs);
    }
}
