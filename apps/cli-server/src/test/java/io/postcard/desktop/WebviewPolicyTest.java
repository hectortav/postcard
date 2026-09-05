package io.postcard.desktop;

import org.junit.jupiter.api.Test;

import static io.postcard.desktop.WebviewPolicy.Decision.OPEN_EXTERNALLY;
import static io.postcard.desktop.WebviewPolicy.Decision.SHOW_IN_APP;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WebviewPolicyTest {
    private static final String BASE = "http://192.168.1.10:8080/#key=abc&pin=1234";

    @Test void sameOriginStaysInApp() {
        assertEquals(SHOW_IN_APP, WebviewPolicy.decide("http://192.168.1.10:8080/", BASE));
    }

    @Test void sameOriginWithPathAndQueryStaysInApp() {
        assertEquals(SHOW_IN_APP, WebviewPolicy.decide("http://192.168.1.10:8080/api/files?x=1", BASE));
    }

    @Test void fragmentAndCaseDoNotChangeTheOrigin() {
        assertEquals(SHOW_IN_APP, WebviewPolicy.decide("HTTP://192.168.1.10:8080/#other", BASE));
    }

    @Test void differentHostOpensExternally() {
        assertEquals(OPEN_EXTERNALLY, WebviewPolicy.decide("http://192.168.1.11:8080/", BASE));
    }

    @Test void differentPortOpensExternally() {
        assertEquals(OPEN_EXTERNALLY, WebviewPolicy.decide("http://192.168.1.10:9090/", BASE));
    }

    @Test void differentSchemeOpensExternally() {
        assertEquals(OPEN_EXTERNALLY, WebviewPolicy.decide("https://192.168.1.10:8080/", BASE));
    }

    @Test void unparseableTargetStaysInAppForTheEngineToFail() {
        assertEquals(SHOW_IN_APP, WebviewPolicy.decide("about:blank", BASE));
        assertEquals(SHOW_IN_APP, WebviewPolicy.decide("http://[::1", BASE));
        assertEquals(SHOW_IN_APP, WebviewPolicy.decide(null, BASE));
        assertEquals(SHOW_IN_APP, WebviewPolicy.decide("   ", BASE));
        assertEquals(SHOW_IN_APP, WebviewPolicy.decide("http://192.168.1.10:8080/", null));
    }
}
