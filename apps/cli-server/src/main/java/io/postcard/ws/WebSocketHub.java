package io.postcard.ws;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Broadcast plane for {@code file_added}, {@code file_removed}, and {@code clipboard} events.
 *
 * <p>Each session has a bounded queue and a pump: {@link #broadcast(String)} only enqueues, and
 * a virtual thread per session does the sending. That matters twice over. Sending is blocking
 * network I/O, and it used to happen on the broadcasting thread — which is an upload request
 * thread, so one unresponsive phone could stall an unrelated transfer. And because nothing ever
 * drained the queue, it filled monotonically: on broadcast number 1,025 every session in the
 * hub, healthy or not, was closed as a "slow consumer".
 *
 * <p>A session whose queue is genuinely full is closed with WebSocket status {@code 1013}
 * ("try again later") rather than back-pressuring the call site.
 */
public final class WebSocketHub {
    static final int QUEUE_CAPACITY = 1024;
    static final int SLOW_CLOSE_CODE = 1013;

    private record Sink(LinkedBlockingQueue<String> queue, Future<?> pump) {}

    private final ConcurrentHashMap<PostcardSession, Sink> sinks = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public void add(PostcardSession s) {
        if (s == null) return;
        var queue = new LinkedBlockingQueue<String>(QUEUE_CAPACITY);
        var pump = pool.submit(() -> pump(s, queue));
        sinks.put(s, new Sink(queue, pump));
    }

    /** Take-and-send loop for one session; ends on interrupt or the first failed send. */
    private void pump(PostcardSession s, LinkedBlockingQueue<String> queue) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String json = queue.take();
                try {
                    s.send(json);
                } catch (Exception e) {
                    // The peer is gone or the send failed: stop pumping and drop the session.
                    drop(s);
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void remove(PostcardSession s) {
        if (s == null) return;
        drop(s);
    }

    private void drop(PostcardSession s) {
        var sink = sinks.remove(s);
        if (sink != null) sink.pump().cancel(true);
    }

    public int size() { return sinks.size(); }

    public void broadcast(String json) {
        for (var entry : sinks.entrySet()) {
            var s = entry.getKey();
            var sink = entry.getValue();
            if (!sink.queue().offer(json)) {
                // Genuinely behind: close and drop rather than keep hammering it.
                drop(s);
                try { s.close(SLOW_CLOSE_CODE, "slow consumer"); } catch (Exception _) {}
            }
        }
    }

    public void close() {
        for (var entry : sinks.entrySet()) {
            entry.getValue().pump().cancel(true);
            var s = entry.getKey();
            pool.submit(() -> {
                try { s.close(1001, "server shutdown"); } catch (Exception _) {}
            });
        }
        sinks.clear();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
