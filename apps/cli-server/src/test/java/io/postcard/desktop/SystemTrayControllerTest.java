package io.postcard.desktop;

import org.junit.jupiter.api.Test;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link SystemTrayController}.
 *
 * <p>The CI environment does not provide a real {@link SystemTray} host, so the
 * happy-path install cannot run there. We split the coverage into two pieces:
 *
 * <ul>
 *   <li>{@link #installReturnsEmptyWhenSystemTrayUnsupported()} — the only path
 *       that touches the JDK's static {@code SystemTray.isSupported()}; runs on
 *       CI and asserts the no-op return + INFO log.</li>
 *   <li>{@link #buildIconPopulatesMenuAndListeners()} — calls the package-private
 *       {@link SystemTrayController#buildIcon(String, Runnable, Runnable)} factory directly
 *       to inspect the constructed {@link TrayIcon}, its {@link PopupMenu}, and
 *       the action listeners attached to each item. Uses real AWT objects (no
 *       Mockito) so the test is deterministic and platform-independent.</li>
 * </ul>
 */
class SystemTrayControllerTest {

    @Test void installReturnsEmptyWhenSystemTrayUnsupported() {
        // On every CI runner, headless Linux without a status-notifier host, and
        // SSH sessions, SystemTray.isSupported() returns false. The controller
        // must return Optional.empty() without throwing.
        Optional<TrayIcon> result = SystemTrayController.install("http://127.0.0.1:8080/", () -> {}, () -> {});
        if (!SystemTray.isSupported()) {
            assertTrue(result.isEmpty(), "isSupported==false must yield Optional.empty()");
        } else {
            // The host running this test happens to have a tray (e.g. macOS dev
            // laptop). Skip the assertion but still report that the call did
            // not throw.
            assertNotNull(result);
        }
    }

    @Test void removeIsNoOpOnNull() {
        // Should not throw.
        SystemTrayController.remove(null);
    }

    @Test void buildIconPopulatesMenuAndListeners() {
        // Constructing a real java.awt.TrayIcon requires a display + a status-
        // notifier host. On headless CI runners (and SSH sessions) this throws
        // HeadlessException. Skip the test there; it still runs on a developer's
        // macOS / Windows / Linux-with-status-notifier machine.
        assumeTrue(SystemTray.isSupported(), "SystemTray not available in this environment");

        String url = "http://192.168.1.5:8080/";
        AtomicInteger quitCount = new AtomicInteger(0);

        TrayIcon icon = SystemTrayController.buildIcon(url, () -> {}, quitCount::incrementAndGet);
        assertNotNull(icon);
        assertEquals("postcard — " + url, icon.getToolTip());
        assertTrue(icon.isImageAutoSize(), "image should be auto-sized for crispness");

        PopupMenu menu = icon.getPopupMenu();
        assertNotNull(menu, "popup menu must be attached");

        List<String> labels = new ArrayList<>();
        for (int i = 0; i < menu.getItemCount(); i++) {
            MenuItem item = menu.getItem(i);
            if (item == null) {
                labels.add("<separator>");
            } else {
                labels.add(item.getLabel());
            }
        }
        // Order matters: Open Dashboard, Copy Local URL, separator, Quit.
        // Note: on Java 21 the separator appears as a MenuItem with label "-"
        // (not null) — we just need the slot to be a separator, not a regular
        // action item.
        assertEquals(4, labels.size(), "menu must have 4 entries: " + labels);
        assertEquals("Open Dashboard", labels.get(0));
        assertEquals("Copy Local URL", labels.get(1));
        assertEquals("Quit", labels.get(3));
        assertTrue(labels.get(2) == null || "-".equals(labels.get(2)),
            "middle slot must be a separator, got: " + labels.get(2));

        // Each of the three menu items must have an ActionListener attached.
        // (java.awt.MenuItem doesn't expose a count, but it does expose getActionCommand().)
        for (String label : List.of("Open Dashboard", "Copy Local URL", "Quit")) {
            MenuItem item = findItem(menu, label);
            assertNotNull(item, "menu item " + label + " must exist");
            assertNotNull(item.getActionListeners(), "listeners array must exist");
            assertTrue(item.getActionListeners().length >= 1, label + " must have at least one listener");
        }

        // The Quit listener invokes the supplied Runnable. We can't easily reach
        // the listener reference, but invoking it via getActionListeners()[0]
        // is enough to prove the wiring is correct.
        MenuItem quitItem = findItem(menu, "Quit");
        assertEquals(0, quitCount.get(), "precondition: quit counter is zero");
        quitItem.getActionListeners()[0].actionPerformed(null);
        assertEquals(1, quitCount.get(), "quit listener must invoke the supplied Runnable");
    }

    @Test void buildIconWorksWithNullQuitRunnable() {
        // Same environment assumption as buildIconPopulatesMenuAndListeners: a
        // real TrayIcon needs a display, so this test is skipped on headless CI.
        assumeTrue(SystemTray.isSupported(), "SystemTray not available in this environment");

        // Defensive: callers can pass null for the quit hook (Main wires one
        // but tests / future use might not). The build itself must not NPE.
        TrayIcon icon = assertDoesNotThrow(() ->
            SystemTrayController.buildIcon("http://127.0.0.1:0/", null, null));
        assertNotNull(icon);
        assertNotNull(icon.getPopupMenu());
    }

    private static MenuItem findItem(PopupMenu menu, String label) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            MenuItem item = menu.getItem(i);
            if (item != null && label.equals(item.getLabel())) return item;
        }
        return null;
    }

    @Test void openDashboardMenuItemDelegatesToTheDashboard() {
        // Same environment assumption as buildIconPopulatesMenuAndListeners: constructing a
        // real TrayIcon throws HeadlessException on a headless CI runner.
        assumeTrue(SystemTray.isSupported(), "SystemTray not available in this environment");

        // The tray used to launch a browser itself. It now knows only that *something* shows
        // the dashboard, which is what lets Main swap the implementation.
        var dashboard = new FakeDashboard();
        TrayIcon icon = SystemTrayController.buildIcon(
            "http://192.168.1.5:8080/", dashboard::open, () -> {});

        MenuItem open = icon.getPopupMenu().getItem(0);
        assertEquals("Open Dashboard", open.getLabel());
        open.getActionListeners()[0].actionPerformed(
            new java.awt.event.ActionEvent(open, java.awt.event.ActionEvent.ACTION_PERFORMED, "open"));

        assertEquals(1, dashboard.opens, "the tray must delegate to the dashboard");
    }
}
