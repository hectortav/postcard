package io.postcard.crypto;

public record KeyMaterial(byte[] key) {
    public KeyMaterial { if (key == null || key.length != 32) throw new IllegalArgumentException("key must be 32 bytes"); }
}
