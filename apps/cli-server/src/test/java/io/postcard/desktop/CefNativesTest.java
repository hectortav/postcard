package io.postcard.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CefNativesTest {

    @Test void overrideWinsOverEverything() {
        Path got = CefNatives.resolve("/opt/cef", Path.of("/app"), Path.of("/dev"));
        assertEquals(Path.of("/opt/cef"), got,
            "-Dpostcard.cef.home must take precedence so a packaged app can be pointed elsewhere");
    }

    @Test void packagedAppUsesTheJarSibling() {
        Path got = CefNatives.resolve(null, Path.of("/app"), Path.of("/dev"));
        assertEquals(Path.of("/app/jcef-bundle"), got);
    }

    @Test void blankOverrideIsIgnored() {
        Path got = CefNatives.resolve("   ", Path.of("/app"), Path.of("/dev"));
        assertEquals(Path.of("/app/jcef-bundle"), got,
            "an empty -D value must not resolve to the filesystem root");
    }

    @Test void developmentFallsBackToTheBuildDirectory() {
        Path got = CefNatives.resolve(null, null, Path.of("/dev"));
        assertEquals(Path.of("/dev/jcef-bundle"), got);
    }
}
