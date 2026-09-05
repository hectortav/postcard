package io.postcard.dev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpotlightEnvelopeTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Instant AT = Instant.ofEpochMilli(1_757_100_000_500L);

    /** Splits the three-line envelope: header, item header, payload. */
    private record Parsed(JsonNode header, JsonNode itemHeader, JsonNode payload, byte[] rawPayload) {}

    private static Parsed parse(byte[] envelope) throws Exception {
        var text = new String(envelope, StandardCharsets.UTF_8);
        var lines = text.split("\n", 3);
        var payloadText = lines[2].substring(0, lines[2].length() - 1); // trailing newline
        return new Parsed(
                M.readTree(lines[0]),
                M.readTree(lines[1]),
                M.readTree(payloadText),
                payloadText.getBytes(StandardCharsets.UTF_8));
    }

    // The sidecar answers 200 to a malformed envelope and then shows nothing, so the byte-count
    // header is worth pinning: a character count would truncate any payload containing non-ASCII
    // (a filename with an umlaut is enough) and the event would silently vanish.
    @Test void itemLengthIsMeasuredInBytesNotCharacters() throws Exception {
        var t = new IllegalStateException("naïve — mojibake ☠");
        var parsed = parse(SpotlightEnvelope.error("abc", AT, "io.postcard.Test", t, Map.of()));

        assertEquals(parsed.rawPayload().length, parsed.itemHeader().get("length").asInt());
        assertTrue(parsed.rawPayload().length > parsed.payload().toString().length() - 40,
                "payload should contain multi-byte characters for this test to mean anything");
    }

    @Test void errorEnvelopeCarriesTheExceptionAndItsFrames() throws Exception {
        var boom = new IllegalStateException("upload exceeded max size");
        var parsed = parse(
                SpotlightEnvelope.error("e1", AT, "io.postcard.server.Server", boom,
                        Map.of("component", "cli-server")));

        assertEquals("event", parsed.itemHeader().get("type").asText());
        assertEquals("e1", parsed.header().get("event_id").asText());
        assertEquals("2025-09-05T19:20:00Z", parsed.header().get("sent_at").asText());

        var payload = parsed.payload();
        assertEquals("java", payload.get("platform").asText());
        assertEquals("error", payload.get("level").asText());
        assertEquals("io.postcard.server.Server", payload.get("logger").asText());
        // Epoch seconds with a fractional part, not ISO-8601 — Sentry rejects the string form here.
        assertEquals(1_757_100_000.5, payload.get("timestamp").asDouble(), 0.001);
        assertEquals("cli-server", payload.get("tags").get("component").asText());

        var value = payload.get("exception").get("values").get(0);
        assertEquals("IllegalStateException", value.get("type").asText());
        assertEquals("upload exceeded max size", value.get("value").asText());
        assertTrue(value.get("stacktrace").get("frames").size() > 0);
    }

    // Sentry renders the last value as the outermost exception, so a wrapped cause has to be
    // emitted root-first or the UI shows the wrapper as the root cause.
    @Test void causeChainIsEmittedRootCauseFirst() throws Exception {
        var root = new java.io.IOException("disk full");
        var wrapper = new IllegalStateException("could not persist upload", root);
        var values = parse(SpotlightEnvelope.error("e2", AT, "l", wrapper, Map.of()))
                .payload().get("exception").get("values");

        assertEquals(2, values.size());
        assertEquals("IOException", values.get(0).get("type").asText());
        assertEquals("IllegalStateException", values.get(1).get("type").asText());
    }

    @Test void selfReferencingCauseDoesNotHang() throws Exception {
        var t = new IllegalStateException("loop");
        t.initCause(t.getCause()); // no-op, but guards the deepestCause loop's identity check
        assertDoesNotThrow(() -> SpotlightEnvelope.error("e3", AT, "l", t, Map.of()));
    }

    // in_app is what separates postcard's frames from the Jetty/JDK frames around them.
    @Test void marksPostcardFramesAsInApp() throws Exception {
        var frames = parse(SpotlightEnvelope.error("e4", AT, "l", new RuntimeException("x"), Map.of()))
                .payload().get("exception").get("values").get(0)
                .get("stacktrace").get("frames");

        var sawInApp = false;
        for (var f : frames) {
            if (f.get("module").asText().startsWith("io.postcard")) {
                assertTrue(f.get("in_app").asBoolean());
                sawInApp = true;
            }
        }
        assertTrue(sawInApp, "the test's own frames are io.postcard.*");
    }

    @Test void logEnvelopeUsesTheStructuredLogItemShape() throws Exception {
        var parsed = parse(SpotlightEnvelope.log("l1", AT, "io.postcard.security.PinRateLimiter",
                "warn", "pin lockout engaged", Map.of("component", "cli-server")));

        assertEquals("log", parsed.itemHeader().get("type").asText());
        assertEquals(1, parsed.itemHeader().get("item_count").asInt());
        assertEquals("application/vnd.sentry.items.log+json",
                parsed.itemHeader().get("content_type").asText());

        var item = parsed.payload().get("items").get(0);
        assertEquals("warn", item.get("level").asText());
        assertEquals("pin lockout engaged", item.get("body").asText());

        var attrs = item.get("attributes");
        assertEquals("warn", attrs.get("sentry.severity_text").get("value").asText());
        assertEquals("io.postcard.security.PinRateLimiter", attrs.get("logger.name").get("value").asText());
        assertEquals("cli-server", attrs.get("component").get("value").asText());
        assertEquals("string", attrs.get("component").get("type").asText());
    }

    @Test void omitsTagsNodeWhenThereAreNone() throws Exception {
        var payload = parse(SpotlightEnvelope.error("e5", AT, "l", new RuntimeException("x"), Map.of()))
                .payload();
        assertFalse(payload.has("tags"));
    }

    // The opt-in rule. A stray `POSTCARD_SPOTLIGHT=` in a shell profile must not open a socket on
    // a user's machine, so anything that is not an explicit yes means off.
    @Test void resolveEndpointOnlyOptsInOnAnExplicitYes() {
        assertEquals(URI.create(SpotlightAppender.DEFAULT_ENDPOINT),
                SpotlightAppender.resolveEndpoint("1"));
        assertEquals(URI.create(SpotlightAppender.DEFAULT_ENDPOINT),
                SpotlightAppender.resolveEndpoint("TRUE"));
        assertEquals(URI.create(SpotlightAppender.DEFAULT_ENDPOINT),
                SpotlightAppender.resolveEndpoint(" on "));
        assertEquals(URI.create("http://192.168.1.5:8969/stream"),
                SpotlightAppender.resolveEndpoint("http://192.168.1.5:8969/stream"));

        assertNull(SpotlightAppender.resolveEndpoint(null));
        assertNull(SpotlightAppender.resolveEndpoint(""));
        assertNull(SpotlightAppender.resolveEndpoint("   "));
        assertNull(SpotlightAppender.resolveEndpoint("0"));
        assertNull(SpotlightAppender.resolveEndpoint("false"));
        assertNull(SpotlightAppender.resolveEndpoint("please"));
    }
}
