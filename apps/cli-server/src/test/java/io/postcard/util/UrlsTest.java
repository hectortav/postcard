package io.postcard.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UrlsTest {

    @Test void theFragmentIsReplaced() {
        // The fragment is the whole secret: the AES key and, when armed, the PIN.
        assertEquals("http://192.168.1.5:8080/#<redacted>",
            Urls.redactFragment("http://192.168.1.5:8080/#key=AAAA-BBBB_CCCC&pin=1234"));
    }

    @Test void noFragmentIsLeftAlone() {
        assertEquals("http://192.168.1.5:8080/", Urls.redactFragment("http://192.168.1.5:8080/"));
    }

    @Test void anEmptyFragmentLosesItsHash() {
        assertEquals("http://a/", Urls.redactFragment("http://a/#"));
    }

    @Test void nullPassesThrough() {
        assertNull(Urls.redactFragment(null));
    }

    @Test void noPartOfTheKeyOrPinSurvives() {
        var redacted = Urls.redactFragment("http://10.0.0.2:1234/#key=s3cr3tKeyMaterial&pin=9876");
        assertFalse(redacted.contains("s3cr3t"));
        assertFalse(redacted.contains("9876"));
    }
}
