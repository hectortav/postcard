package io.postcard;

import io.postcard.server.PostcardOptions;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The command line's own surface. {@code --help} is the first thing anyone runs and the
 * README documents it, but nothing exercised it: the standard help mixin was not being
 * registered at all, so {@code postcard --help} exited 2 with "Unknown option".
 */
class PostcardOptionsHelpTest {

    private static CommandLine cmd() { return new CommandLine(new PostcardOptions()); }

    @Test void helpAndVersionOptionsExist() {
        var spec = cmd().getCommandSpec();
        assertNotNull(spec.findOption("--help"), "--help must be a real option");
        assertNotNull(spec.findOption("--version"), "--version must be a real option");
    }

    @Test void helpIsRecognisedAndRequestsUsage() {
        var c = cmd();
        assertEquals(0, c.execute("--help"));
        assertTrue(c.getParseResult().isUsageHelpRequested());
    }

    @Test void versionIsRecognisedAndReportsTheProjectVersion() {
        var c = cmd();
        assertEquals(0, c.execute("--version"));
        assertTrue(c.getParseResult().isVersionHelpRequested());
    }

    @Test void hostKeepsItsShortForm() {
        // -h belongs to --host here, which is why the help mixin cannot use it.
        var spec = cmd().getCommandSpec();
        assertNotNull(spec.findOption("-h"));
        assertTrue(java.util.Arrays.asList(spec.findOption("-h").names()).contains("--host"));
    }

    @Test void pinMayBeGivenWithoutAValue() {
        // Documented in the README as auto-generating a PIN.
        var opts = new PostcardOptions();
        new CommandLine(opts).parseArgs("--pin");
        assertNotNull(opts.pin);
        assertTrue(opts.pin.isBlank(), "a bare --pin means 'generate one'");
    }

    @Test void theLogLevelDefaultsToInfoAndFollowsQuiet() {
        // Before this there was no way to change what postcard printed short of editing
        // logback.xml inside the jar.
        var opts = new PostcardOptions();
        assertEquals("INFO", opts.resolvedLogLevel());
        opts.quiet = true;
        assertEquals("WARN", opts.resolvedLogLevel());
    }

    @Test void anExplicitLogLevelWinsOverQuiet() {
        var opts = new PostcardOptions();
        opts.quiet = true;
        opts.logLevel = "debug";
        assertEquals("DEBUG", opts.resolvedLogLevel());
    }

    @Test void logLevelsAreCaseInsensitiveAndNonsenseFallsBackToInfo() {
        var opts = new PostcardOptions();
        for (var given : new String[] {"error", "WARN", "Info", "debug", "trace", "off"}) {
            opts.logLevel = given;
            assertEquals(given.toUpperCase(java.util.Locale.ROOT), opts.resolvedLogLevel());
        }
        opts.logLevel = "loud";
        assertEquals("INFO", opts.resolvedLogLevel(), "an unknown level must not silence postcard");
        opts.logLevel = "   ";
        assertEquals("INFO", opts.resolvedLogLevel());
    }

    @Test void unknownOptionsAreRejected() {
        assertNotEquals(0, cmd().execute("--definitely-not-an-option"));
    }
}
