package io.postcard.desktop;

import java.io.File;
import java.nio.file.Path;

/**
 * Locates the bundled JCEF natives.
 *
 * <p>Resolution is a pure function of three inputs so every branch is testable without a
 * filesystem: an explicit override, the directory holding the running jar (the packaged case),
 * and the Gradle build directory (the development case).
 *
 * <p>The application never downloads natives. The {@code installCefNatives} Gradle task put
 * them next to the jar at build time, which is what lets a first launch work with no network —
 * the situation postcard is most often reached for.
 */
public final class CefNatives {
    /** Escape hatch: point a packaged app at a different natives directory. */
    public static final String HOME_PROPERTY = "postcard.cef.home";

    private static final String DIR_NAME = "jcef-bundle";

    private CefNatives() {}

    /**
     * @param override value of {@link #HOME_PROPERTY}, or null/blank when unset
     * @param jarDir   directory containing the running jar, or null when it cannot be determined
     * @param devDir   the Gradle build directory, used as the last resort
     */
    public static Path resolve(String override, Path jarDir, Path devDir) {
        if (override != null && !override.isBlank()) return Path.of(override.trim());
        if (jarDir != null) return jarDir.resolve(DIR_NAME);
        return devDir.resolve(DIR_NAME);
    }

    /** Convenience overload reading the real system properties. */
    public static Path locate() {
        return resolve(System.getProperty(HOME_PROPERTY), jarDir(), Path.of("build"));
    }

    /**
     * Directory holding the running jar. In a jpackage image the classpath is a single jar in
     * the app directory, which is exactly where {@code jpackageInput} staged the natives. A
     * multi-entry classpath means we are not running from an app image, so this gives up and
     * lets the development fallback apply.
     */
    private static Path jarDir() {
        try {
            String cp = System.getProperty("java.class.path", "");
            if (cp.isBlank() || cp.contains(File.pathSeparator)) return null;
            return Path.of(cp).toAbsolutePath().getParent();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
