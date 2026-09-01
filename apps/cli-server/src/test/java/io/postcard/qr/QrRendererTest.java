package io.postcard.qr;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QrRendererTest {
    @Test void producesNonEmptyAnsi() { assertFalse(QrRenderer.ansi("http://192.168.1.50:8080/").isEmpty()); }
}
