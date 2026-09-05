package io.postcard.desktop;

import java.net.URI;

/**
 * Decides whether a navigation inside the dashboard stays in-app or opens externally.
 *
 * <p>Split out from the native navigation delegate so the rule is unit-testable: the
 * delegate (ObjC, JNI) only asks this over the bridge. Same-origin navigations — scheme,
 * host and port matching the dashboard's own {@code BIND} URL — stay in the window;
 * everything else opens in the user's real browser rather than replacing the dashboard.
 *
 * <p>Unparseable targets (including {@code about:blank}) stay in-app and let the engine
 * fail them harmlessly, which is safer than handing garbage to the OS browser.
 */
public final class WebviewPolicy {
    public enum Decision { SHOW_IN_APP, OPEN_EXTERNALLY }

    private WebviewPolicy() {}

    public static Decision decide(String navUrl, String dashboardBase) {
        Origin nav = originOf(navUrl);
        Origin base = originOf(dashboardBase);
        if (nav == null || base == null) return Decision.SHOW_IN_APP;
        return nav.equals(base) ? Decision.SHOW_IN_APP : Decision.OPEN_EXTERNALLY;
    }

    private static Origin originOf(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = new URI(raw.trim());
            if (uri.getScheme() == null || uri.getHost() == null) return null;
            return new Origin(uri.getScheme().toLowerCase(), uri.getHost().toLowerCase(), uri.getPort());
        } catch (Exception _) {
            return null;
        }
    }

    private record Origin(String scheme, String host, int port) {}
}
