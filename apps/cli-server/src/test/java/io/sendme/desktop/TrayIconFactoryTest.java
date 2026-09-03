package io.sendme.desktop;

import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class TrayIconFactoryTest {

    @Test void returnsImageOfRequestedSize() {
        for (int size : new int[] { 16, 32, 64 }) {
            BufferedImage img = TrayIconFactory.create(size);
            assertEquals(size, img.getWidth(), "width for size " + size);
            assertEquals(size, img.getHeight(), "height for size " + size);
        }
    }

    @Test void topLeftCornerIsPaperBackground() {
        BufferedImage img = TrayIconFactory.create(32);
        // Top-left pixel should be the paper background, not transparent.
        Color c = new Color(img.getRGB(0, 0), true);
        assertEquals(0xF4, c.getRed(),   "R");
        assertEquals(0xEE, c.getGreen(), "G");
        assertEquals(0xE2, c.getBlue(),  "B");
        assertEquals(0xFF, c.getAlpha(), "A");
    }

    @Test void centreContainsBrickRedPixels() {
        BufferedImage img = TrayIconFactory.create(32);
        int centre = 16;
        // The "S" mark is centred; the middle row should contain a brick-red pixel.
        boolean sawBrick = false;
        for (int x = 0; x < img.getWidth(); x++) {
            Color c = new Color(img.getRGB(x, centre), true);
            // Brick is approximately (0xA8, 0x33, 0x2A). Allow a small tolerance.
            if (c.getRed() > 140 && c.getRed() < 200
                && c.getGreen() < 80
                && c.getBlue() < 80) {
                sawBrick = true;
                break;
            }
        }
        assertTrue(sawBrick, "centre row must contain a brick-red pixel (the 'S')");
    }

    @Test void rejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> TrayIconFactory.create(0));
        assertThrows(IllegalArgumentException.class, () -> TrayIconFactory.create(-1));
    }
}
