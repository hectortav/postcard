package io.postcard.desktop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class NotifierTest {

    private static final String HOST = "192.168.20.181";

    // ---- who counts as "another user" ----

    @Test void theHostsOwnRequestsAreNotPeers() {
        assertFalse(Notifier.isPeer(HOST, HOST), "the host's own LAN address is not a peer");
        assertFalse(Notifier.isPeer("127.0.0.1", HOST), "loopback is the host");
        assertFalse(Notifier.isPeer("::1", HOST), "IPv6 loopback is the host");
        assertFalse(Notifier.isPeer("0:0:0:0:0:0:0:1", HOST), "expanded IPv6 loopback is the host");
    }

    @Test void anotherDeviceOnTheLanIsAPeer() {
        assertTrue(Notifier.isPeer("192.168.20.55", HOST), "a phone on the same LAN is a peer");
    }

    @Test void unknownAddressesAreNotTreatedAsPeers() {
        // Better to miss a notification than to pop one for the user's own action.
        assertFalse(Notifier.isPeer(null, HOST));
        assertFalse(Notifier.isPeer("", HOST));
        assertFalse(Notifier.isPeer("   ", HOST));
    }

    // ---- what the notification says ----

    @Test void uploadNamesTheFileAndSaysItArrived() {
        var e = Notifier.uploaded("holiday.jpg");
        assertTrue(e.body().contains("holiday.jpg"), "body must name the file, was: " + e.body());
        assertFalse(e.title().isBlank());
    }

    @Test void downloadNamesTheFileAndSaysItLeft() {
        var e = Notifier.downloaded("report.pdf");
        assertTrue(e.body().contains("report.pdf"), "body must name the file, was: " + e.body());
        // The two events must read differently, or the notification is useless.
        assertNotEquals(Notifier.uploaded("report.pdf").body(), e.body());
    }

    @Test void longFileNamesAreTruncatedSoTheBalloonStaysReadable() {
        var name = "a".repeat(200) + ".bin";
        var e = Notifier.uploaded(name);
        assertTrue(e.body().length() < 120, "body should be trimmed, was " + e.body().length());
        assertTrue(e.body().contains("…"), "truncation should be visible, was: " + e.body());
    }

    // ---- delivery ----

    @Test void withoutATrayIconDeliveryIsASilentNoOp() {
        // Headless Linux, SSH sessions and CI have no tray; notifying must not throw.
        var sink = Notifier.forTray(Optional.empty());
        assertDoesNotThrow(() -> sink.accept(Notifier.uploaded("x.txt")));
    }

    @Test void eventsReachTheSinkInOrder() {
        List<String> seen = new ArrayList<>();
        var sink = Notifier.sink(e -> seen.add(e.body()));
        sink.accept(Notifier.uploaded("one.txt"));
        sink.accept(Notifier.downloaded("two.txt"));
        assertEquals(2, seen.size());
        assertTrue(seen.get(0).contains("one.txt"));
        assertTrue(seen.get(1).contains("two.txt"));
    }

    @Test void aFailingSinkNeverBreaksTheRequest() {
        // A notification is decoration; it must never take down an upload or download.
        var sink = Notifier.sink(e -> { throw new RuntimeException("tray exploded"); });
        assertDoesNotThrow(() -> sink.accept(Notifier.uploaded("x.txt")));
    }
}
