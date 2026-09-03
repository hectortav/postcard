package io.sendme.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-IP rate limiter for PIN entry.
 *
 * <p>After {@link #MAX_FAILS} consecutive failures from the same remote IP the
 * IP is locked for {@link #LOCKOUT}. During the lockout window every verify
 * attempt returns 429. Successful PIN entry clears the failure counter.
 *
 * <p>State is held in-memory only. Restarts reset the map; that's acceptable
 * for a session-based CLI tool. A persistent store would be a v0.2+ concern.
 *
 * <p>All mutations on a single IP are atomic via {@link ConcurrentMap#compute},
 * so two threads failing on the same IP cannot race past {@code MAX_FAILS}.
 */
public final class PinRateLimiter {

    public static final int MAX_FAILS = 3;
    public static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final ConcurrentMap<String, Attempt> state = new ConcurrentHashMap<>();
    private final Clock clock;

    public PinRateLimiter() { this(Clock.systemUTC()); }

    /** Package-private constructor for tests that need to fast-forward time. */
    PinRateLimiter(Clock clock) { this.clock = Objects.requireNonNull(clock, "clock"); }

    /** Snapshot of a limiter query: whether the IP is allowed, attempts left, ms-remaining on lockout. */
    public record Result(boolean allowed, int remainingAttempts, long lockoutMsRemaining) {
        public static final Result ALLOWED_FIRST_TRY = new Result(true, MAX_FAILS, 0L);
    }

    /** Mutable per-IP state. {@code lockedUntilEpochMs} is absolute (UTC epoch millis). */
    static final class Attempt {
        int fails;
        long lockedUntilEpochMs;

        Attempt(int fails, long lockedUntilEpochMs) {
            this.fails = fails;
            this.lockedUntilEpochMs = lockedUntilEpochMs;
        }
    }

    /** {@code true} if the IP is currently inside a lockout window. */
    public boolean isLocked(String ip) {
        Attempt a = state.get(ip);
        if (a == null) return false;
        if (a.lockedUntilEpochMs == 0L) return false;
        // Expired entries are lazy-cleaned on the next failure/success that
        // touches this IP. Just report `false` here.
        return clock.millis() < a.lockedUntilEpochMs;
    }

    /** Record a successful PIN entry. Clears the IP's attempt counter. */
    public void recordSuccess(String ip) {
        state.compute(ip, (k, v) -> null);
    }

    /**
     * Record a failed PIN entry. Increments the failure count and, when
     * {@link #MAX_FAILS} is reached, arms the 15-minute lockout. Once a
     * lockout is armed, additional failures during the window do not extend
     * the timer — they only report the current {@code lockoutMsRemaining}.
     * The returned {@link Result} describes the new state.
     */
    public Result recordFailure(String ip) {
        long now = clock.millis();
        Result[] out = new Result[1];
        state.compute(ip, (k, prev) -> {
            Attempt a = (prev == null) ? new Attempt(0, 0L) : prev;
            // If already locked and lockout still in effect, don't extend.
            if (a.lockedUntilEpochMs != 0L && now < a.lockedUntilEpochMs) {
                long ms = a.lockedUntilEpochMs - now;
                out[0] = new Result(false, 0, ms);
                return a;
            }
            a.fails = a.fails + 1;
            if (a.fails >= MAX_FAILS) {
                a.lockedUntilEpochMs = now + LOCKOUT.toMillis();
                int remaining = Math.max(0, MAX_FAILS - a.fails);
                long ms = Math.max(0L, a.lockedUntilEpochMs - now);
                out[0] = new Result(false, remaining, ms);
            } else {
                out[0] = new Result(true, Math.max(0, MAX_FAILS - a.fails), 0L);
            }
            return a;
        });
        return out[0];
    }

    /** Test-only: clear all state. Not part of the public HTTP contract. */
    public void reset() { state.clear(); }

    /**
     * Read-only peek: how many more failures this IP can sustain before
     * being locked. Returns {@link #MAX_FAILS} for IPs with no recorded
     * attempts and {@code 0} for IPs that are currently locked or already
     * exhausted.
     */
    public int peekRemaining(String ip) {
        Attempt a = state.get(ip);
        if (a == null) return MAX_FAILS;
        if (a.lockedUntilEpochMs != 0L && clock.millis() < a.lockedUntilEpochMs) return 0;
        if (a.fails >= MAX_FAILS) return 0;
        return Math.max(0, MAX_FAILS - a.fails);
    }

    /**
     * Read-only peek: the millis remaining on the IP's current lockout, or
     * {@code 0} if the IP is not locked.
     */
    public long peekLockoutMsRemaining(String ip) {
        Attempt a = state.get(ip);
        if (a == null) return 0L;
        if (a.lockedUntilEpochMs == 0L) return 0L;
        long now = clock.millis();
        if (now >= a.lockedUntilEpochMs) return 0L;
        return a.lockedUntilEpochMs - now;
    }
}
