package io.sendme;

import io.sendme.server.SendmeOptions;
import io.sendme.server.Server;
import picocli.CommandLine;

public final class Main {
    public static void main(String[] args) {
        var opts = new SendmeOptions();
        var cl = new CommandLine(opts);
        if (cl.execute(args) != 0) System.exit(1);
        var app = new Server(opts).build();
        app.start("0.0.0.0", parsePortOrZero(opts.port));
    }
    private static int parsePortOrZero(String p) { return p.equalsIgnoreCase("auto") ? 0 : Integer.parseInt(p); }
}
