package io.sendme.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;

public final class ChunkCipher {
    public static final int CHUNK_BYTES = 64 * 1024;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    /** Returns total ciphertext+tag bytes for a plaintext of the given size. */
    public static long chunkContentLength(long plaintextSize) {
        if (plaintextSize < 0) throw new IllegalArgumentException();
        long fullChunks = plaintextSize / CHUNK_BYTES;
        long rem = plaintextSize % CHUNK_BYTES;
        return (fullChunks + (rem == 0 ? 0 : 1)) * (NONCE_BYTES + CHUNK_BYTES + TAG_BYTES) - (rem == 0 ? 0 : (CHUNK_BYTES - rem));
    }

    public static byte[] encryptAll(byte[] plaintext, KeyMaterial km) throws Exception {
        var out = new ByteArrayOutputStream();
        encryptStream(new java.io.ByteArrayInputStream(plaintext), out, km);
        return out.toByteArray();
    }

    /** Streams the ciphertext to {@code out}. Returns the number of bytes written. */
    public static long encryptStream(InputStream in, OutputStream out, KeyMaterial km) throws IOException {
        long total = 0;
        byte[] buf = new byte[CHUNK_BYTES];
        int n;
        while ((n = in.readNBytes(buf, 0, CHUNK_BYTES)) > 0) {
            byte[] nonce = new byte[NONCE_BYTES]; RNG.nextBytes(nonce);
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(km.key(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
                byte[] ct = c.doFinal(buf, 0, n);
                out.write(nonce);
                out.write(ct);
                total += nonce.length + ct.length;
            } catch (Exception e) { throw new IOException("encrypt chunk", e); }
        }
        return total;
    }

    public static byte[] decryptAll(byte[] ciphertext, KeyMaterial km) throws Exception {
        var out = new ByteArrayOutputStream();
        for (int off = 0; off < ciphertext.length; ) {
            byte[] nonce = new byte[NONCE_BYTES]; System.arraycopy(ciphertext, off, nonce, 0, NONCE_BYTES); off += NONCE_BYTES;
            int remaining = ciphertext.length - off;
            int chunkLen = CHUNK_BYTES + TAG_BYTES;
            int avail = Math.min(chunkLen, remaining);
            byte[] chunk = new byte[avail]; System.arraycopy(ciphertext, off, chunk, 0, avail); off += avail;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(km.key(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            out.write(c.doFinal(chunk));
        }
        return out.toByteArray();
    }

    private ChunkCipher() {}
}
