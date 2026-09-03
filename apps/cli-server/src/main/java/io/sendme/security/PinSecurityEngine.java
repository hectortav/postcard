package io.sendme.security;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Objects;

/**
 * Derives a 256-bit AES key from {@code (randomSecretBytes, pin, salt)} using
 * PBKDF2-HMAC-SHA256 with 200,000 iterations.
 *
 * <p>The salt is {@code SHA-256(randomSecretBytes)} so it is deterministic and
 * known to both the sender (Java) and the receiver (browser WebCrypto). This
 * avoids any out-of-band salt exchange while still binding the derived key
 * uniquely to the per-session random secret.
 *
 * <p>The same input triple {@code (secret, pin, salt)} always produces the
 * same 32-byte derived key. Wrong PINs produce different keys, which is
 * detected by the constant-time {@link #verify(byte[], String, byte[])} helper.
 */
public final class PinSecurityEngine {

    public static final int ITERATIONS = 200_000;
    public static final int KEY_BITS = 256;
    public static final int KEY_BYTES = KEY_BITS / 8;
    public static final String KDF_ALG = "PBKDF2WithHmacSHA256";
    public static final String AES_ALG = "AES";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    // SHA-256 and PBKDF2-HMAC-SHA256 are mandatory JCA algorithms in every
    // JDK 8+ distribution. JCA caches getInstance() lookups internally, so
    // we resolve once at class load. The static block's catch is unreachable
    // in any conformant JRE; Phase 13's JaCoCo gate will need an exclusion
    // filter for that defensive guard.
    private static final SecretKeyFactory PBKDF2;
    static {
        try {
            PBKDF2 = SecretKeyFactory.getInstance(KDF_ALG);
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(
                "PBKDF2-HMAC-SHA256 is required by the JRE but not found: " + e);
        }
    }

    private PinSecurityEngine() {}

    /**
     * Compute the salt as the hex-encoded SHA-256 of the random secret bytes.
     * Both sender and receiver can call this independently; the output is
     * deterministic and identical.
     */
    public static String saltFor(byte[] secretBytes) {
        Objects.requireNonNull(secretBytes, "secretBytes");
        if (secretBytes.length < 16) {
            throw new IllegalArgumentException("secret must be at least 128 bits (16 bytes); got " + secretBytes.length);
        }
        return toHex(sha256Digest(secretBytes));
    }

    /**
     * Resolve a SHA-256 {@link MessageDigest} and digest {@code input}. SHA-256
     * is a mandatory JCA algorithm, so the {@link NoSuchAlgorithmException}
     * is unreachable in any conformant JRE.
     */
    private static byte[] sha256Digest(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // Unreachable in a conformant JRE. The catch exists only because
            // getInstance declares the checked exception.
            throw new IllegalStateException("SHA-256 missing from JRE", e);
        }
    }

    /**
     * Derive the AES-256 key for {@code (secretBytes, pin, saltHex)}.
     *
     * @param secretBytes 32-byte random secret from the URL fragment
     * @param pin         4-digit PIN entered by the user
     * @param saltHex     hex-encoded SHA-256 of the secret (see {@link #saltFor(byte[])})
     * @return an AES {@link SecretKey} suitable for AES-256-GCM
     * @throws InvalidKeySpecException if the KDF rejects the input
     */
    public static SecretKey deriveKey(byte[] secretBytes, String pin, String saltHex) throws InvalidKeySpecException {
        validate(secretBytes, pin, saltHex);
        char[] pinChars = pin.toCharArray();
        try {
            byte[] saltBytes = fromHex(saltHex);
            PBEKeySpec spec = new PBEKeySpec(pinChars, saltBytes, ITERATIONS, KEY_BITS);
            try {
                byte[] derived = PBKDF2.generateSecret(spec).getEncoded();
                return new SecretKeySpec(derived, AES_ALG);
            } finally {
                spec.clearPassword();
            }
        } finally {
            java.util.Arrays.fill(pinChars, '\0');
        }
    }

    /**
     * Round-trip helper used by the verify route: re-derive the key from
     * {@code (secretBytes, pin)} and compare against {@code expectedDerived}
     * in constant time. {@code expectedDerived} is the raw 32-byte derived key,
     * not the AES SecretKey wrapper. Any null/short/malformed input resolves
     * to {@code false}; the route layer treats this as a 401.
     */
    public static boolean verify(byte[] secretBytes, String pin, byte[] expectedDerived) {
        if (secretBytes == null || pin == null || expectedDerived == null) return false;
        if (expectedDerived.length != KEY_BYTES) return false;
        try {
            String salt = saltFor(secretBytes);
            byte[] derived = deriveKey(secretBytes, pin, salt).getEncoded();
            return MessageDigest.isEqual(derived, expectedDerived);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Convenience: returns the raw 32-byte derived key (base64-url, no padding)
     * for transport or comparison in tests.
     */
    public static String deriveKeyBase64Url(byte[] secretBytes, String pin) throws InvalidKeySpecException {
        byte[] raw = deriveKey(secretBytes, pin, saltFor(secretBytes)).getEncoded();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static void validate(byte[] secretBytes, String pin, String saltHex) {
        Objects.requireNonNull(secretBytes, "secretBytes");
        Objects.requireNonNull(pin, "pin");
        Objects.requireNonNull(saltHex, "saltHex");
        if (secretBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException("secret must be " + KEY_BYTES + " bytes; got " + secretBytes.length);
        }
        if (pin.isEmpty()) {
            throw new IllegalArgumentException("pin must not be empty");
        }
        if (saltHex.length() != 64) {
            throw new IllegalArgumentException("saltHex must be 64 hex chars (SHA-256); got " + saltHex.length());
        }
    }

    private static String toHex(byte[] bytes) {
        byte[] out = new byte[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = (byte) HEX[b >>> 4];
            out[i * 2 + 1] = (byte) HEX[b & 0x0F];
        }
        return new String(out, StandardCharsets.US_ASCII);
    }

    private static byte[] fromHex(String s) {
        // Caller validates length. Even-length is guaranteed; no need to check.
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(s.charAt(i), 16);
            int lo = Character.digit(s.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("non-hex character at " + i);
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
