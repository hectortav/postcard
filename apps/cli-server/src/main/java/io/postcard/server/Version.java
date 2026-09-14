package io.postcard.server;

import java.io.InputStream;
import java.util.Properties;

/**
 * The project version, as {@code --version} reports it.
 *
 * <p>Read from a resource the build writes from the Gradle {@code version}, because the value
 * used to be a string literal in an annotation: a release could ship binaries whose
 * {@code --version} disagreed with the tag, the installer and {@code package.json}, and
 * nothing would notice.
 */
public final class Version {
    private static final String FALLBACK = "0.0.0-dev";
    private static final String VALUE = load();

    private Version() {}

    public static String value() { return VALUE; }

    /**
     * Supplies {@code --version} output. A provider rather than the annotation's {@code version}
     * attribute, because an annotation value has to be a compile-time constant and this one is
     * read from the build at runtime.
     */
    public static final class Provider implements picocli.CommandLine.IVersionProvider {
        @Override public String[] getVersion() { return new String[] {"postcard " + value()}; }
    }

    private static String load() {
        try (InputStream in = Version.class.getResourceAsStream("/postcard-version.properties")) {
            if (in == null) return FALLBACK;
            var props = new Properties();
            props.load(in);
            var v = props.getProperty("version");
            return (v == null || v.isBlank()) ? FALLBACK : v.trim();
        } catch (Exception e) {
            return FALLBACK;
        }
    }
}
