package io.sendme.net;
import java.net.Inet4Address;
public final class HotspotLauncher {
    public record Result(Inet4Address interfaceIp, HotspotInstructions instructions) {}
    public static Result attempt() { return new Result(null, new HotspotInstructions("stub")); }
    private HotspotLauncher() {}
}
