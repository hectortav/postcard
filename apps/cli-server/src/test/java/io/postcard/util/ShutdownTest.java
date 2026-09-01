package io.postcard.util;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ShutdownTest {
    @Test void interruptsSlowHandlerAfterBudget() throws Exception {
        var inter = new CountDownLatch(1);
        Shutdown.enter();
        new Thread(() -> { try { Thread.sleep(60_000); } catch (InterruptedException e) { inter.countDown(); Shutdown.leave(); } }).start();
        Thread.sleep(50);
        Shutdown.drain(1, () -> {}, () -> {});
        assertEquals(1, inter.getCount(), "handler should have been interrupted");
    }
}
