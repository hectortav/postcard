package io.postcard.server;

import picocli.CommandLine;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * The command line.
 *
 * <p>The help and version options are declared explicitly rather than through
 * {@code mixinStandardHelpOptions}. That flag looks like it should work, but picocli
 * <em>silently</em> drops the entire standard mixin when one of its short names is already
 * taken -- and {@code -h} here belongs to {@code --host}. The result was a binary with no
 * {@code --help} and no {@code --version} at all, both of which the README documents, failing
 * with "Unknown option" and exit 2. Declaring them by hand keeps {@code -h} on {@code --host}
 * and makes the two options real.
 */
@CommandLine.Command(name = "postcard", versionProvider = Version.Provider.class,
  description = "Local file-sharing CLI + web UI",
  sortOptions = false,
  exitCodeListHeading = "%nExit codes:%n",
  exitCodeList = {
    "0:Normal shutdown, or --help / --version",
    "1:Unexpected error",
    "2:Bad command line",
    "3:The address and port could not be bound",
    "4:No LAN address to serve on",
    "5:--pin could not be armed"
  })
public class PostcardOptions implements Callable<Integer> {
    @CommandLine.Option(names = "--help", usageHelp = true, description = "Show this help and exit")
    public boolean helpRequested;

    @CommandLine.Option(names = {"-V", "--version"}, versionHelp = true,
      description = "Print the version and exit")
    public boolean versionRequested;

    @CommandLine.Option(names = {"-p", "--port"}, defaultValue = "8080", description = "Port to bind (default 8080; 'auto' → :0)")
    public String port = "8080";

    @CommandLine.Option(names = {"-h", "--host"}, description = "Bind address (default: auto-detected primary LAN IPv4)")
    public String host;

    @CommandLine.Option(names = {"-d", "--path"}, description = "Directory to share (default: fresh temp dir)")
    public Path path;

    @CommandLine.Option(names = {"-e", "--encrypt"}, description = "Generate a 256-bit AES key; URL hash carries it")
    public boolean encrypt;

    @CommandLine.Option(names = "--encrypt-owner",
      description = "Also encrypt downloads for the host itself. Off by default: the host already "
        + "has the plaintext on disk and its own requests never leave the machine, so the "
        + "dashboard downloads directly and keeps resume support.")
    public boolean encryptOwner;

    @CommandLine.Option(names = "--no-browser", description = "Do not auto-open the default browser on bind")
    public boolean noBrowser;

    @CommandLine.Option(names = "--headless", description = "Run as a background daemon: no system tray, no auto-browser. Use for headless servers, NAS, or SSH sessions.")
    public boolean headless;

    @CommandLine.Option(names = "--max-upload", description = "Optional upload-size cap in MiB")
    public Long maxUploadMiB;

    @CommandLine.Option(names = "--auth-token", defaultValue = "${env:POSTCARD_AUTH_TOKEN}",
      description = "Optional WS handshake secret. Prefer the POSTCARD_AUTH_TOKEN environment "
        + "variable: a command-line value is visible to every user on the machine via `ps`.")
    public String authToken;

    @CommandLine.Option(names = "--log-level", defaultValue = "${env:POSTCARD_LOG_LEVEL}",
      description = "How much to print: error, warn, info (default), debug or trace.")
    public String logLevel;

    @CommandLine.Option(names = {"-q", "--quiet"},
      description = "Only print warnings and errors. The URL, QR code and PIN still print.")
    public boolean quiet;

    @CommandLine.Option(names = {"--pin"}, arity = "0..1", fallbackValue = "",
      defaultValue = "${env:POSTCARD_PIN}",
      description = "Require a 4-digit PIN to access files. Auto-generates one if no value is "
        + "given. The PIN is mixed into the AES-256 key derivation and printed to stdout. "
        + "A PIN passed here is visible to every user on the machine via `ps`; set POSTCARD_PIN "
        + "instead, or pass --pin with no value and read the generated one from stdout.")
    public String pin;

    /**
     * The logback root level this command line asks for.
     *
     * <p>Applied by {@code Main} before anything logs, because logback reads the property when
     * the first logger is created and ignores changes afterwards.
     */
    public String resolvedLogLevel() {
        if (logLevel != null && !logLevel.isBlank()) {
            String v = logLevel.trim().toUpperCase(java.util.Locale.ROOT);
            return switch (v) {
                case "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "OFF" -> v;
                default -> "INFO";
            };
        }
        return quiet ? "WARN" : "INFO";
    }

    @Override public Integer call() { return 0; }
}
