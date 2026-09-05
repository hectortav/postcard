package io.postcard.security;

import io.javalin.Javalin;
import io.postcard.server.PostcardOptions;
import io.postcard.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for runtime PIN management: {@code GET /api/pin/config}
 * and {@code POST /api/pin/configure}. Each test spins the real Server on an
 * ephemeral port; requests from loopback count as the owner only when the
 * server bound loopback (see {@code Server.isOwnerIp}).
 */
class PinConfigureRoutesTest {

    private Server server;
    private Javalin app;
    private String base;
    private final HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private void start(boolean encrypt, boolean pinArmed, String bindHost) throws Exception {
        var opts = new PostcardOptions();
        opts.encrypt = encrypt;
        server = new Server(opts);
        server.init();
        if (pinArmed) server.enablePin("1234");
        if (bindHost != null) server.setBindHost(bindHost);
        app = server.build();
        app.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + app.port();
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
    void configReportsGatesAndManageability() throws Exception {
        start(false, false, null);
        var r = get("/api/pin/config");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"pinRequired\":false"));
        assertTrue(r.body().contains("\"manageable\":false"),
            "bind host unset: loopback must not manage");
    }

    @Test
    void configReportsManageableForTheBindAddress() throws Exception {
        start(false, true, "127.0.0.1");
        var r = get("/api/pin/config");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"pinRequired\":true"));
        assertTrue(r.body().contains("\"manageable\":true"));
    }

    @Test
    void configureRejectedWithoutABindHost() throws Exception {
        start(false, false, null);
        var r = postJson("/api/pin/configure", "{\"pin\":\"5678\"}");
        assertEquals(403, r.statusCode());
        assertTrue(r.body().contains("not_owner"));
        assertFalse(server.pinRequired());
    }

    @Test
    void configureRejectedForAForeignBindHost() throws Exception {
        start(false, false, "192.168.99.99");
        var r = postJson("/api/pin/configure", "{}");
        assertEquals(403, r.statusCode());
        assertTrue(r.body().contains("not_owner"));
    }

    @Test
    void configureRejectsGarbageBodies() throws Exception {
        start(false, false, "127.0.0.1");
        var unparsable = postJson("/api/pin/configure", "{not json");
        assertEquals(400, unparsable.statusCode());
        assertTrue(unparsable.body().contains("invalid_json"));
        var nonString = postJson("/api/pin/configure", "{\"pin\":1234}");
        assertEquals(400, nonString.statusCode());
        assertTrue(nonString.body().contains("invalid_pin"));
        for (String bad : new String[]{"12", "12345", "12ab", ""}) {
            var r = postJson("/api/pin/configure", "{\"pin\":\"" + bad + "\"}");
            assertEquals(400, r.statusCode(), "pin " + bad + " must be rejected");
            assertTrue(r.body().contains("invalid_pin"));
        }
        assertFalse(server.pinRequired(), "rejected PINs must not arm the gate");
    }

    @Test
    void enableFromPlainModeCreatesSecretAndArmsTheGate() throws Exception {
        start(false, false, "127.0.0.1");
        assertNull(server.secretBytes());
        var r = postJson("/api/pin/configure", "{\"pin\":\"5678\"}");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"pinRequired\":true"));
        assertTrue(r.body().contains("\"key\":\""), "dashboard needs the secret for its URL");
        assertNotNull(server.secretBytes());
        assertTrue(server.pinRequired());
        // Gate armed: files refuse until the new PIN verifies…
        assertEquals(401, get("/api/files").statusCode());
        // …and the new PIN (not the old shape) verifies.
        assertEquals(200, postJson("/api/pin/verify", "{\"pin\":\"5678\"}").statusCode());
        assertEquals(200, get("/api/files").statusCode());
    }

    @Test
    void changingThePinRekeysTheSession() throws Exception {
        start(true, true, "127.0.0.1");
        assertEquals(200, postJson("/api/pin/verify", "{\"pin\":\"1234\"}").statusCode());
        assertNotNull(server.derivedKey());
        var r = postJson("/api/pin/configure", "{\"pin\":\"5678\"}");
        assertEquals(200, r.statusCode());
        assertNull(server.derivedKey(), "verified keys must not survive a re-key");
        assertEquals(401, get("/api/files").statusCode());
        assertEquals(401, postJson("/api/pin/verify", "{\"pin\":\"1234\"}").statusCode());
        assertEquals(200, postJson("/api/pin/verify", "{\"pin\":\"5678\"}").statusCode());
    }

    @Test
    void disableOpensTheGatesButKeepsTheSecret() throws Exception {
        start(true, true, "127.0.0.1");
        var r = postJson("/api/pin/configure", "{}");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"pinRequired\":false"));
        assertTrue(r.body().contains("\"key\":\""), "secret stays for encryption continuity");
        assertFalse(server.pinRequired());
        assertEquals(200, get("/api/files").statusCode());
    }

    @Test
    void disableOnASecretlessSessionReturnsNullKey() throws Exception {
        start(false, false, "127.0.0.1");
        var r = postJson("/api/pin/configure", "{}");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"key\":null"));
    }
}
