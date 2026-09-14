# Security

## Reporting a vulnerability

Please report security issues through GitHub's private vulnerability reporting:
**[Security → Report a vulnerability](https://github.com/hectortav/postcard/security/advisories/new)**.
Do not open a public issue for anything exploitable.

Include what you did, what happened, and the version (`postcard --version`). A proof of
concept helps but is not required.

## What is supported

Only the most recent release. postcard has no auto-update, so fixes reach users when they
download a new installer.

## The threat model

postcard serves files over plain HTTP to whoever can reach it on the local network. That is
the product, not an oversight, and it shapes what these defences can and cannot do.

**Assumed:** the operator trusts the network enough to put a file on it, and can see the
terminal where the URL and PIN are printed.

**Defended against:**

- Another device on the network reading files without the PIN. The PIN gates the file list,
  downloads, uploads, the clipboard and the WebSocket, and each client is authorized
  separately, so one device unlocking does not unlock the rest.
- Guessing the PIN. Three wrong attempts lock that address out for fifteen minutes, and the
  PIN is stretched with PBKDF2-HMAC-SHA256 at 200,000 iterations.
- A website the host visits acting through their browser. State-changing requests and
  WebSocket upgrades must carry the dashboard's own origin, and the session cookie is
  `SameSite=Strict`.
- A name that resolves to postcard's address being used to read it (DNS rebinding). The
  `Host` header must name the bind address, a loopback address, or an mDNS `.local` name.
- Tampering with an encrypted transfer. Each chunk is AES-256-GCM with the chunk index, the
  final-chunk flag and the file id as associated data, so chunks cannot be reordered,
  truncated or moved between files.

**Not defended against:**

- Anyone who has the URL. The fragment carries the key, and when `--pin` puts the PIN in the
  link as well, sharing the link shares both. Read the PIN aloud instead if that matters.
- Someone who can read the host's screen or process list. Prefer `POSTCARD_PIN` over
  `--pin 1234`, which is visible in `ps` to every user on the machine.
- Passive observation of an unencrypted transfer. Without `--encrypt`, file contents cross
  the network in the clear.
- A malicious operator. Anyone who can run postcard can already read the directory it shares.

## Verifying a download

Each release publishes `SHASUMS256.txt`. Installers are not yet code-signed, so macOS
Gatekeeper and Windows SmartScreen will warn on first launch.
