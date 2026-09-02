package io.postcard.ws;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Broadcast plane for {@code file_added}, {@code file_removed}, and {@code clipboard}
 * events. Sessions are tracked in a {@link ConcurrentHashMap} keyed by the
 * {@link PostcardSession} reference itself, so {@link #remove(Object)} is identity-based
 * and an anonymous session passed by a buggy client cannot evict a real one.
 *
 * <p>Each session has a bounded per-session queue (1024 messages). If a session
 * cannot keep up, the slow consumer is closed with WebSocket status {@code 1013}
 * ("try again later") rather than back-pressuring the broadcast call site.
 *
 * <p>Task 2.10 will wire the real Jetty/WebSocket adapter to {@link PostcardSession};
 * Task 2.7 only delivers the hub.
 */
public final class WebSocketHub {
    static final int QUEUE_CAPACITY = 1024;
    static final int SLOW_CLOSE_CODE = 1013;

    private final ConcurrentHashMap<PostcardSession, Boolean> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PostcardSession, LinkedBlockingQueue<String>> queues = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public void add(PostcardSession s) {
        if (s == null) return;
        queues.put(s, new LinkedBlockingQueue<>(QUEUE_CAPACITY));
        sessions.put(s, Boolean.TRUE);
    }

    public void remove(PostcardSession s) {
        if (s == null) return;
        sessions.remove(s);
        queues.remove(s);
    }

    public int size() { return sessions.size(); }

    public void broadcast(String json) {
        for (var s : sessions.keySet()) {
            LinkedBlockingQueue<String> q = queues.get(s);
            if (q == null) continue; // removed between iteration and dispatch
            if (!q.offer(json)) {
                // slow consumer: close + drop from hub so we don't keep hammering it
                try { s.close(SLOW_CLOSE_CODE, "slow consumer"); } catch (Exception _) {}
                sessions.remove(s);
                queues.remove(s);
                continue;
            }
            try { s.send(json); } catch (Exception _) {}
        }
    }

    public void close() {
        for (var s : sessions.keySet()) {
            pool.submit(() -> {
                try { s.close(1001, "server shutdown"); } catch (Exception _) {}
            });
        }
        sessions.clear();
        queues.clear();
    }
}
