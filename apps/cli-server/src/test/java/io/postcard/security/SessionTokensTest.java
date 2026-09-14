package io.postcard.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SessionTokensTest {

    @Test void issuedTokensValidate() {
        var t = new SessionTokens();
        var token = t.issue();
        assertTrue(t.isValid(token));
        assertEquals(1, t.size());
    }

    @Test void tokensAreUnguessableAndDistinct() {
        var t = new SessionTokens();
        var a = t.issue();
        var b = t.issue();
        assertNotEquals(a, b);
        // 256 bits, base64url without padding.
        assertEquals(43, a.length());
        assertTrue(a.matches("^[A-Za-z0-9_-]+$"), a);
    }

    @Test void unknownNullAndEmptyTokensAreRejected() {
        var t = new SessionTokens();
        t.issue();
        assertFalse(t.isValid(null));
        assertFalse(t.isValid(""));
        assertFalse(t.isValid("not-a-real-token"));
    }

    @Test void aPrefixOfARealTokenIsNotValid() {
        var t = new SessionTokens();
        var token = t.issue();
        assertFalse(t.isValid(token.substring(0, token.length() - 1)));
        assertFalse(t.isValid(token + "x"));
    }

    @Test void tokensExpire() {
        var now = new AtomicLong(1_000);
        var t = new SessionTokens(5_000, now::get);
        var token = t.issue();
        assertTrue(t.isValid(token));
        now.addAndGet(4_999);
        assertTrue(t.isValid(token), "still inside the TTL");
        now.addAndGet(1);
        assertFalse(t.isValid(token), "expired exactly at the TTL boundary");
        assertEquals(0, t.size());
    }

    @Test void revokeAllDropsEverySession() {
        var t = new SessionTokens();
        var a = t.issue();
        var b = t.issue();
        t.revokeAll();
        assertFalse(t.isValid(a));
        assertFalse(t.isValid(b));
        assertEquals(0, t.size());
    }

    @Test void theTokenMapIsCapped() {
        var now = new AtomicLong(1_000);
        var t = new SessionTokens(1_000_000, now::get);
        for (int i = 0; i < SessionTokens.MAX_TOKENS + 50; i++) {
            // Advance the clock so "oldest" is well defined.
            now.addAndGet(1);
            t.issue();
        }
        assertTrue(t.size() <= SessionTokens.MAX_TOKENS, "live tokens: " + t.size());
    }

    @Test void aRejectedTtlIsRefusedUpFront() {
        assertThrows(IllegalArgumentException.class, () -> new SessionTokens(0, () -> 0L));
        assertThrows(IllegalArgumentException.class, () -> new SessionTokens(-1, () -> 0L));
        assertThrows(NullPointerException.class, () -> new SessionTokens(1, null));
    }
}
