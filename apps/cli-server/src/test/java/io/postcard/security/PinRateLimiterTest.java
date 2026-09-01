package io.postcard.security;

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

    @Test
    void peekRemainingForUnknownIp() {
        var limiter = new PinRateLimiter(new FakeClock(Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals(PinRateLimiter.MAX_FAILS, limiter.peekRemaining("9.9.9.9"));
    }

    @Test
    void peekRemainingAfterPartialFailures() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        limiter.recordFailure("1.2.3.4");
        assertEquals(PinRateLimiter.MAX_FAILS - 1, limiter.peekRemaining("1.2.3.4"));
    }

    @Test
    void peekRemainingReturnsZeroWhenLocked() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("1.2.3.4");
        assertEquals(0, limiter.peekRemaining("1.2.3.4"));
    }

    @Test
    void peekLockoutMsForUnknownIp() {
        var limiter = new PinRateLimiter(new FakeClock(Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals(0L, limiter.peekLockoutMsRemaining("9.9.9.9"));
    }

    @Test
    void peekLockoutMsForIpWithFailuresButNotLocked() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        limiter.recordFailure("1.2.3.4");
        // fails < MAX_FAILS → lockedUntil is still 0
        assertEquals(0L, limiter.peekLockoutMsRemaining("1.2.3.4"));
    }

    @Test
    void peekLockoutMsShrinks() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("1.2.3.4");
        long before = limiter.peekLockoutMsRemaining("1.2.3.4");
        clock.advance(Duration.ofMinutes(7));
        long after = limiter.peekLockoutMsRemaining("1.2.3.4");
        assertTrue(after < before);
        assertTrue(after > 0L);
    }

    @Test
    void peekLockoutMsZeroAfterExpiry() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("1.2.3.4");
        clock.advance(PinRateLimiter.LOCKOUT.plusSeconds(1));
        assertEquals(0L, limiter.peekLockoutMsRemaining("1.2.3.4"));
    }

    @Test
    void isLockedFalseWhenFailsBelowMax() {
        // ip is tracked but lockedUntilEpochMs == 0 (fails < MAX_FAILS).
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        limiter.recordFailure("1.2.3.4");
        assertFalse(limiter.isLocked("1.2.3.4"));
    }

    @Test
    void recordFailureAfterLockoutExpiresReEntersLockout() {
        // The TF branch of the lockout guard: lockedUntilEpochMs is set but
        // expired. A new failure should re-arm the lockout, not extend the
        // old one.
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("1.2.3.4");
        assertTrue(limiter.isLocked("1.2.3.4"));
        clock.advance(PinRateLimiter.LOCKOUT.plusSeconds(1));
        // isLocked returns false now (no auto-cleanup, just returns false)
        assertFalse(limiter.isLocked("1.2.3.4"));
        // recordFailure hits the TF branch — lockedUntil != 0 but now >= lockedUntil.
        var r = limiter.recordFailure("1.2.3.4");
        // After re-arming, the IP is locked again with a fresh window.
        assertFalse(r.allowed());
        assertTrue(limiter.isLocked("1.2.3.4"));
    }

    @Test
    void peekRemainingAfterLockoutExpiredReturnsZero() {
        // TF branch in peekRemaining: lockedUntil set but expired.
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("1.2.3.4");
        clock.advance(PinRateLimiter.LOCKOUT.plusSeconds(1));
        // The entry's `fails` is still >= MAX_FAILS, so peekRemaining returns 0
        // because of the `a.fails >= MAX_FAILS` check, not the lockedUntil check.
        // The TF branch on the `lockedUntilEpochMs != 0L && clock.millis() < a.lockedUntilEpochMs`
        // is the missed one — exercised when the clock is in the gap.
        // We just need a different IP that reaches the inner check.
        limiter.recordFailure("5.6.7.8"); // fails = 1, lockedUntil = 0 → FT branch
        assertEquals(PinRateLimiter.MAX_FAILS - 1, limiter.peekRemaining("5.6.7.8"));
        // Now arm the lockout on this IP.
        for (int i = 1; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("5.6.7.8");
        // Locked → 0
        assertEquals(0, limiter.peekRemaining("5.6.7.8"));
        // Advance past expiry. peekRemaining still returns 0 because fails >= MAX.
        clock.advance(PinRateLimiter.LOCKOUT.plusSeconds(1));
        assertEquals(0, limiter.peekRemaining("5.6.7.8"));
    }
}
