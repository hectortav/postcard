package io.sendme.crypto;

import java.security.SecureRandom;

public final class Ids {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
    private static final SecureRandom RNG = new SecureRandom();
    public static String newId() {
        var sb = new StringBuilder(21);
        for (int i = 0; i < 21; i++) sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        return sb.toString();
    }
    private Ids() {}
}
