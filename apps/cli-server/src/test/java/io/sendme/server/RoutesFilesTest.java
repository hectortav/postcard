package io.sendme.server;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RoutesFilesTest {
    @Test void serverInitializes() throws Exception {
        var opts = new SendmeOptions();
        var s = new Server(opts);
        s.init();
        assertNotNull(s.store());
    }
}
