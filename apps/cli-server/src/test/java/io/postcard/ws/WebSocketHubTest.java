package io.postcard.ws;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WebSocketHubTest {
    /** A healthy peer: accepts every frame immediately. */
    static class FakeSession implements PostcardSession {
        final String key;
        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        volatile boolean closed = false; volatile int code = -1;
        FakeSession(String key) { this.key = key; }
        public void send(String json) { q.offer(json); }
        public void close(int code, String reason) { closed = true; this.code = code; }
    }

    /** A peer that never finishes a send, so its queue really does back up. */
    static class StalledSession implements PostcardSession {
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean closed = false; volatile int code = -1;
        public void send(String json) {
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        public void close(int code, String reason) { closed = true; this.code = code; }
    }

    private static String poll(FakeSession s) throws Exception {
        return s.q.poll(2, TimeUnit.SECONDS);
    }

    @Test void broadcastFansOut() throws Exception {
        var hub = new WebSocketHub();
        var a = new FakeSession("a"); var b = new FakeSession("b");
        hub.add(a); hub.add(b);
        hub.broadcast("{\"type\":\"snapshot\"}");
        assertEquals("{\"type\":\"snapshot\"}", poll(a));
        assertEquals("{\"type\":\"snapshot\"}", poll(b));
        hub.close();
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
        hub.close();
    }

    @Test void aHealthyConsumerSurvivesFarMoreThanTheQueueDepth() throws Exception {
        // The regression this pins: the per-session queue was filled by every broadcast and
        // drained by nothing, so frame 1,025 closed every client in the hub as a "slow
        // consumer" -- including ones that had acknowledged all 1,024 earlier frames.
        //
        // Sent in batches well under the queue depth, draining each before the next. The
        // point is the cumulative count, not how much can be crammed in at once: a bounded
        // queue is allowed to overflow when the producer genuinely outruns the consumer, and
        // an unthrottled loop of 3,072 broadcasts can do exactly that on a loaded machine,
        // which made this test fail on the slowest CI runner for the right reason.
        var hub = new WebSocketHub();
        var healthy = new FakeSession("healthy");
        hub.add(healthy);
        int batch = WebSocketHub.QUEUE_CAPACITY / 4;
        int batches = 12; // 3x the queue depth in total
        int sent = 0;
        for (int b = 0; b < batches; b++) {
            for (int i = 0; i < batch; i++) hub.broadcast("{\"i\":" + (sent + i) + "}");
            for (int i = 0; i < batch; i++) {
                assertEquals("{\"i\":" + (sent + i) + "}", poll(healthy),
                    "frame " + (sent + i) + " should arrive in order");
            }
            sent += batch;
        }
        assertEquals(WebSocketHub.QUEUE_CAPACITY * 3, sent);
        assertFalse(healthy.closed, "a consumer that keeps up must never be dropped");
        assertEquals(1, hub.size());
        hub.close();
    }

    @Test void aGenuinelyStalledConsumerIsClosedWith1013() throws Exception {
        var hub = new WebSocketHub();
        var stalled = new StalledSession();
        hub.add(stalled);
        // The pump takes one frame and blocks in send; the rest pile up behind it.
        for (int i = 0; i < WebSocketHub.QUEUE_CAPACITY + 8; i++) hub.broadcast("{\"i\":" + i + "}");
        long deadline = System.currentTimeMillis() + 2000;
        while (!stalled.closed && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue(stalled.closed, "a peer that cannot keep up must be dropped");
        assertEquals(1013, stalled.code);
        assertEquals(0, hub.size());
        stalled.release.countDown();
        hub.close();
    }

    @Test void closeStopsPumpsAndClosesSessions() throws Exception {
        var hub = new WebSocketHub();
        var a = new FakeSession("a");
        hub.add(a);
        hub.close();
        long deadline = System.currentTimeMillis() + 2000;
        while (!a.closed && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue(a.closed);
        assertEquals(1001, a.code);
        assertEquals(0, hub.size());
    }
}
