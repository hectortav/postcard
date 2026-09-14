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
    void aFailureAfterAnExpiredLockoutStartsACleanSlate() {
        // The lockout guard with lockedUntilEpochMs set but expired. This test previously
        // asserted that a single failure re-locked the address immediately -- which is the
        // bug, not the contract: three strikes buys fifteen minutes, and serving that
        // sentence has to buy the three strikes back, or one mistyped PIN locks a device out
        // of the session for good.
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("1.2.3.4");
        assertTrue(limiter.isLocked("1.2.3.4"));
        clock.advance(PinRateLimiter.LOCKOUT.plusSeconds(1));
        assertFalse(limiter.isLocked("1.2.3.4"));

        var r = limiter.recordFailure("1.2.3.4");
        assertTrue(r.allowed(), "one failure after the wait must not re-lock");
        assertEquals(PinRateLimiter.MAX_FAILS - 1, r.remainingAttempts());
        assertFalse(limiter.isLocked("1.2.3.4"));
    }

    @Test
    void peekRemainingReportsTheAllowanceThroughTheLockoutCycle() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        assertEquals(PinRateLimiter.MAX_FAILS, limiter.peekRemaining("5.6.7.8"));

        limiter.recordFailure("5.6.7.8");
        assertEquals(PinRateLimiter.MAX_FAILS - 1, limiter.peekRemaining("5.6.7.8"));

        for (int i = 1; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("5.6.7.8");
        assertEquals(0, limiter.peekRemaining("5.6.7.8"), "locked out: nothing left");

        // Once the window passes the counter is spent, so the UI must stop telling the user
        // they have no attempts left. It used to report 0 forever.
        clock.advance(PinRateLimiter.LOCKOUT.plusSeconds(1));
        assertEquals(PinRateLimiter.MAX_FAILS, limiter.peekRemaining("5.6.7.8"));
    }

    @Test void servingTheLockoutRestoresTheFullAllowance() {
        // The bug: fails stayed at MAX_FAILS through the lockout, so the very first attempt
        // after waiting out fifteen minutes tripped the limit again -- an effectively
        // permanent lockout for anyone who mistyped three times once.
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("10.0.0.9");
        assertTrue(limiter.isLocked("10.0.0.9"));

        clock.advance(PinRateLimiter.LOCKOUT.plusSeconds(1));
        assertFalse(limiter.isLocked("10.0.0.9"));
        assertEquals(PinRateLimiter.MAX_FAILS, limiter.peekRemaining("10.0.0.9"),
            "the allowance is restored once the lockout expires");

        var first = limiter.recordFailure("10.0.0.9");
        assertTrue(first.allowed(), "one wrong PIN after the wait must not re-lock");
        assertEquals(PinRateLimiter.MAX_FAILS - 1, first.remainingAttempts());
        var second = limiter.recordFailure("10.0.0.9");
        assertTrue(second.allowed());
        var third = limiter.recordFailure("10.0.0.9");
        assertFalse(third.allowed(), "and the third still locks");
    }

    @Test void theTrackedAddressMapIsBounded() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_TRACKED_IPS + 500; i++) {
            limiter.recordFailure("10." + (i / 65536) + "." + ((i / 256) % 256) + "." + (i % 256));
        }
        assertTrue(limiter.size() <= PinRateLimiter.MAX_TRACKED_IPS, "tracked: " + limiter.size());
    }

    @Test void lockedAddressesSurviveEviction() {
        var clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new PinRateLimiter(clock);
        for (int i = 0; i < PinRateLimiter.MAX_FAILS; i++) limiter.recordFailure("10.9.9.9");
        assertTrue(limiter.isLocked("10.9.9.9"));
        for (int i = 0; i < PinRateLimiter.MAX_TRACKED_IPS + 500; i++) {
            limiter.recordFailure("172.16." + ((i / 256) % 256) + "." + (i % 256));
        }
        assertTrue(limiter.isLocked("10.9.9.9"), "an attacker must not clear their lockout by flooding");
    }
}
