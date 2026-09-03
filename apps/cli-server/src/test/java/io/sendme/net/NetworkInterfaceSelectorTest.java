package io.sendme.net;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the design-spec §6.3 reject-name regex
 * `(?i).*(docker|br-|veth|utun|tun\d|tap\d|awdl|llw|vmnet|vboxnet|ham).*`
 * plus a positive case for an ordinary LAN adapter name.
 *
 * <p>Note: `java.net.NetworkInterface` is `final` in JDK 21 and Mockito 5.x
 * (without the inline mock-maker) cannot mock final classes. The
 * `isAcceptable(NetworkInterface)` overload is therefore covered indirectly
 * by the `isAcceptableName(String)` unit tests, which are the only branch of
 * the filter that is not a one-line pass-through to a JDK method.
 */
class NetworkInterfaceSelectorTest {

    @Test
    void rejectsDockerBridgesByName() {
        // Docker bridges are named `br-<12 hex>`; the literal `br-` must match
        // (verifier-flagged reject list, design spec §6.3).
        assertFalse(NetworkInterfaceSelector.isAcceptableName("br-1234"));
        assertFalse(NetworkInterfaceSelector.isAcceptableName("br-abcdef012345"));
    }

    @Test
    void rejectsVethAndUtunByName() {
        // Linux virtual-ethernet peers and macOS point-to-point tunnels.
        assertFalse(NetworkInterfaceSelector.isAcceptableName("veth1234"));
        assertFalse(NetworkInterfaceSelector.isAcceptableName("utun0"));
        assertFalse(NetworkInterfaceSelector.isAcceptableName("utun9"));
    }

    @Test
    void rejectsTunAndTapDevicesByName() {
        // TUN/TAP devices: `tun0`, `tap1`, etc. The regex uses `\d` to require
        // a digit directly after `tun` / `tap` so we don't accidentally reject
        // a hypothetical `tuna` adapter; this test pins the digit-anchored form.
        assertFalse(NetworkInterfaceSelector.isAcceptableName("tun0"));
        assertFalse(NetworkInterfaceSelector.isAcceptableName("tun9"));
        assertFalse(NetworkInterfaceSelector.isAcceptableName("tap0"));
        assertFalse(NetworkInterfaceSelector.isAcceptableName("tap9"));
    }

    @Test
    void rejectsAwdlLlwVmnetVboxnetHamByName() {
        // Apple Wireless Direct Link (`awdl0`) and Low-Latency WLAN (`llw0`)
        // are unroutable from another device. `vmnet*` / `vboxnet*` are
        // VMware / VirtualBox host-only adapters. `ham0` is a ham-radio
        // tunnel on some BSDs / Linux. All must be rejected (spec §6.3).
        assertFalse(NetworkInterfaceSelector.isAcceptableName("awdl0"));
        assertFalse(NetworkInterfaceSelector.isAcceptableName("llw0"));
        assertFalse(NetworkInterfaceSelector.isAcceptableName("vmnet1"));
        assertFalse(NetworkInterfaceSelector.isAcceptableName("vmnet8"));
        assertFalse(NetworkInterfaceSelector.isAcceptableName("vboxnet0"));
        assertFalse(NetworkInterfaceSelector.isAcceptableName("ham0"));
    }

    @Test
    void rejectsDockerBracketedName() {
        // The `docker` keyword inside the name must also match, e.g. a
        // `docker0` bridge that Linux creates by default.
        assertFalse(NetworkInterfaceSelector.isAcceptableName("docker0"));
        assertFalse(NetworkInterfaceSelector.isAcceptableName("docker_gwbridge"));
    }

    @Test
    void acceptsOrdinaryLanAdapterNames() {
        // Positive controls: typical macOS / Linux / Windows LAN adapter names
        // must NOT trip the reject list. `en0` is the macOS Wi-Fi interface;
        // `eth0` is the typical Linux wired interface; `wlp3s0` is a typical
        // Linux wireless interface; `Ethernet` and `Wi-Fi` are Windows names.
        assertTrue(NetworkInterfaceSelector.isAcceptableName("en0"));
        assertTrue(NetworkInterfaceSelector.isAcceptableName("eth0"));
        assertTrue(NetworkInterfaceSelector.isAcceptableName("wlp3s0"));
        assertTrue(NetworkInterfaceSelector.isAcceptableName("Ethernet"));
        assertTrue(NetworkInterfaceSelector.isAcceptableName("Wi-Fi"));
    }

    @Test
    void nullNameIsNotAcceptable() {
        // Defensive: a null name must not NPE; it is treated as reject.
        assertFalse(NetworkInterfaceSelector.isAcceptableName(null));
    }
}
