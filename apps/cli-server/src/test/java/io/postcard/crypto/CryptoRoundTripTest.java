package io.postcard.crypto;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.security.SecureRandom;
import static org.junit.jupiter.api.Assertions.*;

class CryptoRoundTripTest {
    private static byte[] key() { var k = new byte[32]; new SecureRandom().nextBytes(k); return k; }

    @Test void roundTrip() throws Exception {
        var km = new KeyMaterial(key());
        var rng = new SecureRandom();
        for (int size : new int[]{1024, 1024 * 1024, 100 * 1024 * 1024}) {
            byte[] pt = new byte[size]; rng.nextBytes(pt);
            var enc = ChunkCipher.encryptAll(pt, km);
            var dec = ChunkCipher.decryptAll(enc, km);
            assertArrayEquals(pt, dec);
        }
    }
    @Test void streamingRoundTrip() throws Exception {
        var km = new KeyMaterial(key());
        var rng = new SecureRandom();
        int size = 5_000_000; // not a multiple of 64 KiB — exercises the short-final-chunk path
        byte[] pt = new byte[size]; rng.nextBytes(pt);
        var baos = new ByteArrayOutputStream();
        var written = ChunkCipher.encryptStream(new ByteArrayInputStream(pt), baos, km);
        assertEquals(written, baos.size());
        var dec = ChunkCipher.decryptAll(baos.toByteArray(), km);
        assertArrayEquals(pt, dec);
    }
    @Test void shortFinalChunkRoundTrip() throws Exception {
        var km = new KeyMaterial(key());
        byte[] pt = new byte[100_000]; new SecureRandom().nextBytes(pt); // 100k ≠ 64k
        var enc = ChunkCipher.encryptAll(pt, km);
        var dec = ChunkCipher.decryptAll(enc, km);
        assertArrayEquals(pt, dec);
    }
    @Test void tagMismatchThrows() throws Exception {
        var km = new KeyMaterial(key());
        byte[] pt = "hello, postcard".getBytes();
        var enc = ChunkCipher.encryptAll(pt, km);
        enc[20] ^= 1;
        assertThrows(Exception.class, () -> ChunkCipher.decryptAll(enc, km));
    }
    @Test void chunkContentLengthMatchesEncryptAll() throws Exception {
        for (long size : new long[]{1, 65_536, 65_537, 1_000_000, 1_000_001}) {
            var km = new KeyMaterial(key());
            byte[] pt = new byte[(int) size]; new SecureRandom().nextBytes(pt);
            var enc = ChunkCipher.encryptAll(pt, km);
            assertEquals(enc.length, ChunkCipher.chunkContentLength(size));
        }
    }

    // --- Chunk position is authenticated ---------------------------------------------
    //
    // Every chunk carries its own nonce and tag, so each one verifies on its own. That was
    // the whole protection: nothing bound a chunk to where it sat in the file, which left an
    // attacker on the network free to rearrange the stream and still pass every tag check.

    private static final int WIRE_CHUNK = 12 + 64 * 1024 + 16;

    /** Ciphertext for a plaintext of {@code chunks} full 64 KiB chunks plus a short tail. */
    private static byte[] multiChunk(KeyMaterial km, int chunks) throws Exception {
        return multiChunk(km, chunks, "file-one");
    }

    private static byte[] multiChunk(KeyMaterial km, int chunks, String fileId) throws Exception {
        byte[] pt = new byte[chunks * 64 * 1024 + 100];
        new SecureRandom().nextBytes(pt);
        return ChunkCipher.encryptAll(pt, km, fileId);
    }

    @Test void reorderingChunksIsDetected() throws Exception {
        var km = new KeyMaterial(key());
        var ct = multiChunk(km, 3);
        // Swap the first two on-the-wire chunks.
        byte[] tampered = ct.clone();
        System.arraycopy(ct, WIRE_CHUNK, tampered, 0, WIRE_CHUNK);
        System.arraycopy(ct, 0, tampered, WIRE_CHUNK, WIRE_CHUNK);
        assertThrows(Exception.class, () -> ChunkCipher.decryptAll(tampered, km, "file-one"),
            "a reordered stream must not decrypt");
    }

    @Test void truncatingTheStreamIsDetected() throws Exception {
        var km = new KeyMaterial(key());
        var ct = multiChunk(km, 3);
        // Drop the final short chunk. Every remaining chunk is intact and individually valid.
        byte[] truncated = java.util.Arrays.copyOf(ct, 3 * WIRE_CHUNK);
        assertThrows(Exception.class, () -> ChunkCipher.decryptAll(truncated, km, "file-one"),
            "a truncated stream must not decrypt: the last chunk no longer claims to be last");
    }

    @Test void splicingAChunkFromAnotherFileIsDetected() throws Exception {
        // Same key, two files -- exactly the situation in one postcard session.
        var km = new KeyMaterial(key());
        var a = multiChunk(km, 3, "file-one");
        var b = multiChunk(km, 3, "file-two");
        byte[] spliced = a.clone();
        System.arraycopy(b, WIRE_CHUNK, spliced, WIRE_CHUNK, WIRE_CHUNK);
        assertThrows(Exception.class, () -> ChunkCipher.decryptAll(spliced, km, "file-one"),
            "a chunk lifted from another file must not decrypt in this one");
    }

    @Test void anEmptyFileProducesAnEmptyStream() throws Exception {
        var km = new KeyMaterial(key());
        var ct = ChunkCipher.encryptAll(new byte[0], km);
        assertEquals(0, ct.length);
        assertEquals(0, ChunkCipher.chunkContentLength(0));
        assertArrayEquals(new byte[0], ChunkCipher.decryptAll(ct, km));
    }

    @Test void theAdvertisedLengthMatchesTheBytesProduced() throws Exception {
        // The download route sets Content-Length from chunkContentLength before streaming, so
        // a mismatch would hang the client waiting for bytes that never come.
        var km = new KeyMaterial(key());
        for (int size : new int[]{0, 1, 100, 64 * 1024 - 1, 64 * 1024, 64 * 1024 + 1, 3 * 64 * 1024 + 7}) {
            byte[] pt = new byte[size];
            new SecureRandom().nextBytes(pt);
            var out = new ByteArrayOutputStream();
            long written = ChunkCipher.encryptStream(new ByteArrayInputStream(pt), out, km);
            assertEquals(ChunkCipher.chunkContentLength(size), written, "size " + size);
            assertEquals(written, out.size(), "size " + size);
            assertArrayEquals(pt, ChunkCipher.decryptAll(out.toByteArray(), km), "size " + size);
        }
    }
}
