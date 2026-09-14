package io.postcard.server;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The shared directory, indexed.
 *
 * <p>Two kinds of file live here. Uploads are stored under a generated {@link
 * io.postcard.crypto.Ids} name with the original filename in a {@code .name} sidecar, so the
 * id is already URL-safe. Files the operator put there themselves ({@code --path}) keep their
 * own names, which are usually <em>not</em> URL-safe — {@code report.pdf} contains a dot, and
 * the download route rejects any id outside {@link #ID_PATTERN} before touching the
 * filesystem. Those files therefore get a deterministic synthetic id derived from the
 * filename, and {@link #resolve(String)} maps it back. Without this, every pre-existing file
 * with an extension answered 400.
 *
 * <p>Hashes are cached per (filename, size, mtime). {@link #list()} used to re-read every byte
 * of every file on every call, and {@link #findById(String)} calls {@code list()}, so a single
 * download first hashed the whole directory.
 */
public final class FileStore {
    public record Entry(String id, String name, long size, long mtime, String sha256) {}

    /** Ids the download route accepts; see {@code Server}'s {@code /api/download/{id}} guard. */
    static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private record Cached(long size, long mtime, String sha) {}

    private final Path dir;
    private final List<Consumer<Entry>> addListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> removeListeners = new CopyOnWriteArrayList<>();
    /** Synthetic id -> on-disk filename. Populated by {@link #list()}; identity for uploads. */
    private final ConcurrentHashMap<String, String> idToFilename = new ConcurrentHashMap<>();
    /** On-disk filename -> last known (size, mtime, sha256). */
    private final ConcurrentHashMap<String, Cached> shaCache = new ConcurrentHashMap<>();

    public FileStore(Path dir) throws Exception { this.dir = dir; Files.createDirectories(dir); }
    public Path dir() { return dir; }
    public void setAddListener(Consumer<Entry> l) { addListeners.add(l); }
    public void setRemoveListener(Consumer<String> l) { removeListeners.add(l); }

    public String add(Path src, String originalName) throws Exception {
        var id = io.postcard.crypto.Ids.newId();
        var dest = dir.resolve(id);
        try (var in = Files.newInputStream(src); var out = Files.newOutputStream(dest)) { in.transferTo(out); }
        // Sidecar file stores the original upload name so the Content-Disposition
        // header on /api/download/{id} can use it after a server restart.
        // The id (the on-disk filename) is the lookup key; the .name file is
        // written atomically with the upload and deleted on remove.
        var nameSidecar = dir.resolve(id + ".name");
        Files.writeString(nameSidecar, originalName, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        var size = Files.size(dest);
        var mtime = Files.getLastModifiedTime(dest).toMillis();
        var sha = sha256Hex(dest);
        shaCache.put(id, new Cached(size, mtime, sha));
        idToFilename.put(id, id);
        var e = new Entry(id, originalName, size, mtime, sha);
        addListeners.forEach(l -> l.accept(e));
        return id;
    }

    /**
     * The whole directory, newest information first read from disk.
     *
     * <p>A file that cannot be read — deleted mid-listing, or permission-denied — is skipped
     * rather than failing the request. Previously one unreadable file turned {@code /api/files}
     * into a 500 for every client.
     */
    public List<Entry> list() throws Exception {
        var out = new ArrayList<Entry>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                var filename = p.getFileName().toString();
                if (filename.endsWith(".name")) continue; // sidecar, not a shared file
                try {
                    var size = Files.size(p);
                    var mtime = Files.getLastModifiedTime(p).toMillis();
                    var id = idFor(filename);
                    idToFilename.put(id, filename);
                    out.add(new Entry(id, readOriginalName(p), size, mtime, sha256Cached(p, filename, size, mtime)));
                } catch (Exception e) {
                    // Racing deletion, an unreadable file, a broken symlink: skip it.
                    org.slf4j.LoggerFactory.getLogger(FileStore.class)
                        .warn("postcard: skipping unreadable file {} ({})", filename, e.toString());
                }
            }
        }
        return List.copyOf(out);
    }

    public Entry findById(String id) throws Exception {
        for (var e : list()) if (e.id().equals(id)) return e;
        return null;
    }

    /**
     * The path an id names, or {@code null} when the id maps outside the shared directory.
     *
     * <p>Callers reach this only after {@link #findById(String)} has matched, which is what
     * populates the id map. The containment check is belt-and-braces on top of the route's
     * {@link #ID_PATTERN} guard.
     */
    public Path resolve(String id) {
        var filename = idToFilename.get(id);
        if (filename == null) {
            // Not yet indexed: an upload id is its own filename, so try that.
            if (!ID_PATTERN.matcher(id).matches()) return null;
            filename = id;
        }
        var p = dir.resolve(filename).normalize();
        return p.startsWith(dir.normalize()) ? p : null;
    }

    public void remove(String id) throws Exception {
        var filename = idToFilename.getOrDefault(id, id);
        Files.deleteIfExists(dir.resolve(filename));
        Files.deleteIfExists(dir.resolve(filename + ".name"));
        idToFilename.remove(id);
        shaCache.remove(filename);
        removeListeners.forEach(l -> l.accept(id));
    }

    /**
     * The id for an on-disk filename: itself when it is already URL-safe (every upload is), and
     * otherwise a deterministic 22-character base64url digest of the name. Deterministic so a
     * download link stays valid across {@code /api/files} calls and across restarts.
     */
    static String idFor(String filename) {
        if (ID_PATTERN.matcher(filename).matches()) return filename;
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(filename.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from JRE", e);
        }
    }

    private String sha256Cached(Path p, String filename, long size, long mtime) throws Exception {
        var hit = shaCache.get(filename);
        if (hit != null && hit.size() == size && hit.mtime() == mtime) return hit.sha();
        var sha = sha256Hex(p);
        shaCache.put(filename, new Cached(size, mtime, sha));
        return sha;
    }

    private String readOriginalName(Path file) throws Exception {
        var sidecar = dir.resolve(file.getFileName().toString() + ".name");
        if (Files.exists(sidecar)) return Files.readString(sidecar).trim();
        // No sidecar — either an upload from before the sidecar-on-write fix,
        // or a manually-placed file. Fall back to the on-disk filename (id)
        // for backward compatibility, but the spec wires the original name
        // through the sidecar so the only path that misses is pre-fix data.
        return file.getFileName().toString();
    }

    private static String sha256Hex(Path p) throws Exception {
        try (InputStream in = Files.newInputStream(p)) {
            var md = MessageDigest.getInstance("SHA-256");
            var buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        }
    }
}
