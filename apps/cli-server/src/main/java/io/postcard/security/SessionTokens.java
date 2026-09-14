package io.postcard.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opaque per-client session tokens, issued when a receiver proves knowledge of the PIN.
 *
 * <p>This exists because the PIN gate used to be a single field on the server: the first
 * receiver to verify set it, and from that moment every device on the LAN could read
 * {@code /api/files} and download, forever, without ever entering the PIN. The three-strike
 * lockout was defeated by one success from anywhere. Authorization has to be per client, so
 * each successful verify mints a token and every gated route checks the caller's own.
 *
 * <p>Tokens are 256 bits from {@link SecureRandom}, compared with {@link MessageDigest#isEqual}
 * so a match is not decided by how many leading characters agree. They expire, and the map is
 * capped: it is keyed by a value an unauthenticated caller cannot choose, but a long-lived
 * session should not accumulate unbounded state either.
 *
 * <p>{@link #revokeAll()} is what makes changing the PIN mean something — every receiver has to
 * re-verify under the new one.
 */
public final class SessionTokens {
    /** How long a verified session stays valid without re-entering the PIN. */
    public static final long DEFAULT_TTL_MILLIS = 12 * 60 * 60 * 1000L;
    /** Upper bound on live tokens; the oldest are dropped first past this. */
    static final int MAX_TOKENS = 10_000;

    private static final SecureRandom RNG = new SecureRandom();

    private final ConcurrentHashMap<String, Long> expiries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final java.util.function.LongSupplier clock;

    public SessionTokens() { this(DEFAULT_TTL_MILLIS, System::currentTimeMillis); }

    /** Test seam: a fixed TTL and a clock the caller advances by hand. */
    public SessionTokens(long ttlMillis, java.util.function.LongSupplier clock) {
        if (ttlMillis <= 0) throw new IllegalArgumentException("ttl must be positive");
        this.ttlMillis = ttlMillis;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /** Mint a token valid for the TTL. */
    public String issue() {
        var raw = new byte[32];
        RNG.nextBytes(raw);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        var now = clock.getAsLong();
        evictExpired(now);
        if (expiries.size() >= MAX_TOKENS) evictOldest();
        expiries.put(token, now + ttlMillis);
        return token;
    }

    /** True when {@code token} was issued here, has not expired and has not been revoked. */
    public boolean isValid(String token) {
        if (token == null || token.isEmpty()) return false;
        var now = clock.getAsLong();
        // Look the value up by an equality that does not leak a prefix match through timing.
        for (var e : expiries.entrySet()) {
            if (!MessageDigest.isEqual(
                    e.getKey().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    token.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) continue;
            if (e.getValue() <= now) { expiries.remove(e.getKey()); return false; }
            return true;
        }
        return false;
    }

    /** Drop every live session. Called whenever the PIN is set, changed or cleared. */
    public void revokeAll() { expiries.clear(); }

    /** Live (unexpired) session count. */
    public int size() { evictExpired(clock.getAsLong()); return expiries.size(); }

    private void evictExpired(long now) { expiries.entrySet().removeIf(e -> e.getValue() <= now); }

    private void evictOldest() {
        expiries.entrySet().stream()
            .min(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .ifPresent(expiries::remove);
    }
}
