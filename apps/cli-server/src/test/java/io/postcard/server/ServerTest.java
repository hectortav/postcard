package io.postcard.server;

import io.postcard.security.PinSecurityEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerTest {
    private static Server plainServer() {
        return new Server(new PostcardOptions());
    }

    @Test void ownerRequiresExactBindHostMatch() {
        var s = plainServer();
        // Fail-closed: unset means nobody manages.
        assertFalse(s.isOwnerIp("127.0.0.1"));
        assertFalse(s.isOwnerIp("192.168.1.10"));
        s.setBindHost("192.168.1.10");
        assertTrue(s.isOwnerIp("192.168.1.10"));
        // Receivers and scanners are not the owner…
        assertFalse(s.isOwnerIp("192.168.1.11"));
        // …and neither is loopback unless the server actually bound it.
        assertFalse(s.isOwnerIp("127.0.0.1"));
        s.setBindHost("127.0.0.1");
        assertTrue(s.isOwnerIp("127.0.0.1"));
    }

    @Test void enablePinRejectsMalformedPins() {
        var s = plainServer();
        assertThrows(IllegalArgumentException.class, () -> s.enablePin(null));
        assertThrows(IllegalArgumentException.class, () -> s.enablePin(""));
        assertThrows(IllegalArgumentException.class, () -> s.enablePin("123"));
        assertThrows(IllegalArgumentException.class, () -> s.enablePin("12345"));
        assertThrows(IllegalArgumentException.class, () -> s.enablePin("12ab"));
        assertThrows(IllegalArgumentException.class, () -> s.enablePin("abcd"));
        assertFalse(s.pinRequired());
    }

    @Test void enablePinCreatesSecretArmsGateAndDropsVerifiedKeys() {
        var s = plainServer();
        assertNull(s.secretBytes());
        s.setDerivedKey(new byte[32]); // a previously verified receiver
        s.enablePin("1234");
        assertTrue(s.pinRequired());
        assertNotNull(s.secretBytes());
        assertNotNull(s.expectedDerivedKey());
        assertNull(s.derivedKey(), "re-keying must force every receiver to re-verify");
        assertTrue(PinSecurityEngine.verify(s.secretBytes(), "1234", s.expectedDerivedKey()));
        assertFalse(PinSecurityEngine.verify(s.secretBytes(), "9999", s.expectedDerivedKey()));
    }

    @Test void enablePinKeepsAnExistingSecret() {
        var s = plainServer();
        s.enablePin("1234");
        byte[] first = s.secretBytes().clone();
        s.enablePin("5678");
        assertArrayEquals(first, s.secretBytes(), "re-pinning must not rotate the session secret");
        assertTrue(PinSecurityEngine.verify(s.secretBytes(), "5678", s.expectedDerivedKey()));
        assertFalse(PinSecurityEngine.verify(s.secretBytes(), "1234", s.expectedDerivedKey()));
    }

    @Test void disablePinOpensGatesButKeepsTheSecret() {
        var s = plainServer();
        s.enablePin("1234");
        byte[] secret = s.secretBytes().clone();
        s.disablePin();
        assertFalse(s.pinRequired());
        assertNull(s.expectedDerivedKey());
        assertNull(s.derivedKey());
        assertArrayEquals(secret, s.secretBytes(), "disabling drops the gate, not the encryption");
    }

    @Test void disablePinOnAPlainSessionIsAHarmlessNoOp() {
        var s = plainServer();
        s.disablePin();
        assertFalse(s.pinRequired());
        assertNull(s.secretBytes());
    }
}
