package io.postcard.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.TrayIcon;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Desktop notifications for activity by other devices: a file arriving, or one of yours being
 * taken.
 *
 * <p>Delivered natively through the tray icon rather than through the browser's Notification
 * API. That is not a preference — the Notification API is gated on a secure context, and
 * postcard serves from {@code http://<lan-ip>:<port>}, which is not one. (The same constraint
 * rules out {@code crypto.subtle}, which is why file encryption uses a pure-JS AES
 * implementation.) The tray has no such restriction.
 *
 * <p>Only peers are announced. Notifying the user about their own upload would be noise, so
 * requests from the host itself — loopback, or the address postcard is bound to — are ignored.
 * Anything unrecognised is treated as <em>not</em> a peer: missing a notification is much
 * cheaper than popping one for the user's own action.
 */
public final class Notifier {
    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    /** Tray balloons are small; past this the name is elided. */
    private static final int MAX_NAME = 64;

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    /** A notification ready for display. */
    public record Event(String title, String body) {}

    private Notifier() {}

    /** Whether a request came from a device other than the host running postcard. */
    public static boolean isPeer(String requestIp, String hostIp) {
        if (requestIp == null || requestIp.isBlank()) return false;
        var ip = requestIp.strip();
        if (LOOPBACK.contains(ip)) return false;
        return !ip.equals(hostIp);
    }

    public static Event uploaded(String fileName) {
        return new Event("postcard", "Received " + elide(fileName));
    }

    public static Event downloaded(String fileName) {
        return new Event("postcard", "Someone downloaded " + elide(fileName));
    }

    private static String elide(String name) {
        var n = name == null ? "(unnamed)" : name;
        return n.length() <= MAX_NAME ? n : n.substring(0, MAX_NAME - 1) + "…";
    }

    /**
     * Wrap a delivery function so a failure can never propagate into the request that
     * triggered it. A notification is decoration; an upload is not.
     */
    public static Consumer<Event> sink(Consumer<Event> delivery) {
        return event -> {
            try {
                delivery.accept(event);
            } catch (Exception e) {
                log.debug("postcard: could not show notification ({})", e.getMessage());
            }
        };
    }

    /**
     * Deliver through the tray icon, or do nothing when there is no tray (headless Linux, SSH
     * sessions, CI).
     */
    public static Consumer<Event> forTray(Optional<TrayIcon> icon) {
        if (icon.isEmpty()) return sink(_ -> {});
        var tray = icon.get();
        return sink(event -> java.awt.EventQueue.invokeLater(() ->
            tray.displayMessage(event.title(), event.body(), TrayIcon.MessageType.INFO)));
    }
}
