package io.postcard.crypto;

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

    /**
     * Associated data for one chunk: its index, and whether it ends the file.
     *
     * <p>Each chunk was individually authenticated but nothing tied it to its position, so an
     * attacker on the LAN could reorder chunks, drop the tail, or splice chunks from another
     * file and every GCM tag would still verify. Binding the index defeats reordering; binding
     * the final-chunk flag defeats truncation, because the chunk left at the end no longer
     * claims to be the end; and binding the file id defeats splicing between files, which
     * matters because one session encrypts every file under the same key.
     *
     * <p>AAD is authenticated, not transmitted, so this costs no bytes on the wire and
     * {@link #chunkContentLength(long)} is unchanged.
     */
    static byte[] aad(long index, boolean last, String fileId) {
        byte[] id = (fileId == null ? "" : fileId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.nio.ByteBuffer.allocate(9 + id.length)
            .putLong(index).put((byte) (last ? 1 : 0)).put(id).array();
    }

    /** Returns total ciphertext+tag bytes for a plaintext of the given size. */
    public static long chunkContentLength(long plaintextSize) {
        if (plaintextSize < 0) throw new IllegalArgumentException();
        long fullChunks = plaintextSize / CHUNK_BYTES;
        long rem = plaintextSize % CHUNK_BYTES;
        return (fullChunks + (rem == 0 ? 0 : 1)) * (NONCE_BYTES + CHUNK_BYTES + TAG_BYTES) - (rem == 0 ? 0 : (CHUNK_BYTES - rem));
    }

    public static byte[] encryptAll(byte[] plaintext, KeyMaterial km) throws Exception {
        return encryptAll(plaintext, km, "");
    }

    public static byte[] encryptAll(byte[] plaintext, KeyMaterial km, String fileId) throws Exception {
        var out = new ByteArrayOutputStream();
        encryptStream(new java.io.ByteArrayInputStream(plaintext), out, km, fileId);
        return out.toByteArray();
    }

    /** Streams the ciphertext to {@code out}. Returns the number of bytes written. */
    public static long encryptStream(InputStream in, OutputStream out, KeyMaterial km) throws IOException {
        return encryptStream(in, out, km, "");
    }

    /** Streams the ciphertext to {@code out}, binding each chunk to {@code fileId}. */
    public static long encryptStream(InputStream in, OutputStream out, KeyMaterial km, String fileId)
            throws IOException {
        long total = 0;
        byte[] cur = new byte[CHUNK_BYTES];
        int n = in.readNBytes(cur, 0, CHUNK_BYTES);
        if (n <= 0) return 0; // empty file: nothing to authenticate
        long index = 0;
        while (true) {
            // One chunk of lookahead, because a chunk's AAD has to say whether it is the last
            // and a full-sized chunk might still be followed by nothing.
            byte[] next = new byte[CHUNK_BYTES];
            int m = (n == CHUNK_BYTES) ? in.readNBytes(next, 0, CHUNK_BYTES) : 0;
            boolean last = m <= 0;
            byte[] nonce = new byte[NONCE_BYTES]; RNG.nextBytes(nonce);
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(km.key(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
                c.updateAAD(aad(index, last, fileId));
                byte[] ct = c.doFinal(cur, 0, n);
                out.write(nonce);
                out.write(ct);
                total += nonce.length + ct.length;
            } catch (Exception e) { throw new IOException("encrypt chunk", e); }
            if (last) break;
            cur = next; n = m; index++;
        }
        return total;
    }

    public static byte[] decryptAll(byte[] ciphertext, KeyMaterial km) throws Exception {
        return decryptAll(ciphertext, km, "");
    }

    public static byte[] decryptAll(byte[] ciphertext, KeyMaterial km, String fileId) throws Exception {
        var out = new ByteArrayOutputStream();
        long index = 0;
        for (int off = 0; off < ciphertext.length; ) {
            byte[] nonce = new byte[NONCE_BYTES]; System.arraycopy(ciphertext, off, nonce, 0, NONCE_BYTES); off += NONCE_BYTES;
            int remaining = ciphertext.length - off;
            int chunkLen = CHUNK_BYTES + TAG_BYTES;
            int avail = Math.min(chunkLen, remaining);
            byte[] chunk = new byte[avail]; System.arraycopy(ciphertext, off, chunk, 0, avail); off += avail;
            boolean last = off >= ciphertext.length;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(km.key(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(index, last, fileId));
            out.write(c.doFinal(chunk));
            index++;
        }
        return out.toByteArray();
    }

    private ChunkCipher() {}
}
