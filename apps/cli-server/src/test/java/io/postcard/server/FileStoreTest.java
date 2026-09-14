package io.postcard.server;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FileStoreTest {
    Path dir;
    @BeforeEach void setUp() throws Exception { dir = Files.createTempDirectory("fs-"); }
    @AfterEach void tearDown() throws Exception { if (Files.exists(dir)) Files.walk(dir).sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception _) {} }); }

    @Test void addEmitsBroadcastOnlyAfterHash() throws Exception {
        var store = new FileStore(dir);
        var emitted = new LinkedBlockingQueue<FileStore.Entry>();
        store.setAddListener(emitted::offer);
        var tmp = Files.createTempFile("a-", ".bin"); Files.write(tmp, new byte[]{1, 2, 3, 4});
        var id = store.add(tmp, "a.bin");
        Files.deleteIfExists(tmp);
        var e = emitted.poll(2, TimeUnit.SECONDS);
        assertNotNull(e, "broadcast should fire after hash");
        assertEquals(id, e.id());
        assertEquals("a.bin", e.name());
        var expected = MessageDigest.getInstance("SHA-256").digest(new byte[]{1, 2, 3, 4});
        var expectedHex = java.util.HexFormat.of().formatHex(expected);
        assertEquals(expectedHex, e.sha256());
    }
    @Test void removeFiresRemovalListenerAndDeletes() throws Exception {
        var store = new FileStore(dir);
        var removed = new LinkedBlockingQueue<String>();
        store.setRemoveListener(removed::offer);
        var tmp = Files.createTempFile("a-", ".bin"); Files.write(tmp, new byte[]{1, 2, 3, 4});
        var id = store.add(tmp, "a.bin");
        Files.deleteIfExists(tmp);
        store.remove(id);
        assertEquals(id, removed.poll(2, TimeUnit.SECONDS));
        assertFalse(Files.exists(dir.resolve(id)));
    }

    @Test void preExistingFilesGetUrlSafeIds() throws Exception {
        // --path mode shares files the operator already had. Their on-disk names are their
        // ids, and the download route rejects any id outside ^[A-Za-z0-9_-]{8,64}$ before
        // touching the filesystem -- so "report.pdf" answered 400 and could never be fetched.
        var store = new FileStore(dir);
        Files.writeString(dir.resolve("report.pdf"), "hello");
        Files.writeString(dir.resolve("holiday photo.JPG"), "jpeg");
        Files.writeString(dir.resolve("\u03c0.txt"), "greek");
        for (var e : store.list()) {
            assertTrue(FileStore.ID_PATTERN.matcher(e.id()).matches(),
                "id must be URL-safe for " + e.name() + ", got " + e.id());
            assertNotNull(store.resolve(e.id()));
            assertTrue(Files.isRegularFile(store.resolve(e.id())));
        }
    }

    @Test void idsAreStableAcrossListings() throws Exception {
        var store = new FileStore(dir);
        Files.writeString(dir.resolve("report.pdf"), "hello");
        var first = store.list().get(0).id();
        var second = store.list().get(0).id();
        assertEquals(first, second, "a download link must stay valid between listings");
        // Deterministic from the name alone, so it also survives a restart.
        assertEquals(first, FileStore.idFor("report.pdf"));
    }

    @Test void theOriginalNameIsPreservedForPreExistingFiles() throws Exception {
        var store = new FileStore(dir);
        Files.writeString(dir.resolve("report.pdf"), "hello");
        var e = store.list().get(0);
        assertEquals("report.pdf", e.name());
        assertEquals("hello", Files.readString(store.resolve(e.id())));
    }

    @Test void alreadySafeNamesKeepTheirOwnId() throws Exception {
        var store = new FileStore(dir);
        Files.writeString(dir.resolve("already-safe-name"), "x");
        assertEquals("already-safe-name", store.list().get(0).id());
    }

    @Test void resolveRefusesIdsItDoesNotKnow() throws Exception {
        var store = new FileStore(dir);
        assertNull(store.resolve("../../etc/passwd"));
        assertNull(store.resolve("no/slashes/allowed"));
    }

    @Test void anUnreadableFileIsSkippedRatherThanFailingTheListing() throws Exception {
        var store = new FileStore(dir);
        Files.writeString(dir.resolve("good-file-one"), "a");
        var bad = dir.resolve("bad-file-one");
        Files.writeString(bad, "b");
        // A dangling symlink stands in for any file that cannot be read: previously one of
        // these turned /api/files into a 500 for every client.
        Files.delete(bad);
        Files.createSymbolicLink(bad, dir.resolve("nonexistent-target"));
        var names = store.list().stream().map(FileStore.Entry::name).toList();
        assertTrue(names.contains("good-file-one"), "the readable file must still be listed");
    }

    @Test void hashesAreCachedUntilTheFileChanges() throws Exception {
        var store = new FileStore(dir);
        var f = dir.resolve("cached-file-name");
        Files.writeString(f, "one");
        var first = store.list().get(0).sha256();
        assertEquals(first, store.list().get(0).sha256());
        // A changed file must re-hash: size and mtime both move.
        Thread.sleep(10);
        Files.writeString(f, "two-different");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertNotEquals(first, store.list().get(0).sha256(), "a changed file must be re-hashed");
    }
}
