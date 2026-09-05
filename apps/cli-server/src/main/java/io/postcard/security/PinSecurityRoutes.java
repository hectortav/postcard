package io.postcard.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.postcard.crypto.KeyMaterial;
import io.postcard.server.Server;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Javalin handlers for the PIN layer.
 *
 * <ul>
 *   <li>{@code POST /api/pin/verify} — body {@code {"pin":"1234"}}. On match
 *       (constant-time) the server stores the derived AES key on
 *       {@link Server#setDerivedKey(byte[])} and returns 200. On mismatch it
 *       records the failure and returns 401. If the IP is already locked it
 *       returns 429 without performing the comparison.</li>
 *   <li>{@code GET /api/pin/status} — returns the current rate-limit state
 *       for the requester's IP. Used by the UI to render a countdown.</li>
 *   <li>{@code GET /api/pin/config} — open to all: {@code {"pinRequired":bool,
 *       "manageable":bool}}. {@code manageable} is true only for the owner
 *       (see {@code Server.isOwnerIp}); receivers hide the PIN settings.</li>
 *   <li>{@code POST /api/pin/configure} — owner-only runtime PIN management.
 *       Body {@code {"pin":"1234"}} enables (or changes) protection;
 *       {@code {}} (or {@code {"pin":null}}) disables it. Enabling creates the
 *       KDF secret when the session has none and re-keys the session: every
 *       receiver must re-verify. Returns the current secret so the dashboard
 *       can refresh its URL fragment for QR sharing.</li>
 * </ul>
 *
 * <p>The wire-format shapes are:
 * <pre>
 *   200 → {}                           (verify success)
 *   401 → {"error":"invalid_pin"}       (verify mismatch)
 *   429 → {"error":"locked",
 *          "lockoutMsRemaining":&lt;n&gt;} (verify during lockout)
 *   200 → {"locked":bool,
 *          "remaining":int,
 *          "lockoutMsRemaining":long}   (status)
 *   200 → {"pinRequired":bool,
 *          "manageable":bool}           (config)
 *   200 → {"pinRequired":bool,
 *          "key":string|null}           (configure success; key is base64url)
 *   400 → {"error":"invalid_pin"}       (configure with a malformed PIN)
 *   400 → {"error":"invalid_json"}      (configure with an unparsable body)
 *   403 → {"error":"not_owner"}         (configure from a non-owner IP)
 * </pre>
 */
public final class PinSecurityRoutes {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BODY_LIMIT = 1024;

    private final PinRateLimiter limiter;
    private final Server server;

    public PinSecurityRoutes(PinRateLimiter limiter, Server server) {
        this.limiter = limiter;
        this.server = server;
    }

    /** Register both routes on the given Javalin app. */
    public static void register(Javalin app, PinRateLimiter limiter, Server server) {
        new PinSecurityRoutes(limiter, server).register(app);
    }

    private void register(Javalin app) {
        app.post("/api/pin/verify", ctx -> handleVerify(ctx));
        app.get("/api/pin/status", ctx -> handleStatus(ctx));
        app.get("/api/pin/config", ctx -> handleConfig(ctx));
        app.post("/api/pin/configure", ctx -> handleConfigure(ctx));
    }

    void handleVerify(io.javalin.http.Context ctx) throws Exception {
        // Always read IP up-front so the limiter sees the same address that
        // the body parser will use.
        String ip = ctx.ip();

        // Lockout check first — never even parse the body if the IP is locked.
        if (limiter.isLocked(ip)) {
            long ms = limiter.peekLockoutMsRemaining(ip);
            ctx.status(429).json(lockPayload(new PinRateLimiter.Result(false, 0, ms)));
            return;
        }

        Map<String, Object> body = parseBody(ctx);
        if (body == null) {
            ctx.status(400).json(Map.of("error", "invalid_json"));
            return;
        }
        Object pinRaw = body.get("pin");
        if (!(pinRaw instanceof String pin)) {
            ctx.status(400).json(Map.of("error", "missing_pin"));
            return;
        }

        // The server only stores an "expected" derived key when the user
        // passes --pin at startup. Without an expected key there is nothing
        // to verify against — treat the request as a no-op success so the
        // frontend can still see the file list (in un-PIN mode the routes
        // are also un-gated, see Server.java).
        byte[] expected = server.expectedDerivedKey();
        if (expected == null) {
            ctx.status(200).json(Map.of());
            return;
        }

        // The receiver derives the same key as the sender: KDF(secret, pin, salt).
        // We don't know the secret at this layer — it lives on the Server, gated
        // behind the encrypt flag. We compare against `expected` directly.
        boolean ok = PinSecurityEngine.verify(server.secretBytes(), pin, expected);
        if (!ok) {
            PinRateLimiter.Result r = limiter.recordFailure(ip);
            if (r.allowed()) {
                ctx.status(401).json(Map.of(
                    "error", "invalid_pin",
                    "remaining", r.remainingAttempts()
                ));
            } else {
                ctx.status(429).json(lockPayload(r));
            }
            return;
        }

        // Success: re-derive the key from (secret, pin, salt) and store it
        // on the Server so subsequent /api/files and /api/download calls
        // can use it (mixed with the PIN) instead of the raw random secret.
        // We deliberately do NOT replace keyMaterial here — the original
        // secret is what /api/pin/verify compares against, so swapping it
        // out would lock out any subsequent verify attempt (e.g. a second
        // device joining the same session).
        byte[] derived = PinSecurityEngine.deriveKey(server.secretBytes(), pin, PinSecurityEngine.saltFor(server.secretBytes())).getEncoded();
        server.setDerivedKey(derived);
        limiter.recordSuccess(ip);
        ctx.status(200).json(Map.of());
    }

    void handleStatus(io.javalin.http.Context ctx) {
        String ip = ctx.ip();
        boolean locked = limiter.isLocked(ip);
        if (locked) {
            // Compute remaining from the existing attempt state without mutating.
            long ms = limiter.peekLockoutMsRemaining(ip);
            ctx.status(200).json(Map.of(
                "locked", true,
                "remaining", 0,
                "lockoutMsRemaining", ms
            ));
            return;
        }
        int remainingAttempts = limiter.peekRemaining(ip);
        ctx.status(200).json(Map.of(
            "locked", false,
            "remaining", remainingAttempts,
            "lockoutMsRemaining", 0L
        ));
    }

    void handleConfig(io.javalin.http.Context ctx) {
        ctx.status(200).json(Map.of(
            "pinRequired", server.pinRequired(),
            "manageable", server.isOwnerIp(ctx.ip())
        ));
    }

    void handleConfigure(io.javalin.http.Context ctx) throws Exception {
        // Owner-only: receivers and LAN scanners must not reconfigure protection.
        if (!server.isOwnerIp(ctx.ip())) {
            ctx.status(403).json(Map.of("error", "not_owner"));
            return;
        }
        Map<String, Object> body = parseBody(ctx);
        if (body == null) {
            ctx.status(400).json(Map.of("error", "invalid_json"));
            return;
        }
        Object pinRaw = body.get("pin");
        if (pinRaw == null) {
            // Disable: the gate and the expected key go away; the KDF secret
            // stays (same as --encrypt semantics).
            server.disablePin();
            ctx.status(200).json(configPayload());
            return;
        }
        if (!(pinRaw instanceof String pin)) {
            ctx.status(400).json(Map.of("error", "invalid_pin"));
            return;
        }
        // Enable (or change): creates the secret when the session has none,
        // re-derives the expected key and drops verified keys so every
        // receiver re-verifies under the new PIN.
        try {
            server.enablePin(pin);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", "invalid_pin"));
            return;
        }
        ctx.status(200).json(configPayload());
    }

    private Map<String, Object> configPayload() {
        // LinkedHashMap (not Map.of): the key is null when the session never
        // held a secret, and Map.of rejects null values.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pinRequired", server.pinRequired());
        m.put("key", server.keyB64Url());
        return m;
    }

    private static Map<String, Object> lockPayload(PinRateLimiter.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", "locked");
        m.put("lockoutMsRemaining", r.lockoutMsRemaining());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseBody(io.javalin.http.Context ctx) {
        try {
            // Javalin 6: ctx.body() returns the raw string. For a POST route
            // the body is always present (possibly empty).
            String body = ctx.body();
            if (body.isEmpty()) return Map.of();
            if (body.length() > BODY_LIMIT) return null;
            return JSON.readValue(body, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
