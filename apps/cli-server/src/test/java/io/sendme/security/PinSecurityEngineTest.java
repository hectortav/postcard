package io.sendme.security;

import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class PinSecurityEngineTest {

    private static byte[] randomSecret() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void knownVectorDeterministic() throws Exception {
        // Deterministic secret + PIN + salt → reproducible derived key.
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) (i + 1);
        String pin = "1234";
        String salt = PinSecurityEngine.saltFor(secret);
        SecretKey k1 = PinSecurityEngine.deriveKey(secret, pin, salt);
        SecretKey k2 = PinSecurityEngine.deriveKey(secret, pin, salt);
        assertEquals("AES", k1.getAlgorithm());
        assertArrayEquals(k1.getEncoded(), k2.getEncoded());
        assertEquals(32, k1.getEncoded().length);
    }

    @Test
    void wrongPinProducesDifferentKey() throws Exception {
        byte[] secret = randomSecret();
        String salt = PinSecurityEngine.saltFor(secret);
        SecretKey a = PinSecurityEngine.deriveKey(secret, "1234", salt);
        SecretKey b = PinSecurityEngine.deriveKey(secret, "5678", salt);
        assertFalse(MessageDigestIsEqualShim(a.getEncoded(), b.getEncoded()));
    }

    @Test
    void saltForIsSha256OfSecret() {
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) (i * 7);
        String salt = PinSecurityEngine.saltFor(secret);
        assertEquals(64, salt.length());
        // Re-deriving via MessageDigest independently should match.
        try {
            byte[] expect = java.security.MessageDigest.getInstance("SHA-256").digest(secret);
            StringBuilder sb = new StringBuilder();
            for (byte b : expect) sb.append(String.format("%02x", b & 0xFF));
            assertEquals(sb.toString(), salt);
        } catch (Exception e) { fail(e); }
    }

    @Test
    void saltForIsDeterministic() {
        byte[] secret = randomSecret();
        assertEquals(PinSecurityEngine.saltFor(secret), PinSecurityEngine.saltFor(secret));
    }

    @Test
    void saltForRejectsShortSecret() {
        assertThrows(IllegalArgumentException.class, () -> PinSecurityEngine.saltFor(new byte[8]));
    }

    @Test
    void saltForRejectsNullSecret() {
        assertThrows(NullPointerException.class, () -> PinSecurityEngine.saltFor(null));
    }

    @Test
    void deriveKeyRejectsEmptyPin() {
        byte[] secret = randomSecret();
        String salt = PinSecurityEngine.saltFor(secret);
        assertThrows(IllegalArgumentException.class, () -> PinSecurityEngine.deriveKey(secret, "", salt));
    }

    @Test
    void deriveKeyRejectsWrongLengthSecret() {
        String salt = PinSecurityEngine.saltFor(new byte[32]);
        assertThrows(IllegalArgumentException.class,
            () -> PinSecurityEngine.deriveKey(new byte[16], "1234", salt));
    }

    @Test
    void deriveKeyRejectsBadSaltHexLength() {
        byte[] secret = randomSecret();
        assertThrows(IllegalArgumentException.class,
            () -> PinSecurityEngine.deriveKey(secret, "1234", "deadbeef"));
    }

    @Test
    void deriveKeyRejectsNonHexSalt() {
        byte[] secret = randomSecret();
        String bad = "zzzz".repeat(16); // 64 chars but non-hex
        assertThrows(IllegalArgumentException.class,
            () -> PinSecurityEngine.deriveKey(secret, "1234", bad));
    }

    @Test
    void deriveKeyRejectsOddLengthSalt() {
        byte[] secret = randomSecret();
        assertThrows(IllegalArgumentException.class,
            () -> PinSecurityEngine.deriveKey(secret, "1234", "abc"));
    }

    @Test
    void deriveKeyRejectsNullArgs() {
        byte[] secret = randomSecret();
        String salt = PinSecurityEngine.saltFor(secret);
        assertThrows(NullPointerException.class, () -> PinSecurityEngine.deriveKey(null, "1234", salt));
        assertThrows(NullPointerException.class, () -> PinSecurityEngine.deriveKey(secret, null, salt));
        assertThrows(NullPointerException.class, () -> PinSecurityEngine.deriveKey(secret, "1234", null));
    }

    @Test
    void verifyMatchesDerivedKey() throws Exception {
        byte[] secret = randomSecret();
        String salt = PinSecurityEngine.saltFor(secret);
        SecretKey k = PinSecurityEngine.deriveKey(secret, "1234", salt);
        assertTrue(PinSecurityEngine.verify(secret, "1234", k.getEncoded()));
        assertFalse(PinSecurityEngine.verify(secret, "5678", k.getEncoded()));
    }

    @Test
    void verifyRejectsWrongLengthExpected() {
        byte[] secret = randomSecret();
        assertFalse(PinSecurityEngine.verify(secret, "1234", new byte[16]));
    }

    @Test
    void verifyRejectsNullArgs() {
        byte[] secret = randomSecret();
        SecretKey k;
        try { k = PinSecurityEngine.deriveKey(secret, "1234", PinSecurityEngine.saltFor(secret)); }
        catch (Exception e) { fail(e); return; }
        byte[] expected = k.getEncoded();
        assertFalse(PinSecurityEngine.verify(null, "1234", expected));
        assertFalse(PinSecurityEngine.verify(secret, null, expected));
        assertFalse(PinSecurityEngine.verify(secret, "1234", null));
    }

    @Test
    void deriveKeyBase64UrlRoundTrips() throws Exception {
        byte[] secret = randomSecret();
        String encoded = PinSecurityEngine.deriveKeyBase64Url(secret, "1234");
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        assertEquals(32, decoded.length);
        SecretKey direct = PinSecurityEngine.deriveKey(secret, "1234", PinSecurityEngine.saltFor(secret));
        assertArrayEquals(direct.getEncoded(), decoded);
    }

    /** Constant-time comparison helper to avoid importing the shim in the impl. */
    private static boolean MessageDigestIsEqualShim(byte[] a, byte[] b) {
        return java.security.MessageDigest.isEqual(a, b);
    }
}
