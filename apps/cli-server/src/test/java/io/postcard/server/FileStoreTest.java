package io.postcard.server;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FileStoreTest {
    Path dir;
    @BeforeEach void setUp() throws Exception { dir = Files.createTempDirectory("fs-"); }
    @AfterEach void tearDown() throws Exception { if (Files.exists(dir)) Files.walk(dir).sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} }); }

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
}
