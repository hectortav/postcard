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
    // PIN-derived AES key. Set by /api/pin/verify when the receiver proves
    // knowledge of the PIN. Until set, /api/files and /api/download refuse
    // requests with 401 when --pin was supplied at startup.
    private volatile byte[] derivedKey;
    // Pre-derived key the sender produced at startup (when --pin is set).
    // Used by the /api/pin/verify route to compare against the receiver's
    // attempt without ever storing the PIN server-side.
    private volatile byte[] expectedDerivedKey;
    // True when the CLI was launched with --pin. Gates /api/files and
    // /api/download/{id} until derivedKey is set.
    private volatile boolean pinRequired;

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

    /** Derived AES key the receiver proved control of. Null until /api/pin/verify succeeds. */
    public byte[] derivedKey() { return derivedKey; }
    public void setDerivedKey(byte[] k) { this.derivedKey = k; }

    /** Atomically swap the AES key the download route uses. */
    public void replaceKeyMaterial(KeyMaterial km) { this.keyMaterial = km; }

    /** Per-IP PIN rate limiter. */
    public io.postcard.security.PinRateLimiter pinLimiter() { return pinLimiter; }

    public Javalin build() {
        var app = Javalin.create(cfg -> {
            cfg.staticFiles.add(s -> {
                s.hostedPath = "/";
                s.directory = "/public";
                s.location = Location.CLASSPATH;
            });
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
        app.before(ctx -> { ctx.header("X-Postcard-Mode", mode); ctx.header("Cache-Control", "no-store"); });
        app.after(ctx -> { if (ctx.path().startsWith("/api/")) ctx.header("Cache-Control", "no-store"); });
        // In-flight tracking for graceful shutdown
        app.before(ctx -> Shutdown.enter());
        app.after(ctx -> Shutdown.leave());

        app.get("/api/files", ctx -> {
            if (pinRequired && derivedKey == null) {
                ctx.status(401).json(java.util.Map.of("error", "pin_required"));
                return;
            }
            ctx.json(store.list());
        });
        app.post("/api/upload", ctx -> {
            long limit = opts.maxUploadMiB == null ? Long.MAX_VALUE : opts.maxUploadMiB * 1024L * 1024L;
            long contentLength = ctx.req().getContentLengthLong();
            if (contentLength > limit) { ctx.status(413).json(java.util.Map.of("error", "upload_too_large", "limitBytes", limit)); return; }
            var f = ctx.uploadedFile("file");
            if (f == null) { ctx.status(400).json(java.util.Map.of("error", "missing_file")); return; }
            if (f.filename() != null && f.filename().codePoints().anyMatch(cp -> cp < 0x20)) { ctx.status(400).json(java.util.Map.of("error", "invalid_filename")); return; }
            var tmp = java.nio.file.Files.createTempFile("up-", ".bin");
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
            if (pinRequired && derivedKey == null) {
                ctx.status(401).json(java.util.Map.of("error", "pin_required"));
                return;
            }
            var id = ctx.pathParam("id");
            if (!id.matches("^[A-Za-z0-9_-]{8,64}$")) { ctx.status(400); return; }
            var entry = store.findById(id);
            if (entry == null) { ctx.status(404); return; }
            var p = store.resolve(id);
            long size = entry.size();
            String filename = entry.name().replace("\"", "");
            announce(ctx.ip(), () -> io.postcard.desktop.Notifier.downloaded(entry.name()));
            ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            String probed = java.nio.file.Files.probeContentType(p);
            ctx.header("Content-Type", probed != null ? probed : "application/octet-stream");
            ctx.header("ETag", "\"" + entry.sha256() + "\"");

            if (keyMaterial != null) {
                // Encrypted mode: ignore Range, full ciphertext+tag. After
                // the PIN is verified the derived key (which mixes the URL
                // secret with the user's PIN) is used for encryption. The
                // original keyMaterial is kept around so the verify route
                // can keep re-deriving and re-checking the PIN.
                KeyMaterial effective = derivedKey != null ? new KeyMaterial(derivedKey) : keyMaterial;
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
                rangeH = null; // stale ETag → full re-send
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
            try (var in = java.nio.file.Files.newInputStream(p)) { in.skipNBytes(r.start()); ctx.result(in.readNBytes((int) len)); }
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
