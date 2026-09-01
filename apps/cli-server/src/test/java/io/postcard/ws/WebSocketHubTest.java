package io.postcard.ws;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WebSocketHubTest {
    static class FakeSession implements PostcardSession {
        final String key;
        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        volatile boolean closed = false; volatile int code = -1;
        FakeSession(String key) { this.key = key; }
        public void send(String json) { q.offer(json); }
        public void close(int code, String reason) { closed = true; this.code = code; }
    }

    @Test void broadcastFansOut() {
        var hub = new WebSocketHub();
        var a = new FakeSession("a"); var b = new FakeSession("b");
        hub.add(a); hub.add(b);
        hub.broadcast("{\"type\":\"snapshot\"}");
        assertEquals("{\"type\":\"snapshot\"}", a.q.poll());
        assertEquals("{\"type\":\"snapshot\"}", b.q.poll());
    }

    @Test void removeIsIdentityBased() {
        var hub = new WebSocketHub();
        var a = new FakeSession("a");
        hub.add(a);
        assertEquals(1, hub.size());
        hub.remove(a);
        assertEquals(0, hub.size());
        // Adding a fresh anonymous PostcardSession and trying to remove it must be a no-op
        hub.add(a);
        hub.remove(new PostcardSession() {
            public void send(String json) {}
            public void close(int code, String reason) {}
        });
        assertEquals(1, hub.size(), "anonymous remove must not affect the real session");
    }

    @Test void slowConsumerClosedWith1013() throws Exception {
        var hub = new WebSocketHub();
        var slow = new FakeSession("slow");
        hub.add(slow);
        for (int i = 0; i < 1025; i++) hub.broadcast("{\"i\":" + i + "}");
        Thread.sleep(50);
        assertTrue(slow.closed);
        assertEquals(1013, slow.code);
    }
}
