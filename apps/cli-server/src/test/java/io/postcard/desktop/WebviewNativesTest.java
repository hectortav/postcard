package io.postcard.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebviewNativesTest {

    @Test void overrideWinsOverEverything() {
        Path got = WebviewNatives.resolve("/opt/webview", Path.of("/app"), Path.of("/dev"));
        assertEquals(Path.of("/opt/webview"), got,
            "-Dpostcard.webview.home must take precedence so a packaged app can be pointed elsewhere");
    }

    @Test void packagedAppUsesTheJarSibling() {
        Path got = WebviewNatives.resolve(null, Path.of("/app"), Path.of("/dev"));
        assertEquals(Path.of("/app/webview-natives"), got);
    }

    @Test void blankOverrideIsIgnored() {
        Path got = WebviewNatives.resolve("   ", Path.of("/app"), Path.of("/dev"));
        assertEquals(Path.of("/app/webview-natives"), got,
            "an empty -D value must not resolve to the filesystem root");
    }

    @Test void developmentFallsBackToTheBuildDirectory() {
        Path got = WebviewNatives.resolve(null, null, Path.of("/dev"));
        assertEquals(Path.of("/dev/webview-natives"), got);
    }
}
