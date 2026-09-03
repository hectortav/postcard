package io.sendme.security;

import io.javalin.Javalin;
import io.sendme.crypto.KeyMaterial;
import io.sendme.server.SendmeOptions;
import io.sendme.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for the /api/pin/* routes. Spins up the real Server
 * on an ephemeral port and exercises the routes via HttpClient.
 */
class PinSecurityRoutesTest {

    private Server server;
    private Javalin app;
    private String base;
    private HttpClient http;
    private byte[] secret;

    @BeforeEach
    void setUp() throws Exception {
        var opts = new SendmeOptions();
        // Force --encrypt so the random secret exists.
        opts.encrypt = true;
        server = new Server(opts);
        server.init();
        // Configure a known pin-required state.
        secret = server.secretBytes();
        // Sender side: derive the expected key and arm the gates.
        String salt = PinSecurityEngine.saltFor(secret);
        byte[] expected = PinSecurityEngine.deriveKey(secret, "1234", salt).getEncoded();
        server.setExpectedDerivedKey(expected);
        server.setPinRequired(true);
        app = server.build();
        app.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + app.port();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void statusBeforeAnyFail() throws Exception {
        var r = get("/api/pin/status");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"locked\":false"));
        assertTrue(r.body().contains("\"remaining\":3"));
    }

    @Test
    void filesGatedByPinBeforeVerify() throws Exception {
        var r = get("/api/files");
        assertEquals(401, r.statusCode());
        assertTrue(r.body().contains("pin_required"));
    }

    @Test
    void downloadGatedByPinBeforeVerify() throws Exception {
        var r = get("/api/download/abc12345");
        assertEquals(401, r.statusCode());
        assertTrue(r.body().contains("pin_required"));
    }

    @Test
    void verifySuccessUnlocksFiles() throws Exception {
        var r = postJson("/api/pin/verify", "{\"pin\":\"1234\"}");
        assertEquals(200, r.statusCode());
        // After verify, /api/files is reachable (returns 200, body is the file list JSON)
        var files = get("/api/files");
        assertEquals(200, files.statusCode());
        // And the derived key is in place on the server.
        assertNotNull(server.derivedKey());
        assertEquals(32, server.derivedKey().length);
    }

    @Test
    void verifyWrongPinReturns401WithRemaining() throws Exception {
        var r = postJson("/api/pin/verify", "{\"pin\":\"9999\"}");
        assertEquals(401, r.statusCode());
        assertTrue(r.body().contains("invalid_pin"));
        assertTrue(r.body().contains("\"remaining\":2"));
    }

    @Test
    void verifyMissingPinReturns400() throws Exception {
        var r = postJson("/api/pin/verify", "{}");
        assertEquals(400, r.statusCode());
    }

    @Test
    void verifyBadJsonReturns400() throws Exception {
        var r = postJson("/api/pin/verify", "not json at all");
        assertEquals(400, r.statusCode());
    }

    @Test
    void verifyOversizedBodyReturns400() throws Exception {
        String huge = "{\"pin\":\"" + "x".repeat(2048) + "\"}";
        var r = postJson("/api/pin/verify", huge);
        assertEquals(400, r.statusCode());
    }

    @Test
    void verifyLocksAfterThreeFailures() throws Exception {
        for (int i = 0; i < 3; i++) {
            postJson("/api/pin/verify", "{\"pin\":\"9999\"}");
        }
        var r = postJson("/api/pin/verify", "{\"pin\":\"9999\"}");
        assertEquals(429, r.statusCode());
        assertTrue(r.body().contains("locked"));
        // Status now reports locked.
        var status = get("/api/pin/status");
        assertEquals(200, status.statusCode());
        assertTrue(status.body().contains("\"locked\":true"));
        assertTrue(status.body().contains("\"remaining\":0"));
    }

    @Test
    void noOpVerifyWhenExpectedKeyNull() throws Exception {
        // If --pin was not supplied, expectedDerivedKey is null.
        var opts = new SendmeOptions();
        opts.encrypt = true;
        var s = new Server(opts);
        s.init();
        var otherApp = s.build();
        otherApp.start("127.0.0.1", 0);
        try {
            var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + otherApp.port() + "/api/pin/verify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"pin\":\"1234\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
        } finally {
            otherApp.stop();
        }
    }

    @Test
    void postVerifyWithNoPinField() throws Exception {
        // The "pin" key is missing entirely.
        var r = postJson("/api/pin/verify", "{\"foo\":\"bar\"}");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("missing_pin"));
    }

    @Test
    void statusWhenUnlockedReportsAllThree() throws Exception {
        // Successful verify first.
        postJson("/api/pin/verify", "{\"pin\":\"1234\"}");
        // The IP is cleared — status should be unlocked.
        var r = get("/api/pin/status");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"locked\":false"));
        assertTrue(r.body().contains("\"remaining\":3"));
    }

    @Test
    void verifyWithEmptyBody() throws Exception {
        // POST with no body — parseBody returns Map.of() (no pin key),
        // handleVerify then 400s with missing_pin.
        var r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/pin/verify"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, r.statusCode());
    }
}
