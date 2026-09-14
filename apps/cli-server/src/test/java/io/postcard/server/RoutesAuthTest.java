package io.postcard.server;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The data routes, over real HTTP and a real WebSocket: what they serve, and who they serve it
 * to. Covers the download path for operator-supplied files, the same-origin filter, and the
 * per-client PIN gate on both the REST routes and the socket.
 */
class RoutesAuthTest {

    private Server server;
    private Javalin app;
    private String base;
    private Path shared;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .cookieHandler(new java.net.CookieManager())
        .build();

    private void start(java.util.function.Consumer<PostcardOptions> configure) throws Exception {
        shared = Files.createTempDirectory("postcard-routes-");
        var opts = new PostcardOptions();
        opts.path = shared;
        configure.accept(opts);
        server = new Server(opts);
        server.init();
        app = server.build();
        app.start("127.0.0.1", 0);
        server.setBindHost("127.0.0.1");
        server.setBindPort(app.port());
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach void tearDown() throws Exception {
        if (app != null) app.stop();
        if (shared != null && Files.exists(shared)) {
            try (var walk = Files.walk(shared)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception _) {} });
            }
        }
    }

    private HttpResponse<byte[]> get(String path, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String idOfOnlyFile() throws Exception {
        var body = new String(get("/api/files").body(), java.nio.charset.StandardCharsets.UTF_8);
        var m = java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"").matcher(body);
        assertTrue(m.find(), "no file in " + body);
        return m.group(1);
    }

    @Test void anOperatorSuppliedFileWithAnExtensionCanBeDownloaded() throws Exception {
        // The bug this pins: in --path mode the id was the on-disk filename, and the download
        // route rejects anything outside ^[A-Za-z0-9_-]{8,64}$ -- so every pre-existing file
        // with a dot in its name answered 400 and was impossible to fetch.
        start(o -> {});
        Files.writeString(shared.resolve("report.pdf"), "the actual bytes");
        var r = get("/api/download/" + idOfOnlyFile());
        assertEquals(200, r.statusCode());
        assertEquals("the actual bytes", new String(r.body(), java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(r.headers().firstValue("Content-Disposition").orElse("").contains("report.pdf"));
    }

    @Test void uploadedHtmlIsNeverServedAsHtml() throws Exception {
        // Same origin as the dashboard: serving this as text/html would make an uploaded file
        // a script running against the dashboard's own origin.
        start(o -> {});
        Files.writeString(shared.resolve("page.html"), "<script>alert(1)</script>");
        var r = get("/api/download/" + idOfOnlyFile());
        assertEquals(200, r.statusCode());
        assertEquals("application/octet-stream", r.headers().firstValue("Content-Type").orElse(""));
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""));
    }

    @Test void securityHeadersArePresent() throws Exception {
        start(o -> {});
        var r = get("/api/files");
        assertEquals("DENY", r.headers().firstValue("X-Frame-Options").orElse(""));
        assertEquals("no-referrer", r.headers().firstValue("Referrer-Policy").orElse(""));
        assertTrue(r.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"));
    }

    @Test void aRangeRequestReturnsExactlyThatRange() throws Exception {
        start(o -> {});
        var body = new byte[300_000];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i % 251);
        Files.write(shared.resolve("big.bin"), body);
        var r = get("/api/download/" + idOfOnlyFile(), "Range", "bytes=1000-201000");
        assertEquals(206, r.statusCode());
        assertEquals("bytes 1000-201000/300000", r.headers().firstValue("Content-Range").orElse(""));
        var expected = java.util.Arrays.copyOfRange(body, 1000, 201001);
        assertArrayEquals(expected, r.body(), "the streamed range must match the file's bytes");
    }

    @Test void theHostDownloadsPlaintextButAReceiverWouldNot() throws Exception {
        start(o -> o.encrypt = true);
        Files.writeString(shared.resolve("secret.txt"), "super-secret-payload");
        // Requests arrive from the bind address, so this client is the host.
        assertTrue(server.isOwnerIp("127.0.0.1"));
        assertFalse(server.shouldEncryptFor("127.0.0.1"), "the host already has the plaintext");
        assertTrue(server.shouldEncryptFor("192.168.1.50"), "a receiver on the LAN gets ciphertext");
        var r = get("/api/download/" + idOfOnlyFile());
        assertEquals(200, r.statusCode());
        assertEquals("super-secret-payload", new String(r.body(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void encryptOwnerForcesCiphertextForEveryone() throws Exception {
        start(o -> { o.encrypt = true; o.encryptOwner = true; });
        Files.writeString(shared.resolve("secret.txt"), "super-secret-payload");
        assertTrue(server.shouldEncryptFor("127.0.0.1"));
        var r = get("/api/download/" + idOfOnlyFile());
        assertEquals(200, r.statusCode());
        assertFalse(new String(r.body(), java.nio.charset.StandardCharsets.UTF_8).contains("super-secret-payload"));
    }

    @Test void aCrossOriginPostIsRefused() throws Exception {
        start(o -> {});
        var r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/pin/configure"))
            .header("Content-Type", "text/plain")
            .header("Origin", "http://attacker.example")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(403, r.statusCode());
        assertTrue(r.body().contains("bad_origin"));
    }

    /** Raw request line + headers, because HttpClient refuses to let callers set Host. */
    private String rawGet(String path, String hostHeader) throws Exception {
        try (var socket = new java.net.Socket("127.0.0.1", app.port())) {
            socket.setSoTimeout(3000);
            var out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + hostHeader
                + "\r\nConnection: close\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();
            return new String(socket.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test void aRebindingHostHeaderIsRefused() throws Exception {
        start(o -> {});
        Files.writeString(shared.resolve("report.pdf"), "x");
        // A GET carries no Origin, so the Host header is the only signal that the request
        // arrived through an attacker-controlled name pointed at this address. Without this,
        // such a page could read the file list and download.
        var refused = rawGet("/api/files", "attacker.example:" + app.port());
        assertTrue(refused.startsWith("HTTP/1.1 403"), refused.lines().findFirst().orElse(""));
        assertTrue(refused.contains("bad_host"));
        // The address postcard actually printed still works.
        var allowed = rawGet("/api/files", "127.0.0.1:" + app.port());
        assertTrue(allowed.startsWith("HTTP/1.1 200"), allowed.lines().findFirst().orElse(""));
        assertTrue(allowed.contains("report.pdf"));
    }

    @Test void mdnsAndLoopbackNamesStillWork() throws Exception {
        start(o -> {});
        assertTrue(server.hostAllowed("macbook.local:" + app.port()));
        assertTrue(server.hostAllowed("localhost:" + app.port()));
        assertTrue(server.hostAllowed("127.0.0.1:" + app.port()));
        assertFalse(server.hostAllowed("evil.example:" + app.port()));
        assertFalse(server.hostAllowed("macbook.local:1"));
    }

    // ---- WebSocket ----

    /** Connects and returns the first text frame, or null if the socket closed instead. */
    private String firstFrameOrClose(String cookie) throws Exception {
        var frame = new AtomicReference<String>();
        var done = new CountDownLatch(1);
        var listener = new WebSocket.Listener() {
            @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                frame.compareAndSet(null, data.toString());
                done.countDown();
                return null;
            }
            @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                done.countDown();
                return null;
            }
            @Override public void onError(WebSocket ws, Throwable error) { done.countDown(); }
        };
        var builder = http.newWebSocketBuilder();
        if (cookie != null) builder.header("Cookie", Server.SESSION_COOKIE + "=" + cookie);
        try {
            builder.buildAsync(URI.create(base.replace("http://", "ws://") + "/ws"), listener)
                .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null; // upgrade refused outright
        }
        assertTrue(done.await(5, TimeUnit.SECONDS), "socket neither sent nor closed");
        return frame.get();
    }

    @Test void theSocketSendsTheSnapshotWhenNoPinIsSet() throws Exception {
        start(o -> {});
        Files.writeString(shared.resolve("report.pdf"), "x");
        var frame = firstFrameOrClose(null);
        assertNotNull(frame);
        assertTrue(frame.contains("\"type\":\"snapshot\""), frame);
        assertTrue(frame.contains("report.pdf"), frame);
    }

    @Test void theSocketRefusesToSendTheSnapshotWithoutAVerifiedSession() throws Exception {
        // The hole this pins: /api/files was gated on the PIN and /ws was not, and the
        // snapshot carries every filename, size and hash plus the shared clipboard text.
        // Anyone on the LAN could read all of it by opening a socket.
        start(o -> o.pin = "1234");
        server.enablePin("1234");
        Files.writeString(shared.resolve("report.pdf"), "x");
        var frame = firstFrameOrClose(null);
        assertNull(frame, "no snapshot may be sent before the PIN is verified");
    }

    @Test void theSocketSendsTheSnapshotOnceThePinIsVerified() throws Exception {
        start(o -> o.pin = "1234");
        server.enablePin("1234");
        Files.writeString(shared.resolve("report.pdf"), "x");
        var verify = http.send(HttpRequest.newBuilder(URI.create(base + "/api/pin/verify"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"pin\":\"1234\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, verify.statusCode());
        var cookie = verify.headers().firstValue("Set-Cookie").orElse("");
        var token = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        var frame = firstFrameOrClose(token);
        assertNotNull(frame);
        assertTrue(frame.contains("\"type\":\"snapshot\""), frame);
    }
}
