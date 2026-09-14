package io.postcard.server;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.postcard.crypto.ChunkCipher;
import io.postcard.crypto.KeyMaterial;
import io.postcard.http.RangeParser;
import io.postcard.util.Shutdown;
import io.postcard.ws.WebSocketHub;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Executors;

public final class Server {
    public static final java.util.concurrent.ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();
    private final PostcardOptions opts;
    private final WebSocketHub hub = new WebSocketHub();
    private final java.util.concurrent.ConcurrentHashMap<Object, io.postcard.ws.PostcardSession> sessionByJavalinSession = new java.util.concurrent.ConcurrentHashMap<>();
    private final io.postcard.security.PinRateLimiter pinLimiter = new io.postcard.security.PinRateLimiter();
    private FileStore store;
    private KeyMaterial keyMaterial;
    private String mode = "lan";
    private boolean tempDir;
    private String hotspotSsid;
    private String hotspotPassword;
    // Per-client sessions. A receiver that proves knowledge of the PIN gets its own
    // token; the gate below checks the caller's token rather than a single global flag,
    // so one device unlocking no longer unlocks the whole LAN.
    private final io.postcard.security.SessionTokens sessions = new io.postcard.security.SessionTokens();
    // Pre-derived key the sender produced at startup (when --pin is set).
    // Used by the /api/pin/verify route to compare against the receiver's
    // attempt without ever storing the PIN server-side.
    private volatile byte[] expectedDerivedKey;
    // True when the CLI was launched with --pin (or the dashboard armed one at runtime).
    // Gates every data route on a verified per-client session; see the filter in build().
    private volatile boolean pinRequired;
    // LAN address bound at startup; null until Main sets it. See setBindHost.
    private volatile String bindHost;
    // Port bound at startup; 0 until Main sets it. Used with bindHost to build the one
    // Origin the browser is allowed to send.
    private volatile int bindPort;

    public Server(PostcardOptions opts) { this.opts = opts; }

    public void init() throws Exception {
        if (opts.path != null) { store = new FileStore(opts.path); tempDir = false; }
        else { store = new FileStore(java.nio.file.Files.createTempDirectory("postcard-")); tempDir = true; }
        store.setAddListener(e -> hub.broadcast("{\"type\":\"file_added\",\"id\":\"" + e.id() + "\",\"name\":\"" + json(e.name()) + "\",\"size\":" + e.size() + ",\"mtime\":" + e.mtime() + ",\"sha256\":\"" + e.sha256() + "\"}"));
        store.setRemoveListener(id -> hub.broadcast("{\"type\":\"file_removed\",\"id\":\"" + id + "\"}"));
        if (opts.encrypt) { var k = new byte[32]; new SecureRandom().nextBytes(k); keyMaterial = new KeyMaterial(k); }
        // --pin implies we still need a random secret to feed the KDF (the
        // receiver mixes the PIN with this secret in their browser). Without
        // this, /api/pin/verify would have nothing to derive against.
        if (opts.pin != null && keyMaterial == null) { var k = new byte[32]; new SecureRandom().nextBytes(k); keyMaterial = new KeyMaterial(k); }
    }

    public String keyB64Url() { return keyMaterial == null ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(keyMaterial.key()); }
    public String mode() { return mode; }
    public void setMode(String m) { this.mode = m; }
    public void setHotspot(String ssid, String password) { this.hotspotSsid = ssid; this.hotspotPassword = password; this.mode = "hotspot"; }
    public String hotspotSsid() { return hotspotSsid; }
    public String hotspotPassword() { return hotspotPassword; }
    public boolean tempDir() { return tempDir; }
    public WebSocketHub hub() { return hub; }

    // Desktop notifications for peer activity. Injected by Main once the tray icon
    // exists; a no-op sink until then, and forever in headless mode.
    private volatile java.util.function.Consumer<io.postcard.desktop.Notifier.Event> notify =
        io.postcard.desktop.Notifier.sink(_ -> {});
    private volatile String hostIp = "";

    public void setNotifier(java.util.function.Consumer<io.postcard.desktop.Notifier.Event> n, String hostIp) {
        this.notify = n;
        this.hostIp = hostIp == null ? "" : hostIp;
    }

    /** Announce peer activity; requests from the host itself are ignored. */
    private void announce(String ip, java.util.function.Supplier<io.postcard.desktop.Notifier.Event> event) {
        if (io.postcard.desktop.Notifier.isPeer(ip, hostIp)) notify.accept(event.get());
    }
    public FileStore store() { return store; }
    public KeyMaterial keyMaterial() { return keyMaterial; }
    public PostcardOptions opts() { return opts; }

