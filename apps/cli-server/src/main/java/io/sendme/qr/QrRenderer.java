package io.sendme.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public final class QrRenderer {
    public static String ansi(String text) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 25, 25);
            var sb = new StringBuilder();
            for (int y = 0; y < m.getHeight(); y += 2) {
                for (int x = 0; x < m.getWidth(); x++) {
                    boolean top = m.get(x, y);
                    boolean bot = y + 1 < m.getHeight() && m.get(x, y + 1);
                    sb.append(top && bot ? "█" : top ? "▀" : bot ? "▄" : " ");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
    private QrRenderer() {}
}
