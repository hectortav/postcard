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
        if (clock.millis() < a.lockedUntilEpochMs) return true;
        // Auto-expire: clear stale entry.
        state.compute(ip, (k, v) -> v == null ? null
            : (v.lockedUntilEpochMs != 0L && clock.millis() >= v.lockedUntilEpochMs) ? null : v);
        return false;
    }

    /** Record a successful PIN entry. Clears the IP's attempt counter. */
    public void recordSuccess(String ip) {
        state.compute(ip, (k, v) -> null);
    }

    /**
     * Record a failed PIN entry. Increments the failure count and, when
     * {@link #MAX_FAILS} is reached, arms the 15-minute lockout. The returned
     * {@link Result} describes the new state.
     */
    public Result recordFailure(String ip) {
        long now = clock.millis();
        long lockEnd = now + LOCKOUT.toMillis();
        Result[] out = new Result[1];
        state.compute(ip, (k, prev) -> {
            Attempt a = (prev == null) ? new Attempt(0, 0L) : prev;
            a.fails = a.fails + 1;
            if (a.fails >= MAX_FAILS) {
                a.lockedUntilEpochMs = lockEnd;
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
}
