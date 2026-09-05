package io.postcard.dev;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Forwards WARN/ERROR log events to a local Spotlight sidecar.
 *
 * <p>Wiring this in as a logback appender rather than a Javalin exception handler is deliberate.
 * Javalin's default handler already logs every uncaught route exception, and registering
 * {@code app.exception(...)} would <em>replace</em> that default — quietly changing both the
 * response and what lands on stdout. An appender is purely additive: postcard's HTTP behaviour,
 * status codes and console output are byte-for-byte what they were, and anything the server
 * already logs is picked up for free, including failures in the WebSocket and tray paths that
 * never touch a route handler.
 *
 * <p><b>Off unless asked for.</b> The appender is inert unless {@code POSTCARD_SPOTLIGHT} (env) or
 * {@code postcard.spotlight} (system property) is set. Users who run the released binary never
 * open a socket; there is no default-on path and no DSN anywhere in the codebase. Set the value to
 * {@code 1} or {@code true} for the default sidecar, or to a full URL to point somewhere else:
 *
 * <pre>{@code POSTCARD_SPOTLIGHT=1 java -jar postcard-cli-server-0.1.0-all.jar}</pre>
 *
 * <p>Delivery is best-effort and asynchronous: a missing sidecar must never slow down or break a
 * file transfer, so send failures are counted and swallowed, and the appender disables itself
 * after {@link #MAX_FAILURES} consecutive ones rather than retrying against a dead port.
 *
 * <p>Excluded from the JaCoCo gate as a thin shell over the network, matching the treatment of the
 * other I/O shells in this build. Everything decidable — the envelope format and the enablement
 * rule — lives in {@link SpotlightEnvelope} and {@link #resolveEndpoint}, which are pure and
 * covered.
 */
public final class SpotlightAppender extends AppenderBase<ILoggingEvent> {

    /** Where the {@code spotlight} CLI listens by default. */
    public static final String DEFAULT_ENDPOINT = "http://localhost:8969/stream";

    /** Consecutive send failures tolerated before the appender gives up for the process lifetime. */
    static final int MAX_FAILURES = 5;

    private static final Map<String, String> TAGS = Map.of("component", "cli-server");

    private final AtomicInteger failures = new AtomicInteger();
    private volatile HttpClient client;
    private volatile URI endpoint;

    /**
     * Resolves the sidecar endpoint from the opt-in switch, or {@code null} when disabled.
     *
     * <p>Accepts {@code 1}/{@code true}/{@code yes}/{@code on} for the default endpoint and any
     * {@code http://…} value as an explicit override. Anything else — unset, blank, {@code 0},
     * {@code false} — means off, so a stray {@code POSTCARD_SPOTLIGHT=} in a shell profile fails
     * safe rather than opening a socket.
     */
    static URI resolveEndpoint(String value) {
        if (value == null) return null;
        var v = value.trim();
        if (v.isEmpty()) return null;
        if (v.startsWith("http://") || v.startsWith("https://")) return URI.create(v);
        return switch (v.toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true", "yes", "on" -> URI.create(DEFAULT_ENDPOINT);
            default -> null;
        };
    }

    /** The opt-in switch, system property taking precedence over the environment variable. */
    static String configuredValue() {
        var prop = System.getProperty("postcard.spotlight");
        return prop != null ? prop : System.getenv("POSTCARD_SPOTLIGHT");
    }

    @Override
    public void start() {
        endpoint = resolveEndpoint(configuredValue());
        if (endpoint == null) return; // stays stopped: append() is never called
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        addInfo("Spotlight reporting enabled, streaming to " + endpoint);
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (failures.get() >= MAX_FAILURES) return;

        var level = event.getLevel().toString().toLowerCase(java.util.Locale.ROOT);
        var at = Instant.ofEpochMilli(event.getTimeStamp());
        var eventId = UUID.randomUUID().toString().replace("-", "");
        var proxy = event.getThrowableProxy();

        byte[] body;
        if (proxy instanceof ch.qos.logback.classic.spi.ThrowableProxy tp) {
            body = SpotlightEnvelope.error(eventId, at, event.getLoggerName(), tp.getThrowable(), TAGS);
        } else {
            body =
                    SpotlightEnvelope.log(
                            eventId,
                            at,
                            event.getLoggerName(),
                            level,
                            event.getFormattedMessage(),
                            TAGS);
        }
        send(body);
    }

    private void send(byte[] body) {
        var request =
                HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/x-sentry-envelope")
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenAccept(r -> failures.set(0))
                .exceptionally(
                        e -> {
                            if (failures.incrementAndGet() == MAX_FAILURES) {
                                addWarn(
                                        "Spotlight sidecar unreachable at "
                                                + endpoint
                                                + " — giving up. Start it with `pnpm spotlight`.");
                            }
                            return null;
                        });
    }
}
