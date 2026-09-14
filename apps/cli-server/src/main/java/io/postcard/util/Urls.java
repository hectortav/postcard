package io.postcard.util;

/**
 * URL handling for things that get written somewhere a human or another process can read.
 *
 * <p>postcard's dashboard URL carries the AES key and the PIN in its fragment
 * ({@code #key=...&pin=1234}). That URL was being written to the application log, to the tray
 * icon's tooltip and — on macOS — to the system-wide unified log, which persists to disk and is
 * readable by any local user. The fragment is the secret; everything that logs a URL logs the
 * redacted form instead.
 */
public final class Urls {
    private Urls() {}

    /**
     * The URL with its fragment replaced by a marker, so a log line still identifies the
     * session without disclosing the key or the PIN. Null and fragment-less input pass through.
     */
    public static String redactFragment(String url) {
        if (url == null) return null;
        int hash = url.indexOf('#');
        if (hash < 0) return url;
        if (hash == url.length() - 1) return url.substring(0, hash);
        return url.substring(0, hash) + "#<redacted>";
    }
}
