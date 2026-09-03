package io.sendme.server;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.sendme.crypto.ChunkCipher;
import io.sendme.crypto.KeyMaterial;
import io.sendme.http.RangeParser;
import io.sendme.ws.WebSocketHub;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Executors;

public final class Server {
    public static final java.util.concurrent.ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();
    private final SendmeOptions opts;
    private final WebSocketHub hub = new WebSocketHub();
    private FileStore store;
    private KeyMaterial keyMaterial;
    private String mode = "lan";
    private boolean tempDir;
    private String hotspotSsid;
    private String hotspotPassword;

    public Server(SendmeOptions opts) { this.opts = opts; }

    public void init() throws Exception {
        if (opts.path != null) { store = new FileStore(opts.path); tempDir = false; }
        else { store = new FileStore(java.nio.file.Files.createTempDirectory("sendme-")); tempDir = true; }
        store.setAddListener(e -> hub.broadcast("{\"type\":\"file_added\",\"id\":\"" + e.id() + "\",\"name\":\"" + json(e.name()) + "\",\"size\":" + e.size() + ",\"mtime\":" + e.mtime() + ",\"sha256\":\"" + e.sha256() + "\"}"));
        store.setRemoveListener(id -> hub.broadcast("{\"type\":\"file_removed\",\"id\":\"" + id + "\"}"));
        if (opts.encrypt) { var k = new byte[32]; new SecureRandom().nextBytes(k); keyMaterial = new KeyMaterial(k); }
    }

    public String keyB64Url() { return keyMaterial == null ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(keyMaterial.key()); }
    public String mode() { return mode; }
    public void setMode(String m) { this.mode = m; }
    public void setHotspot(String ssid, String password) { this.hotspotSsid = ssid; this.hotspotPassword = password; this.mode = "hotspot"; }
    public String hotspotSsid() { return hotspotSsid; }
    public String hotspotPassword() { return hotspotPassword; }
    public boolean tempDir() { return tempDir; }
    public WebSocketHub hub() { return hub; }
    public FileStore store() { return store; }
    public KeyMaterial keyMaterial() { return keyMaterial; }
    public SendmeOptions opts() { return opts; }

    public Javalin build() {
        var app = Javalin.create(cfg -> {
            cfg.staticFiles.add(s -> {
                s.hostedPath = "/";
                s.directory = "/public";
                s.location = Location.CLASSPATH;
            });
        });
        // Global response headers
        app.before(ctx -> { ctx.header("X-Sendme-Mode", mode); ctx.header("Cache-Control", "no-store"); });
        app.after(ctx -> { if (ctx.path().startsWith("/api/")) ctx.header("Cache-Control", "no-store"); });

        app.get("/api/files", ctx -> ctx.json(store.list()));
        app.post("/api/upload", ctx -> {
            long limit = opts.maxUploadMiB == null ? Long.MAX_VALUE : opts.maxUploadMiB * 1024L * 1024L;
            long contentLength = ctx.req().getContentLengthLong();
            if (contentLength > limit) { ctx.status(413).json(java.util.Map.of("error", "upload_too_large", "limitBytes", limit)); return; }
            var f = ctx.uploadedFile("file");
            if (f == null) { ctx.status(400).json(java.util.Map.of("error", "missing_file")); return; }
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
                ctx.json(java.util.Map.of("id", id));
            } finally { java.nio.file.Files.deleteIfExists(tmp); }
        });
        app.get("/api/download/{id}", ctx -> {
            var id = ctx.pathParam("id");
            if (!id.matches("^[A-Za-z0-9_-]{8,64}$")) { ctx.status(400); return; }
            var entry = store.findById(id);
            if (entry == null) { ctx.status(404); return; }
            var p = store.resolve(id);
            long size = entry.size();
            String filename = entry.name().replace("\"", "");
            ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            String probed = java.nio.file.Files.probeContentType(p);
            ctx.header("Content-Type", probed != null ? probed : "application/octet-stream");
            ctx.header("ETag", "\"" + entry.sha256() + "\"");

            if (keyMaterial != null) {
                // Encrypted mode: ignore Range, full ciphertext+tag
                long ctLen = ChunkCipher.chunkContentLength(size);
                ctx.status(200);
                ctx.res().setContentLengthLong(ctLen);
                try (var in = java.nio.file.Files.newInputStream(p); var out = ctx.outputStream()) {
                    ChunkCipher.encryptStream(in, out, keyMaterial);
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
        return app;
    }

    private volatile String clipboard = "";
    public String getClipboard() { return clipboard; }
    public void setClipboard(String t) { clipboard = t; }

    private static String json(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
