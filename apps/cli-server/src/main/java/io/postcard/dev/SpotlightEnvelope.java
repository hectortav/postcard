package io.postcard.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Builds Sentry envelopes for the local Spotlight sidecar.
 *
 * <p>postcard deliberately does <em>not</em> depend on sentry-java. The shipped JAR belongs to a
 * tool whose entire premise is that it never phones home, and adding a telemetry SDK to it — even
 * one configured with no DSN — is a bigger promise to keep than the dev-time debugging is worth.
 * The wire format is small enough to write directly: an envelope is a header line, an item header
 * line, and the item payload, newline-separated, POSTed to {@code /stream} as
 * {@code application/x-sentry-envelope}. That costs one class and Jackson, which is already a
 * dependency.
 *
 * <p>Two item types are produced, matching the two things the {@code sentry-spotlight} MCP server
 * can search:
 * <ul>
 *   <li>{@code event} — an exception with a stack trace, surfaced by {@code search_errors}.</li>
 *   <li>{@code log} — a structured log line, surfaced by {@code search_logs}.</li>
 * </ul>
 *
 * <p>Both shapes were verified against Spotlight 4.11.8 by posting them to a live sidecar and
 * reading them back through the MCP server, which is the only way to be sure: the sidecar accepts
 * an ill-formed envelope with a cheerful 200 and then silently shows nothing.
 *
 * <p>Everything here is pure — {@link SpotlightAppender} owns the socket — so the format stays
 * unit-testable without a sidecar running.
 */
public final class SpotlightEnvelope {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SENT_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private SpotlightEnvelope() {}

    /** An {@code event} item: one exception, with its causal chain flattened into frames. */
    public static byte[] error(
            String eventId, Instant at, String loggerName, Throwable t, Map<String, String> tags) {
        var event = MAPPER.createObjectNode();
        event.put("event_id", eventId);
        // Sentry takes `timestamp` as epoch seconds with a fractional part, not ISO-8601.
        event.put("timestamp", at.toEpochMilli() / 1000.0);
        event.put("platform", "java");
        event.put("level", "error");
        event.put("logger", loggerName);
        event.put("environment", "development");

        var values = event.putObject("exception").putArray("values");
        // Sentry renders the *last* value as the outermost exception, so the causal chain goes in
        // cause-first order: the root cause is what you want at the top of the stack trace.
        for (Throwable cur = deepestCause(t); cur != null; cur = nextTowards(t, cur)) {
            appendException(values, cur);
        }
        putTags(event, tags);
        return envelope(eventId, at, "event", MAPPER.valueToTree(event), null);
    }

    /** A {@code log} item: one structured line, with its logger and tags as searchable attributes. */
    public static byte[] log(
            String eventId,
            Instant at,
            String loggerName,
            String level,
            String message,
            Map<String, String> tags) {
        var item = MAPPER.createObjectNode();
        item.put("timestamp", at.toEpochMilli() / 1000.0);
        item.put("level", level);
        item.put("body", message);

        var attributes = item.putObject("attributes");
        putAttribute(attributes, "sentry.severity_text", level);
        putAttribute(attributes, "logger.name", loggerName);
        tags.forEach((k, v) -> putAttribute(attributes, k, v));

        var payload = MAPPER.createObjectNode();
        payload.putArray("items").add(item);
        return envelope(eventId, at, "log", payload, "application/vnd.sentry.items.log+json");
    }

    /** Serialises the three-line envelope. {@code contentType} is only set for log items. */
    private static byte[] envelope(
            String eventId, Instant at, String itemType, Object payload, String contentType) {
        try {
            var header = MAPPER.createObjectNode();
            header.put("event_id", eventId);
            header.put("sent_at", SENT_AT.format(at));

            byte[] body = MAPPER.writeValueAsBytes(payload);

            var itemHeader = MAPPER.createObjectNode();
            itemHeader.put("type", itemType);
            // `length` must be the byte count, not the character count — a filename with a
            // non-ASCII character in it would otherwise truncate the item.
            itemHeader.put("length", body.length);
            if (contentType != null) {
                itemHeader.put("item_count", 1);
                itemHeader.put("content_type", contentType);
            }

            var out = new java.io.ByteArrayOutputStream();
            out.write(MAPPER.writeValueAsBytes(header));
            out.write('\n');
            out.write(MAPPER.writeValueAsBytes(itemHeader));
            out.write('\n');
            out.write(body);
            out.write('\n');
            return out.toByteArray();
        } catch (java.io.IOException e) {
            // Both sinks are in-memory, so this is unreachable; it is not worth propagating a
            // checked exception through a dev-only reporter for a case that cannot happen.
            throw new IllegalStateException("failed to serialise Spotlight envelope", e);
        }
    }

    private static void appendException(ArrayNode values, Throwable t) {
        var value = values.addObject();
        value.put("type", t.getClass().getSimpleName());
        value.put("value", String.valueOf(t.getMessage()));
        value.put("module", t.getClass().getPackageName());

        var frames = value.putObject("stacktrace").putArray("frames");
        var trace = t.getStackTrace();
        // Java hands back the stack innermost-first; Sentry expects the opposite, and renders the
        // last frame as the crash site.
        for (int i = trace.length - 1; i >= 0; i--) {
            var f = trace[i];
            var frame = frames.addObject();
            frame.put("filename", f.getFileName());
            frame.put("module", f.getClassName());
            frame.put("function", f.getMethodName());
            frame.put("lineno", f.getLineNumber());
            // Drives Sentry's "in app" grouping, which is what makes postcard's own frames stand
            // out from the Jetty and JDK frames sandwiching them.
            frame.put("in_app", f.getClassName().startsWith("io.postcard"));
        }
    }

    private static void putTags(ObjectNode event, Map<String, String> tags) {
        if (tags.isEmpty()) return;
        var node = event.putObject("tags");
        tags.forEach(node::put);
    }

    private static void putAttribute(ObjectNode attributes, String key, String value) {
        var attr = attributes.putObject(key);
        attr.put("value", value);
        attr.put("type", "string");
    }

    /** The root cause, or {@code t} itself when there is no cause chain. */
    private static Throwable deepestCause(Throwable t) {
        var cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur;
    }

    /** Walks one link back up the chain from {@code from} towards {@code root}. */
    private static Throwable nextTowards(Throwable root, Throwable from) {
        if (from == root) return null;
        var cur = root;
        while (cur.getCause() != from) {
            cur = cur.getCause();
            if (cur == null) return null;
        }
        return cur;
    }
}
