package io.sendme.server;

import picocli.CommandLine;
import java.nio.file.Path;

@CommandLine.Command(name = "sendme", mixinStandardHelpOptions = true, version = "sendme 0.1.0",
  description = "Local file-sharing CLI + web UI")
public class SendmeOptions {
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

    @CommandLine.Option(names = "--max-upload", description = "Optional upload-size cap in MiB")
    public Long maxUploadMiB;

    @CommandLine.Option(names = "--auth-token", description = "Optional WS handshake secret")
    public String authToken;
}
