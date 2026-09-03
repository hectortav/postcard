package io.sendme.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Picks the primary RFC1918 IPv4 address for the local host.
 *
 * <p>Filtering (design spec §6.3): reject any interface that is loopback,
 * virtual, point-to-point, or whose name matches the hotspot / tunnel /
 * container / VM-host reject regex. Of the survivors, collect IPv4
 * addresses and prefer {@code 192.168/16} &gt; {@code 10/8} &gt;
 * {@code 172.16/12}. Returns {@code null} if no acceptable address is
 * found, in which case the caller falls back to the hotspot launcher.
 *
 * <p>Why the reject list exists: {@code docker*} / {@code br-*} / {@code veth*}
 * are Linux container plumbing; {@code utun*} / {@code tun\d} / {@code tap\d}
 * are point-to-point tunnels (already rejected by the {@code isPointToPoint}
 * check, but the name belt-and-braces the filter); {@code awdl*} / {@code llw*}
 * are Apple Wireless Direct Link / Low-Latency WLAN (unroutable from another
 * device); {@code vmnet*} / {@code vboxnet*} are VMware / VirtualBox
 * host-only adapters that would trap the user in a single-host LAN;
 * {@code ham*} is a ham-radio tunnel that some BSDs / Linux systems expose.
 */
public final class NetworkInterfaceSelector {

    /**
     * Hotspot / container / VM-host reject regex from design spec §6.3.
     * Case-insensitive; matches anywhere in the name.
     */
    private static final Pattern REJECT = Pattern.compile(
            "(?i).*(docker|br-|veth|utun|tun\\d|tap\\d|awdl|llw|vmnet|vboxnet|ham).*");

    private NetworkInterfaceSelector() {}

    /**
     * True iff {@code name} is non-null and does NOT match the reject regex.
     * Exposed as a public static so the filter can be unit-tested against
     * the name without instantiating a real {@link NetworkInterface}.
     */
    public static boolean isAcceptableName(String name) {
        return name != null && !REJECT.matcher(name).matches();
    }

    /**
     * True iff {@code ni} is a real, routable, non-virtual, non-loopback
     * interface whose name does not match the reject list.
     */
    public static boolean isAcceptable(NetworkInterface ni) throws java.net.SocketException {
        return !ni.isLoopback()
                && !ni.isVirtual()
                && !ni.isPointToPoint()
                && isAcceptableName(ni.getName());
    }

    /**
     * Returns the highest-preference RFC1918 IPv4 address on this host,
     * or {@code null} if no acceptable interface is up. Preference order:
     * {@code 192.168/16} &gt; {@code 10/8} &gt; {@code 172.16/12}.
     */
    public static Inet4Address selectPrimary() throws java.net.SocketException {
        Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
        if (en == null) return null;
        List<Inet4Address> a192 = new ArrayList<>();
        List<Inet4Address> a10  = new ArrayList<>();
        List<Inet4Address> a172 = new ArrayList<>();
        while (en.hasMoreElements()) {
            NetworkInterface ni = en.nextElement();
            if (!isAcceptable(ni)) continue;
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (!(a instanceof Inet4Address ip)) continue;
                byte[] b = ip.getAddress();
                if ((b[0] == (byte) 192 && b[1] == (byte) 168)) a192.add(ip);
                else if (b[0] == 10) a10.add(ip);
                else if (b[0] == (byte) 172 && (b[1] & 0xF0) == 16) a172.add(ip);
            }
        }
        if (!a192.isEmpty()) return a192.get(0);
        if (!a10.isEmpty())  return a10.get(0);
        if (!a172.isEmpty()) return a172.get(0);
        return null;
    }
}