    /** Raw bytes of the random 256-bit secret used as the KDF input. May be null when encryption is off. */
    public byte[] secretBytes() { return keyMaterial == null ? null : keyMaterial.key(); }

    /** Sender-pre-derived key (set when --pin is supplied at startup). */
    public byte[] expectedDerivedKey() { return expectedDerivedKey; }
    public void setExpectedDerivedKey(byte[] k) { this.expectedDerivedKey = k; }

    /** True when the CLI was launched with --pin. */
    public boolean pinRequired() { return pinRequired; }
    public void setPinRequired(boolean v) { this.pinRequired = v; }

    /** Per-client PIN sessions; see {@link io.postcard.security.SessionTokens}. */
    public io.postcard.security.SessionTokens sessions() { return sessions; }

    /** Name of the cookie carrying a verified session. */
    public static final String SESSION_COOKIE = "postcard_session";

    /**
     * The key downloads are encrypted under. Deterministic: when a PIN is armed it is always
     * the key derived from (secret, PIN), which the receiver's browser derives independently.
     * This used to depend on whether some other client had verified first.
     */
    public KeyMaterial effectiveKey() {
        if (keyMaterial == null) return null;
        byte[] derived = expectedDerivedKey;
        return (pinRequired && derived != null) ? new KeyMaterial(derived) : keyMaterial;
    }

    /**
     * Whether this caller's download should be encrypted.
     *
     * <p>The host already holds the plaintext on disk and its own requests never leave the
     * machine, so encrypting for the owner buys nothing while forcing the dashboard through a
     * decryption path with a hard memory ceiling. {@code --encrypt-owner} opts out of the
     * bypass for an operator who wants it uniform (and is what lets CI exercise the receiver
     * path, where Playwright's source address is the bind address).
     */
    public boolean shouldEncryptFor(String ip) {
        if (keyMaterial == null) return false;
        return opts.encryptOwner || !isOwnerIp(ip);
    }

    /** Atomically swap the AES key the download route uses. */
    public void replaceKeyMaterial(KeyMaterial km) { this.keyMaterial = km; }

    /**
     * LAN address this server bound (set by Main after interface selection).
     * PIN management is owner-only: a request counts as the owner exactly when
     * its source IP equals this address. Anything else — receivers on phones,
     * LAN scanners — gets 403 from the configure route. Fail-closed by design:
     * unset means nobody manages.
     */
    public void setBindHost(String bindHost) { this.bindHost = bindHost; }

    /** Bound port, recorded after {@code app.start()} so the Origin check knows the full origin. */
    public void setBindPort(int bindPort) { this.bindPort = bindPort; }

    /** The single Origin a browser is allowed to present, or null before the bind is known. */
    public String allowedOrigin() {
        return (bindHost == null || bindPort == 0) ? null : "http://" + bindHost + ":" + bindPort;
    }

    /**
     * Whether a request may perform a state-changing action or open a WebSocket.
     *
     * <p>postcard has no CORS configuration, which meant any website the host visited could
     * POST to {@code /api/pin/configure} and turn the PIN off, or push files into the shared
     * directory: both are CORS-simple requests that need no preflight and arrive with the
     * host's own source address. An absent Origin is allowed so curl and other non-browser
     * clients keep working; a browser always sends one on these requests.
     */
    public boolean originAllowed(String origin) {
        String expected = allowedOrigin();
        if (expected == null) return true; // pre-bind (tests construct routes without a bind)
        return origin == null || origin.isEmpty() || origin.equals(expected);
    }

