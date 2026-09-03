package io.sendme.server;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import java.util.concurrent.Executors;

public final class Server {
    public static final java.util.concurrent.ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();
    private final SendmeOptions opts;
    public Server(SendmeOptions opts) { this.opts = opts; }

    public Javalin build() {
        var app = Javalin.create(cfg -> {
            cfg.staticFiles.add(s -> {
                s.hostedPath = "/";
                s.directory = "/public";
                s.location = Location.CLASSPATH;
            });
        });
        app.get("/", ctx -> ctx.contentType("text/html").result("<!doctype html>sendme</html>"));
        return app;
    }
}
