package io.sendme.desktop;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Pure-function factory for the sendme menu-bar / system-tray icon.
 *
 * <p>Renders a paper-coloured square with a brick-red "S" mark, drawn entirely in code so there
 * is no bundled asset and no runtime font dependency. Three sizes are produced: 16×16, 32×32,
 * 64×64 (the 64-px buffer is a retina-friendly source; the caller picks the right one for the
 * current {@link java.awt.TrayIcon} via {@link java.awt.TrayIcon#getSize()}).
 *
 * <p>The factory holds no AWT runtime state — it is safe to call from any thread, including the
 * headless CI.
 */
public final class TrayIconFactory {
    /** Paper background — matches the sendme marketing palette. */
    private static final Color PAPER = new Color(0xF4, 0xEE, 0xE2);
    /** Airmail brick — used for the "S" mark. */
    private static final Color BRICK = new Color(0xA8, 0x33, 0x2A);

    private TrayIconFactory() {}

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
            // Background fill — paper.
            g.setColor(PAPER);
            g.fillRect(0, 0, size, size);
            // The "S" mark — SANS_SERIF BOLD, sized to ~70% of the icon, centred.
            int fontSize = Math.max(1, (int) Math.round(size * 0.7));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            g.setColor(BRICK);
            FontMetrics fm = g.getFontMetrics();
            int textW = fm.stringWidth("S");
            int textH = fm.getAscent() - fm.getDescent();
            int x = (size - textW) / 2;
            int y = (size - textH) / 2 + fm.getAscent();
            g.drawString("S", x, y);
        } finally {
            g.dispose();
        }
        return img;
    }
}
