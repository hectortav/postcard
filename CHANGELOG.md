# Changelog

All notable changes to postcard are recorded here, newest first. This project follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security

- Each client is now authorized separately. Verifying the PIN once used to open the file list
  and downloads for every device on the network, permanently, which also made the
  three-strike lockout meaningless.
- The WebSocket is gated on the PIN. It previously sent every filename, size and hash, plus
  the shared clipboard text, to anyone who opened a socket.
- State-changing requests and WebSocket upgrades must come from the dashboard's own origin,
  so a website the host visits can no longer disable the PIN or upload files.
- The `Host` header is checked, which closes a DNS-rebinding path to the file list.
- Responses carry a content security policy, `nosniff`, `DENY` framing and `no-referrer`, and
  downloads are always `application/octet-stream` so an uploaded page cannot run on the
  dashboard's origin.
- Encrypted chunks are bound to their file and position, so they cannot be reordered,
  truncated, or spliced in from another file.
- The AES key and PIN no longer appear in the log, the tray tooltip, or the macOS system log.
- `--max-upload` is enforced by the server before anything reaches disk, and upload spooling
  moved inside the shared directory so a crash cannot strand files in the system temp folder.
- `POSTCARD_PIN` and `POSTCARD_AUTH_TOKEN` avoid putting secrets in `ps`.
- Serving a lockout no longer costs the next attempt: the three tries come back.

### Added

- The browser decrypts downloads. `--encrypt` previously produced files receivers could not
  open, because no browser code ever decrypted them.
- `--encrypt-owner` encrypts the host's own downloads too. By default the host is served
  plaintext, since it already holds the file and its own requests never reach the network.
- Uploads show one row per file with progress, and can be cancelled.
- The dashboard says when it is not connected, and when clipboard text could not be sent.

### Fixed

- `--help` and `--version` exist and exit without starting a server.
- Files already in a `--path` directory can be downloaded. Any name containing a dot
  returned 400.
- WebSocket clients are no longer disconnected after 1,024 broadcasts.
- Quitting on macOS no longer crashes, and clicking the Dock icon raises the window.
- Range requests stream instead of buffering the range in memory, and no longer truncate
  above 2 GiB.
- The Windows installer is offered on the site. The download button looked for `.exe` while
  the build produces `.msi`, so it was dead on every release.
- The comparison table follows the viewport instead of freezing at its first width.
- Distinct releases produce distinctly named installers.

### Removed

- The GraalVM native target. It could not serve the dashboard, and its CI drift check
  compared a directory that did not exist.
