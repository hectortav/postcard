package io.postcard.desktop;

import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Chooses where a download lands.
 *
 * <p>Split out from the CEF download handler and made a pure function of (directory, suggested
 * name, existence predicate) so collision behaviour is testable without touching a disk — the
 * handler itself is then barely more than a call to this plus
 * {@code callback.Continue(path, false)}.
 *
 * <p>The suggested name arrives from the page, so it is treated as untrusted: only the final
 * path element is kept, which is what stops {@code ../../etc/passwd} from escaping the download
 * directory.
 */
public final class DownloadTarget {
    private static final String FALLBACK = "download";

    /** Bound on the collision search; a thousand same-named files is already pathological. */
    private static final int MAX_ATTEMPTS = 1_000;

    private DownloadTarget() {}

    public static Path resolve(Path dir, String suggestedName, Predicate<Path> exists) {
        String name = sanitize(suggestedName);
        Path candidate = dir.resolve(name);
        if (!exists.test(candidate)) return candidate;

        String stem = stem(name);
        String ext = extension(name);
        for (int n = 1; n < MAX_ATTEMPTS; n++) {
            candidate = dir.resolve(stem + " (" + n + ")" + ext);
            if (!exists.test(candidate)) return candidate;
        }
        // Timestamp rather than loop forever or overwrite someone's file.
        return dir.resolve(stem + " (" + System.currentTimeMillis() + ")" + ext);
    }

    /** Last path element only, so a crafted name cannot escape the directory. */
    private static String sanitize(String suggested) {
        if (suggested == null || suggested.isBlank()) return FALLBACK;
        String trimmed = suggested.trim().replace('\\', '/');
        int slash = trimmed.lastIndexOf('/');
        String last = slash < 0 ? trimmed : trimmed.substring(slash + 1);
        if (last.isBlank() || last.equals(".") || last.equals("..")) return FALLBACK;
        return last;
    }

    /** Everything before the final dot. A leading dot starts the name, not an extension. */
    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** The final dot onwards, or empty when the name has no extension. */
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }
}
