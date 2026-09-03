package io.sendme.net;

import java.io.IOException;
import java.net.Inet4Address;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class HotspotLauncher {
    public record Result(Inet4Address interfaceIp, HotspotInstructions instructions) {}
    private static final String ALPHA = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RNG = new SecureRandom();

    public static Result attempt() { return attempt(HotspotLauncher::runProcess); }

    static Result attempt(Function<String[], Process> runner) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) return null;
        if (!nmcliPresent(runner)) return new Result(null, new HotspotInstructions("nmcli not present. See §9 of the design spec for manual steps."));
        String ssid = "sendme-" + random(6);
        String pw = random(12);
        var iface = pickInterface();
        if (iface == null) return new Result(null, new HotspotInstructions("No usable Wi-Fi interface found."));
        var cmd = new String[]{"sudo", "-n", "nmcli", "device", "wifi", "hotspot", "ifname", iface, "ssid", ssid, "password", pw};
        Process p = runner.apply(cmd);
        boolean ok;
        try { ok = p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0; } catch (Exception e) { return new Result(null, new HotspotInstructions("Hotspot creation failed: " + e.getMessage())); }
        if (!ok) return new Result(null, new HotspotInstructions("Hotspot creation failed. See §9."));
        Inet4Address ip;
        try { ip = NetworkInterfaceSelector.selectPrimary(); }
        catch (java.net.SocketException e) { ip = null; }
        var note = new HotspotInstructions(String.format("Hotspot %s created. To tear down later: sudo nmcli connection down %s", ssid, ssid));
        return new Result(ip, note);
    }

    private static boolean nmcliPresent(Function<String[], Process> runner) {
        try { Process p = runner.apply(new String[]{"which", "nmcli"}); return p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0; }
        catch (Exception e) { return false; }
    }
    private static String pickInterface() {
        try {
            var en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                var ni = en.nextElement();
                if (ni.isUp() && !ni.isLoopback() && !ni.isVirtual() && ni.getName().startsWith("wl")) return ni.getName();
            }
        } catch (Exception ignored) {}
        return "wlan0";
    }
    private static String random(int n) { var sb = new StringBuilder(); for (int i = 0; i < n; i++) sb.append(ALPHA.charAt(RNG.nextInt(ALPHA.length()))); return sb.toString(); }
    private static Process runProcess(String[] cmd) { try { return new ProcessBuilder(cmd).redirectErrorStream(true).start(); } catch (IOException e) { throw new RuntimeException(e); } }
    private HotspotLauncher() {}
}
