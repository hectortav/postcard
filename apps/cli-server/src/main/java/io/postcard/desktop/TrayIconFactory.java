package io.postcard.desktop;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/**
 * Pure-function factory for the postcard menu-bar / system-tray icon.
 *
 * <p>Renders the "Stamp" mark — a perforated brick-red postage stamp on a paper ground, with a
 * paper "p" monogram — drawn entirely in code so there is no bundled asset and no runtime font
 * dependency. This is the tray-sized counterpart to {@code icons/postcard.svg}, which is the
 * master used for the {@code .icns} / {@code .ico} / {@code .png} installer artwork.
 *
 * <p>Below {@link #PERFORATION_MIN_SIZE} the perforations are dropped: at 16 px they alias into
 * an indistinct fringe and cost more legibility than the silhouette they buy. The installer
 * artwork applies the same rule via a separate small-size master ({@code postcard-small.svg}).
 *
 * <p>Three sizes are typically produced: 16×16, 32×32, 64×64 (the 64-px buffer is a
 * retina-friendly source; the caller picks the right one for the current
 * {@link java.awt.TrayIcon} via {@link java.awt.TrayIcon#getSize()}).
 *
 * <p>The factory holds no AWT runtime state — it is safe to call from any thread, including the
 * headless CI.
 */
public final class TrayIconFactory {
    /** Paper background — matches the postcard marketing palette. */
    private static final Color PAPER = new Color(0xF4, 0xEE, 0xE2);
    /** Airmail brick — the stamp body. */
    private static final Color BRICK = new Color(0xA8, 0x33, 0x2A);

    /** Icon sizes below this drop the perforations; they only alias at tray scale. */
    static final int PERFORATION_MIN_SIZE = 32;

    /** Perforation circles per stamp edge, corners inclusive. */
    private static final int PERFORATIONS_PER_EDGE = 5;

    private TrayIconFactory() {}

    /** Distance from the icon edge to the stamp edge, in pixels, for a given icon size. */
    public static int stampInset(int size) {
        return Math.round(size * 0.14f);
    }

    /**
     * Render a tray icon of the given edge length in pixels. The size must be a positive
     * integer; values smaller than 16 still produce a usable (if not pretty) image.
     */
    public static BufferedImage create(int size) {
        if (size <= 0) throw new IllegalArgumentException("size must be positive, got " + size);
        var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(PAPER);
            g.fillRect(0, 0, size, size);

            int inset = stampInset(size);
            int span = size - 2 * inset;
            g.setColor(BRICK);
            g.fillRect(inset, inset, span, span);

            if (size >= PERFORATION_MIN_SIZE) punchPerforations(g, inset, span);
            drawMonogram(g, inset, span);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** Bite paper-coloured circles out of the stamp's four edges. */
    private static void punchPerforations(Graphics2D g, int inset, int span) {
        double r = span * 0.058;
        double step = span / (double) (PERFORATIONS_PER_EDGE - 1);
        g.setColor(PAPER);
        for (int i = 0; i < PERFORATIONS_PER_EDGE; i++) {
            double at = inset + i * step;
            fillCircle(g, at, inset, r);          // top
            fillCircle(g, at, inset + span, r);   // bottom
            fillCircle(g, inset, at, r);          // left
            fillCircle(g, inset + span, at, r);   // right
        }
    }

    private static void fillCircle(Graphics2D g, double cx, double cy, double r) {
        g.fill(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
    }

    /**
     * Draw the paper "p" centred on the stamp by its visual (ink) bounds rather than its
     * font metrics — at 16 px the difference between the two is several pixels.
     */
    private static void drawMonogram(Graphics2D g, int inset, int span) {
        var font = new Font(Font.SERIF, Font.BOLD, Math.max(1, Math.round(span * 0.78f)));
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), "p");
        Rectangle2D b = gv.getVisualBounds();
        float x = (float) (inset + (span - b.getWidth()) / 2 - b.getX());
        float y = (float) (inset + (span - b.getHeight()) / 2 - b.getY());
        g.setColor(PAPER);
        g.drawGlyphVector(gv, x, y);
    }
}
