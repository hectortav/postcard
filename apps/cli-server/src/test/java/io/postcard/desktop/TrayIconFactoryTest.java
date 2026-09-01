package io.postcard.desktop;

import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class TrayIconFactoryTest {

    private static final Color PAPER = new Color(0xF4, 0xEE, 0xE2);
    private static final Color BRICK = new Color(0xA8, 0x33, 0x2A);

    /** Squared RGB distance — cheap and good enough to classify antialiased pixels. */
    private static int dist(Color a, Color b) {
        int dr = a.getRed() - b.getRed(), dg = a.getGreen() - b.getGreen(), db = a.getBlue() - b.getBlue();
        return dr * dr + dg * dg + db * db;
    }

    private static boolean isBrick(BufferedImage img, int x, int y) {
        Color c = new Color(img.getRGB(x, y), true);
        return dist(c, BRICK) < dist(c, PAPER);
    }

    private static boolean isPaper(BufferedImage img, int x, int y) {
        return !isBrick(img, x, y);
    }

    @Test void returnsImageOfRequestedSize() {
        for (int size : new int[] { 16, 32, 64 }) {
            BufferedImage img = TrayIconFactory.create(size);
            assertEquals(size, img.getWidth(), "width for size " + size);
            assertEquals(size, img.getHeight(), "height for size " + size);
        }
    }

    @Test void topLeftCornerIsPaperBackground() {
        BufferedImage img = TrayIconFactory.create(32);
        Color c = new Color(img.getRGB(0, 0), true);
        assertEquals(0xF4, c.getRed(),   "R");
        assertEquals(0xEE, c.getGreen(), "G");
        assertEquals(0xE2, c.getBlue(),  "B");
        assertEquals(0xFF, c.getAlpha(), "A");
    }

    @Test void stampBodyIsPredominantlyBrick() {
        int size = 64;
        BufferedImage img = TrayIconFactory.create(size);
        int inset = TrayIconFactory.stampInset(size);
        int brick = 0, total = 0;
        for (int y = inset; y < size - inset; y++) {
            for (int x = inset; x < size - inset; x++) {
                total++;
                if (isBrick(img, x, y)) brick++;
            }
        }
        assertTrue(brick > total / 2,
            "stamp body should be mostly brick, was " + brick + "/" + total);
    }

    @Test void monogramDrawsPaperPixelsInsideTheStamp() {
        int size = 64;
        BufferedImage img = TrayIconFactory.create(size);
        int inset = TrayIconFactory.stampInset(size);
        // Inset a further 3px so perforations along the stamp edge cannot be mistaken
        // for the monogram.
        int paper = 0, total = 0;
        for (int y = inset + 3; y < size - inset - 3; y++) {
            for (int x = inset + 3; x < size - inset - 3; x++) {
                total++;
                if (isPaper(img, x, y)) paper++;
            }
        }
        // The monogram must be present (not a bare brick square) but must remain a
        // minority of the stamp field (not an inverted stamp).
        assertTrue(paper > 20, "the paper 'p' monogram should sit inside the stamp, saw " + paper + " paper px");
        assertTrue(paper < total * 2 / 5,
            "the monogram must not swamp the stamp, was " + paper + "/" + total);
    }

    @Test void perforationsBreakTheStampEdgeAtLargeSizes() {
        int size = 64;
        BufferedImage img = TrayIconFactory.create(size);
        int inset = TrayIconFactory.stampInset(size);
        boolean sawBrick = false, sawPaper = false;
        for (int x = inset + 1; x < size - inset - 1; x++) {
            if (isBrick(img, x, inset)) sawBrick = true; else sawPaper = true;
        }
        assertTrue(sawBrick, "top edge must retain brick between perforations");
        assertTrue(sawPaper, "top edge must be interrupted by paper perforations");
    }

    @Test void smallSizesDropPerforationsForLegibility() {
        int size = 16;
        BufferedImage img = TrayIconFactory.create(size);
        int inset = TrayIconFactory.stampInset(size);
        // One row inside the stamp's top edge: at tray size the perforations are
        // dropped, so this row must be unbroken brick.
        for (int x = inset + 1; x < size - inset - 1; x++) {
            assertTrue(isBrick(img, x, inset + 1),
                "no perforations below the threshold; x=" + x + " should be brick");
        }
    }

    @Test void rejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> TrayIconFactory.create(0));
        assertThrows(IllegalArgumentException.class, () -> TrayIconFactory.create(-1));
    }
}
