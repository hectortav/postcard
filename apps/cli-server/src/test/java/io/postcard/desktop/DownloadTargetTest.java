package io.postcard.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadTargetTest {

    private static final Path DIR = Path.of("/downloads");

    /** Existence predicate over a fixed set of names, so no disk is touched. */
    private static Predicate<Path> taken(String... names) {
        Set<Path> set = Arrays.stream(names).map(DIR::resolve).collect(Collectors.toSet());
        return set::contains;
    }

    @Test void usesTheSuggestedNameWhenFree() {
        assertEquals(DIR.resolve("report.pdf"),
            DownloadTarget.resolve(DIR, "report.pdf", taken()));
    }

    @Test void suffixesOnCollision() {
        assertEquals(DIR.resolve("report (1).pdf"),
            DownloadTarget.resolve(DIR, "report.pdf", taken("report.pdf")));
    }

    @Test void keepsCountingPastSeveralCollisions() {
        assertEquals(DIR.resolve("report (3).pdf"),
            DownloadTarget.resolve(DIR, "report.pdf",
                taken("report.pdf", "report (1).pdf", "report (2).pdf")));
    }

    @Test void handlesNamesWithoutAnExtension() {
        assertEquals(DIR.resolve("LICENSE (1)"),
            DownloadTarget.resolve(DIR, "LICENSE", taken("LICENSE")));
    }

    @Test void stripsPathSeparatorsFromTheSuggestedName() {
        assertEquals(DIR.resolve("passwd"),
            DownloadTarget.resolve(DIR, "../../etc/passwd", taken()),
            "the name comes from the page, so a download must never escape the target directory");
    }

    @Test void stripsWindowsPathSeparatorsToo() {
        assertEquals(DIR.resolve("passwd"),
            DownloadTarget.resolve(DIR, "..\\..\\etc\\passwd", taken()));
    }

    @Test void fallsBackWhenTheNameIsUnusable() {
        assertEquals(DIR.resolve("download"),
            DownloadTarget.resolve(DIR, "   ", taken()));
    }

    @Test void fallsBackWhenTheNameIsOnlyDots() {
        assertEquals(DIR.resolve("download"),
            DownloadTarget.resolve(DIR, "..", taken()));
    }

    @Test void dotfilesKeepTheirLeadingDot() {
        assertEquals(DIR.resolve(".gitignore (1)"),
            DownloadTarget.resolve(DIR, ".gitignore", taken(".gitignore")),
            "a leading dot starts the name, it does not start an extension");
    }
}
