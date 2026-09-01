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
}
