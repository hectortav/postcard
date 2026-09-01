package io.postcard.server;

import picocli.CommandLine;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "postcard", mixinStandardHelpOptions = true, version = "postcard 0.1.0",
  description = "Local file-sharing CLI + web UI")
public class PostcardOptions implements Callable<Integer> {
    @CommandLine.Option(names = {"-p", "--port"}, defaultValue = "8080", description = "Port to bind (default 8080; 'auto' → :0)")
    public String port = "8080";

    @CommandLine.Option(names = {"-h", "--host"}, description = "Bind address (default: auto-detected primary LAN IPv4)")
    public String host;

    @CommandLine.Option(names = {"-d", "--path"}, description = "Directory to share (default: fresh temp dir)")
    public Path path;

    @CommandLine.Option(names = {"-e", "--encrypt"}, description = "Generate a 256-bit AES key; URL hash carries it")
    public boolean encrypt;

    @CommandLine.Option(names = "--no-browser", description = "Do not auto-open the default browser on bind")
    public boolean noBrowser;

    @CommandLine.Option(names = "--headless", description = "Run as a background daemon: no system tray, no auto-browser. Use for headless servers, NAS, or SSH sessions.")
    public boolean headless;

    @CommandLine.Option(names = "--max-upload", description = "Optional upload-size cap in MiB")
    public Long maxUploadMiB;

    @CommandLine.Option(names = "--auth-token", description = "Optional WS handshake secret")
    public String authToken;

    @CommandLine.Option(names = {"--pin"}, description = "Require a 4-digit PIN to access files. Auto-generates one if no value is given. The PIN is mixed into the AES-256 key derivation and printed to stdout.")
    public String pin;

    @Override public Integer call() { return 0; }
}
