package io.sendme.util;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class Shutdown {
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    public static void enter() { IN_FLIGHT.incrementAndGet(); }
    public static void leave() { IN_FLIGHT.decrementAndGet(); }
    public static int inFlight() { return IN_FLIGHT.get(); }

    public static void drain(int budgetSeconds, Runnable onTimeout, Runnable afterDrain) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(budgetSeconds);
        while (IN_FLIGHT.get() > 0 && System.nanoTime() < deadline) { try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } }
        if (IN_FLIGHT.get() > 0) {
            // Drain budget exhausted; 1s upper bound on wait before forcing teardown.
            onTimeout.run();
            try { Thread.sleep(1_000); } catch (InterruptedException ignored) {}
        }
        afterDrain.run();
    }
    public static void run() { drain(3, () -> {}, () -> {}); }
}
