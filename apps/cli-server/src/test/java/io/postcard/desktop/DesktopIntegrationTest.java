package io.postcard.desktop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DesktopIntegrationTest {

    @Test void reopenActionOpensTheServerUrl() {
        List<String> opened = new ArrayList<>();
        DesktopIntegration.reopenAction("http://192.168.1.5:8080/", opened::add).run();
        assertEquals(List.of("http://192.168.1.5:8080/"), opened,
            "a dock/taskbar reopen must re-open the server URL");
    }

    @Test void reopenActionSurvivesAFailingOpener() {
        // A browser that refuses to launch must not take down the running server.
        Runnable action = DesktopIntegration.reopenAction("http://192.168.1.5:8080/", url -> {
            throw new RuntimeException("no browser");
        });
        assertDoesNotThrow(action::run);
    }

    @Test void installReopenHandlerReportsWhetherThePlatformSupportsIt() {
        // macOS supports APP_EVENT_REOPENED; headless CI and Linux do not. Either
        // way this must answer without throwing, so Main can call it unguarded.
        assertDoesNotThrow(() -> DesktopIntegration.installReopenHandler("http://127.0.0.1:1/", u -> {}));
    }

    @Test void rejectsNullUrl() {
        assertThrows(NullPointerException.class,
            () -> DesktopIntegration.reopenAction(null, u -> {}));
    }
}
