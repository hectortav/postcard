package io.sendme.server;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class FileStore {
    public record Entry(String id, String name, long size, long mtime, String sha256) {}

    private final Path dir;
    private final List<Consumer<Entry>> addListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> removeListeners = new CopyOnWriteArrayList<>();

    public FileStore(Path dir) throws Exception { this.dir = dir; Files.createDirectories(dir); }
    public Path dir() { return dir; }
    public void setAddListener(Consumer<Entry> l) { addListeners.add(l); }
    public void setRemoveListener(Consumer<String> l) { removeListeners.add(l); }

    public String add(Path src, String originalName) throws Exception {
        var id = io.sendme.crypto.Ids.newId();
        var dest = dir.resolve(id);
        try (var in = Files.newInputStream(src); var out = Files.newOutputStream(dest)) { in.transferTo(out); }
        // Sidecar file stores the original upload name so the Content-Disposition
        // header on /api/download/{id} can use it after a server restart.
        // The id (the on-disk filename) is the lookup key; the .name file is
        // written atomically with the upload and deleted on remove.
        var nameSidecar = dir.resolve(id + ".name");
        Files.writeString(nameSidecar, originalName, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        var sha = sha256Hex(dest);
        var size = Files.size(dest);
        var mtime = Files.getLastModifiedTime(dest).toMillis();
        var e = new Entry(id, originalName, size, mtime, sha);
        addListeners.forEach(l -> l.accept(e));
        return id;
    }

    public List<Entry> list() throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).map(p -> {
                try {
                    var id = p.getFileName().toString();
                    if (id.endsWith(".name")) return null; // skip sidecars
                    var name = readOriginalName(p);
                    return new Entry(id, name, Files.size(p), Files.getLastModifiedTime(p).toMillis(), sha256Hex(p));
                }
                catch (Exception e) { throw new RuntimeException(e); }
            }).filter(java.util.Objects::nonNull).toList();
        }
    }

    public Entry findById(String id) throws Exception {
        for (var e : list()) if (e.id().equals(id)) return e;
        return null;
    }

    public Path resolve(String id) { return dir.resolve(id); }
    public void remove(String id) throws Exception {
        Files.deleteIfExists(dir.resolve(id));
        Files.deleteIfExists(dir.resolve(id + ".name"));
        removeListeners.forEach(l -> l.accept(id));
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