    /**
     * Whether the {@code Host} header names this server.
     *
     * <p>Guards against DNS rebinding: an attacker's domain that resolves to postcard's LAN
     * address would otherwise let their page read {@code /api/files} and download, because a
     * plain GET carries no {@code Origin} for the check above to reject.
     *
     * <p>Deliberately not strict equality with the bind address. postcard is a LAN tool and
     * people legitimately reach it by names it never printed -- {@code macbook.local},
     * {@code localhost}, a loopback literal during development. Refusing those with a bare 403
     * would cost more than the narrowed attack surface is worth, so the rule is: the port must
     * match, and the host must be the bind address, a loopback literal, or an mDNS
     * {@code .local} name.
     */
    public boolean hostAllowed(String host) {
        if (bindPort == 0) return true; // pre-bind
        if (host == null || host.isEmpty()) return true; // HTTP/1.0 client
        String name = host;
        int colon = host.lastIndexOf(':');
        // Ignore the colon inside a bracketed IPv6 literal ("[::1]").
        if (colon > host.lastIndexOf(']')) {
            String portPart = host.substring(colon + 1);
            if (!portPart.equals(String.valueOf(bindPort))) return false;
            name = host.substring(0, colon);
        }
        name = name.replace("[", "").replace("]", "");
        if (name.equalsIgnoreCase(bindHost)) return true;
        if (name.equalsIgnoreCase("localhost") || name.equals("::1") || name.startsWith("127.")) return true;
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".local");
    }

    /** Owner check for PIN management; see {@link #setBindHost(String)}. */
    public boolean isOwnerIp(String ip) {
        return bindHost != null && bindHost.equals(ip);
    }

    /**
     * Arm (or re-arm) PIN protection with the given PIN, at startup or at runtime
     * from the dashboard. Creates the random KDF secret when the session has none
     * yet (plain mode), derives the expected key, arms the gate and drops any
     * previously verified key so every receiver re-verifies under the new PIN.
     *
     * @param pin exactly 4 ASCII digits
     * @throws IllegalArgumentException when the PIN has the wrong shape
     */
    public synchronized void enablePin(String pin) {
        if (pin == null || !pin.matches("^[0-9]{4}$")) {
            throw new IllegalArgumentException("pin must be exactly 4 digits");
        }
        // --pin implies a secret even in plain mode (mirrors init()): the receiver
        // mixes the PIN with it in their browser, so verify needs something to
        // derive against.
        if (keyMaterial == null) {
            var k = new byte[32];
            new SecureRandom().nextBytes(k);
            keyMaterial = new KeyMaterial(k);
        }
        try {
            String salt = io.postcard.security.PinSecurityEngine.saltFor(secretBytes());
            expectedDerivedKey = io.postcard.security.PinSecurityEngine
                .deriveKey(secretBytes(), pin, salt).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("pin key derivation failed", e);
        }
        sessions.revokeAll();
        pinRequired = true;
    }

    /**
     * Drop PIN protection at runtime. The KDF secret is deliberately kept (same as
     * --encrypt semantics): downloads stay encrypted under it and the URL keeps
     * its #key= fragment; only the PIN gate and the expected key go away.
     */
    public synchronized void disablePin() {
        pinRequired = false;
        expectedDerivedKey = null;
        sessions.revokeAll();
    }

    /**
     * Where Jetty spools multipart bodies, and where the upload handler writes its own
     * temporary copy. Inside the shared directory so the existing teardown reclaims it.
     */
    public java.nio.file.Path uploadSpoolDir() {
        if (store == null) return java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        var spool = store.dir().resolve(".postcard-tmp");
        try { java.nio.file.Files.createDirectories(spool); } catch (Exception e) {
            return store.dir();
        }
        return spool;
    }

    /**
     * Delete anything left in the spool directory by a previous run that did not exit cleanly.
     * Called at startup, when nothing can be mid-upload.
     */
    public void sweepUploadSpool() {
        if (store == null) return;
        var spool = store.dir().resolve(".postcard-tmp");
        if (!java.nio.file.Files.isDirectory(spool)) return;
        try (var files = java.nio.file.Files.list(spool)) {
            files.forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception _) {} });
        } catch (Exception _) { }
    }

    /** Per-IP PIN rate limiter. */
    public io.postcard.security.PinRateLimiter pinLimiter() { return pinLimiter; }

    public Javalin build() {
        var app = Javalin.create(cfg -> {
            cfg.staticFiles.add(s -> {
                s.hostedPath = "/";
                s.directory = "/public";
                s.location = Location.CLASSPATH;
            });
            // Upload limits, enforced by Jetty before a byte reaches the handler.
            //
            // The README called --max-upload "pre-disk enforcement" and it was not: Javalin's
            // defaults are unlimited size with a 1-byte in-memory threshold, so the servlet
            // container spooled the entire body to java.io.tmpdir and only then ran the
            // handler's own check. The Content-Length pre-check was the only real guard and it
            // is trivially skipped with Transfer-Encoding: chunked.
            //
            // The spool directory also moves under the shared directory, so a crash or a kill
            // -9 cannot strand multi-gigabyte temp files in the system temp directory: the
            // shutdown path already deletes this tree.
            cfg.jetty.multipartConfig.cacheDirectory(uploadSpoolDir().toString());
            if (opts.maxUploadMiB != null) {
                cfg.jetty.multipartConfig.maxFileSize(opts.maxUploadMiB, io.javalin.config.SizeUnit.MB);
                cfg.jetty.multipartConfig.maxTotalRequestSize(opts.maxUploadMiB, io.javalin.config.SizeUnit.MB);
            }
            // WebSocket limits — see design §6.1 / Global Constraints.
            // The brief's `app.jetty.modifyJetty(b -> b.setHandler(new HandlerList(...)))` is broken
            // Kotlin-style code; in Javalin 6.3.0 (Jetty 11.0.23 transitive) the policy settings
            // live on the JettyWebSocketServletFactory, which Javalin exposes via
            // `cfg.jetty.modifyWebSocketServletFactory(...)`. The factory is itself a
            // WebSocketPolicy, so the setters chain directly.
            cfg.jetty.modifyWebSocketServletFactory(f -> {
                f.setMaxTextMessageSize(65536L);    // 64 KiB text frames (spec §6.1)
                f.setMaxBinaryMessageSize(0L);      // 0 → reject binary; we only send text
                f.setIdleTimeout(java.time.Duration.ofSeconds(60));
            });
        });
        // Global response headers
        app.before(ctx -> {
            ctx.header("X-Postcard-Mode", mode);
            ctx.header("Cache-Control", "no-store");
            // The dashboard renders filenames and shared clipboard text that any device on the
            // LAN can set. Preact escapes both, so these are defence in depth rather than a fix
            // for a live hole -- but an uploaded .html served from this same origin would
            // otherwise be one navigation away from scripting the dashboard.
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("X-Frame-Options", "DENY");
            ctx.header("Referrer-Policy", "no-referrer");
            ctx.header("Content-Security-Policy",
                "default-src 'self'; connect-src 'self' ws: wss:; img-src 'self' data:; "
                + "style-src 'self' 'unsafe-inline'; script-src 'self'; object-src 'none'; "
                + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        });
        app.after(ctx -> { if (ctx.path().startsWith("/api/")) ctx.header("Cache-Control", "no-store"); });
        // In-flight tracking for graceful shutdown
        app.before(ctx -> Shutdown.enter());
        app.after(ctx -> Shutdown.leave());

        // Same-origin enforcement for anything that changes state. See originAllowed.
        app.before(ctx -> {
            String method = ctx.method().name();
            boolean stateChanging = method.equals("POST") || method.equals("PUT")
                || method.equals("DELETE") || method.equals("PATCH");
            if (!stateChanging) return;
            if (!originAllowed(ctx.header("Origin"))) {
                ctx.status(403).json(java.util.Map.of("error", "bad_origin"));
                ctx.skipRemainingHandlers();
            }
        });

        // Host check on every request, including GETs, which carry no Origin.
        app.before(ctx -> {
            if (!hostAllowed(ctx.header("Host"))) {
                ctx.status(403).json(java.util.Map.of("error", "bad_host"));
                ctx.skipRemainingHandlers();
            }
        });

        // PIN gate. Every data route requires this caller's own verified session; the
        // unlock surface (/api/pin/verify, status, config) and the static dashboard stay open,
        // or a receiver could never reach the screen that asks for the PIN.
        app.before(ctx -> {
            if (!pinRequired) return;
            String path = ctx.path();
            boolean gated = path.equals("/api/files") || path.equals("/api/upload")
                || path.equals("/api/clipboard") || path.startsWith("/api/download/");
            if (!gated) return;
            if (!sessions.isValid(ctx.cookie(SESSION_COOKIE))) {
                ctx.status(401).json(java.util.Map.of("error", "pin_required"));
                ctx.skipRemainingHandlers();
            }
        });

        // What the dashboard needs to know about this session, decided here rather than
        // sniffed in the browser: the native window and a phone run the same bundle and must
        // never diverge on their own (see AGENTS.md).
        app.get("/api/session", ctx -> ctx.json(java.util.Map.of(
            "pinRequired", pinRequired,
            "manageable", isOwnerIp(ctx.ip()),
            "encrypted", shouldEncryptFor(ctx.ip()),
            "pinLength", 4)));

        app.get("/api/files", ctx -> ctx.json(store.list()));
        app.post("/api/upload", ctx -> {
            long limit = opts.maxUploadMiB == null ? Long.MAX_VALUE : opts.maxUploadMiB * 1024L * 1024L;
            long contentLength = ctx.req().getContentLengthLong();
            if (contentLength > limit) { ctx.status(413).json(java.util.Map.of("error", "upload_too_large", "limitBytes", limit)); return; }
            var f = ctx.uploadedFile("file");
            if (f == null) { ctx.status(400).json(java.util.Map.of("error", "missing_file")); return; }
            if (f.filename() != null && f.filename().codePoints().anyMatch(cp -> cp < 0x20)) { ctx.status(400).json(java.util.Map.of("error", "invalid_filename")); return; }
            var tmp = java.nio.file.Files.createTempFile(uploadSpoolDir(), "up-", ".bin");
            try {
                long written = 0;
                try (var in = f.content(); var out = java.nio.file.Files.newOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024]; int n;
                    while ((n = in.read(buf)) > 0) {
                        written += n;
                        if (written > limit) { ctx.status(413).json(java.util.Map.of("error", "upload_too_large", "limitBytes", limit)); return; }
                        out.write(buf, 0, n);
                    }
                }
                String id = store.add(tmp, f.filename());
                announce(ctx.ip(), () -> io.postcard.desktop.Notifier.uploaded(f.filename()));
                ctx.json(java.util.Map.of("id", id));
            } finally { java.nio.file.Files.deleteIfExists(tmp); }
        });
        app.get("/api/download/{id}", ctx -> {
            var id = ctx.pathParam("id");
            if (!id.matches("^[A-Za-z0-9_-]{8,64}$")) { ctx.status(400); return; }
            var entry = store.findById(id);
            if (entry == null) { ctx.status(404); return; }
            var p = store.resolve(id);
            if (p == null || !java.nio.file.Files.isRegularFile(p)) { ctx.status(404); return; }
            long size = entry.size();
            String filename = entry.name().replace("\"", "");
            announce(ctx.ip(), () -> io.postcard.desktop.Notifier.downloaded(entry.name()));
            ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            // Always octet-stream. Serving an uploaded .html or .svg under its probed type
            // would make it a same-origin document on the dashboard's own origin.
            ctx.header("Content-Type", "application/octet-stream");
            ctx.header("ETag", "\"" + entry.sha256() + "\"");

            if (shouldEncryptFor(ctx.ip())) {
                // Encrypted mode: no Range (each chunk carries its own nonce and tag, so a
                // byte range is not independently decryptable), full ciphertext+tag.
                KeyMaterial effective = effectiveKey();
                long ctLen = ChunkCipher.chunkContentLength(size);
                ctx.status(200);
                ctx.res().setContentLengthLong(ctLen);
                try (var in = java.nio.file.Files.newInputStream(p); var out = ctx.outputStream()) {
                    ChunkCipher.encryptStream(in, out, effective);
                }
                return;
            }
            String rangeH = ctx.header("Range");
            String ifRange = ctx.header("If-Range");
            if (ifRange != null && !("\"" + entry.sha256() + "\"").equals(ifRange) && !entry.sha256().equals(ifRange)) {
                rangeH = null; // stale ETag -> full re-send
            }
            if (rangeH == null) {
                ctx.status(200);
                ctx.res().setContentLengthLong(size);
                try (var in = java.nio.file.Files.newInputStream(p); var out = ctx.outputStream()) {
                    in.transferTo(out);
                }
                return;
            }
            var parsed = RangeParser.parse(rangeH, size);
            if (parsed.isEmpty()) { ctx.status(416).header("Content-Range", "bytes */" + size); return; }
            var r = parsed.get();
            long len = r.end() - r.start() + 1;
            ctx.status(206).header("Content-Range", "bytes " + r.start() + "-" + r.end() + "/" + size);
            ctx.res().setContentLengthLong(len);
            // Streamed, not buffered: this used to be readNBytes((int) len), which allocated the
            // whole range on the heap and silently truncated any range at or above 2 GiB.
            try (var in = java.nio.file.Files.newInputStream(p); var out = ctx.outputStream()) {
                in.skipNBytes(r.start());
                byte[] buf = new byte[64 * 1024];
                long remaining = len;
                while (remaining > 0) {
                    int want = (int) Math.min(buf.length, remaining);
                    int n = in.read(buf, 0, want);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    remaining -= n;
                }
            }
        });
        app.get("/api/clipboard", ctx -> ctx.json(java.util.Map.of("text", getClipboard())));

        // PIN security routes. Always registered so the frontend can poll
        // /api/pin/status; the actual gating happens inside the handler.
        io.postcard.security.PinSecurityRoutes.register(
            app,
            pinLimiter,
            this);

        // WebSocket route — see design §6.6, spec §6.1, brief Task 2.10.
        // Per-Javalin-Session adapter map keyed by Object (the Javalin WsContext) is the
        // identity-based remove the WebSocketHub verifier mandated. `onClose` looks up
        // the adapter by the Javalin WsContext and removes it from the hub; a buggy client
        // passing an anonymous PostcardSession can no longer evict a real one.
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                // Same-origin: a WebSocket upgrade is not subject to CORS at all, so without
                // this any page the host visits could open /ws and stream the file list.
                if (!originAllowed(ctx.header("Origin")) || !hostAllowed(ctx.header("Host"))) {
                    ctx.closeSession(4403, "bad origin");
                    return;
                }
                // The snapshot below carries every filename, size, hash and the shared
                // clipboard text. /api/files was gated on the PIN and this was not, so the
                // PIN protected nothing against anyone willing to open a WebSocket.
                if (pinRequired && !sessions.isValid(ctx.cookie(SESSION_COOKIE))) {
                    ctx.closeSession(4401, "pin required");
                    return;
                }
                // Enforce --auth-token (design §6.7, plan §29, spec §2 #1).
                if (opts.authToken != null) {
                    String qs = ctx.queryString();
                    String token = qs == null ? null : java.util.Arrays.stream(qs.split("&"))
                        .filter(p -> p.startsWith("token=")).findFirst().map(p -> p.substring(6)).orElse(null);
                    if (!opts.authToken.equals(token)) { ctx.closeSession(4401, "unauthorized"); return; }
                }
                var adapter = new io.postcard.ws.PostcardSession() {
                    public void send(String json) { try { ctx.send(json); } catch (Exception e) { throw new RuntimeException(e); } }
                    public void close(int code, String reason) { try { ctx.closeSession(code, reason); } catch (Exception _) {} }
                };
                sessionByJavalinSession.put(ctx, adapter);
                hub.add(adapter);
                // Initial snapshot (includes hotspot creds when in hotspot mode).
                try {
                    var sb = new StringBuilder("{\"type\":\"snapshot\",\"files\":[");
                    var list = store.list();
                    for (int i = 0; i < list.size(); i++) {
                        var e = list.get(i);
                        if (i > 0) sb.append(",");
                        sb.append("{\"id\":\"").append(e.id()).append("\",\"name\":\"").append(json(e.name())).append("\",\"size\":").append(e.size()).append(",\"mtime\":").append(e.mtime()).append(",\"sha256\":\"").append(e.sha256()).append("\"}");
                    }
                    sb.append("],\"clipboard\":\"").append(json(getClipboard())).append("\"");
                    if ("hotspot".equals(mode) && hotspotSsid != null) {
                        sb.append(",\"hotspot\":{\"ssid\":\"").append(json(hotspotSsid)).append("\",\"password\":\"").append(json(hotspotPassword)).append("\"}");
                    }
                    sb.append("}");
                    adapter.send(sb.toString());
                } catch (Exception _) {
                    // If snapshot build fails (e.g. disk read error), close the session.
                    try { ctx.closeSession(1011, "internal error"); } catch (Exception ignored2) {}
                }
            });
            ws.onMessage(ctx -> {
                try {
                    @SuppressWarnings("unchecked")
                    var j = (java.util.Map<String, Object>) new com.fasterxml.jackson.databind.ObjectMapper().readValue(ctx.message(), java.util.Map.class);
                    if ("clipboard".equals(j.get("type"))) {
                        var t = String.valueOf(j.getOrDefault("text", ""));
                        setClipboard(t);
                        hub.broadcast("{\"type\":\"clipboard\",\"text\":\"" + json(t) + "\"}");
                    }
                } catch (Exception _) {
                    // Malformed JSON: drop the frame, keep the session open.
                }
            });
            ws.onClose(ctx -> {
                var adapter = sessionByJavalinSession.remove(ctx);
                if (adapter != null) hub.remove(adapter);
            });
        });
        return app;
    }

    private volatile String clipboard = "";
    public String getClipboard() { return clipboard; }
    public void setClipboard(String t) { clipboard = t; }

    private static String json(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
