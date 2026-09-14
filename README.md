# postcard

Local file-sharing CLI + web UI. Drop files in the browser, share the printed URL,
let other devices on the LAN (or your hotspot) download — all in-process, no cloud,
no accounts.

The marketing site lives at [`apps/landing`](apps/landing) and is published to
GitHub Pages when a `v*.*.*` tag is pushed (see [Release workflow](#release-workflow)).

## Quick start

```bash
# build a fat JAR (with the web bundle embedded)
pnpm install
pnpm turbo run build
cd apps/cli-server && ./gradlew shadowJar

# run it
java -jar build/libs/postcard-cli-server-0.1.0-all.jar

# or with encryption
java -jar build/libs/postcard-cli-server-0.1.0-all.jar --encrypt
```

The server prints a URL and a QR code. Point a phone at the QR, drop files in
the page, copy the link.

## Flags

| Flag                   | Default   | Notes                                                                                                                                                                                                                                                                                                                                                                                   |
| ---------------------- | --------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `-p, --port`           | 8080      | `auto` for :0 fallback                                                                                                                                                                                                                                                                                                                                                                  |
| `-h, --host`           | auto      | bind override                                                                                                                                                                                                                                                                                                                                                                           |
| `-d, --path`           | temp dir  | directory to share                                                                                                                                                                                                                                                                                                                                                                      |
| `-e, --encrypt`        | off       | AES-256-GCM, key in the URL fragment. Receivers decrypt in the browser; see [Encryption](#encryption).                                                                                                                                                                                                                                                                                  |
| `--encrypt-owner`      | off       | Encrypt the host's own downloads too. By default the host is served plaintext: it already has the file on disk, its own requests never reach the network, and this keeps resume support for the one device likely to hold large files.                                                                                                                                                  |
| `--pin [code]`         | off       | 4-digit PIN gates `/api/files` and `/api/download/{id}`. Auto-generates a PIN if no value is given; the PIN mixes into the AES-256 key derivation (PBKDF2-HMAC-SHA256, 200k iter). 3 wrong attempts from the same IP lock that IP out for 15 min. The dashboard's PIN protection section can enable, change or disable the PIN at runtime (host computer only; receivers never see it). |
| `--no-browser`         | off       | start without opening the dashboard window (the tray can still open it, and no Chromium is loaded until it does)                                                                                                                                                                                                                                                                        |
| `--headless`           | off       | daemon mode: no tray icon, no auto-browser                                                                                                                                                                                                                                                                                                                                              |
| `--max-upload <MiB>`   | unbounded | Enforced by the server before a byte reaches disk.                                                                                                                                                                                                                                                                                                                                      |
| `--auth-token`         | none      | Optional WebSocket handshake secret. Prefer `POSTCARD_AUTH_TOKEN`: a command-line value is visible in `ps` to every user on the machine.                                                                                                                                                                                                                                                |
| `--help` / `--version` |           | Print and exit without starting a server.                                                                                                                                                                                                                                                                                                                                               |

The dashboard opens in a window postcard owns. On macOS that window is the system
WebKit view, which is part of the operating system and bundles nothing. On Windows
and Linux it is an embedded Chromium (JCEF) shipped inside the app, so a first
launch works with no network. Either way no external browser is launched. **Closing that
window quits postcard**, including any transfer a phone still has in flight.
Bundling Chromium is why the Windows and Linux installers are large. macOS uses the
system WebKit view for the window and only carries Chromium for the tray-opened
fallback, so its app image is about 377 MB before compression.

When a tray icon is available, postcard shows a desktop notification whenever
_another_ device uploads a file or downloads one of yours. Your own uploads and
downloads stay silent, and `--headless` disables notifications entirely.
`--headless` also skips the window and never initializes Chromium.

## Building installers

`apps/cli-server` exposes four `Exec` tasks that wrap the JDK 25 toolchain's
`jpackage` to produce platform-native installers. The shadow JAR + bundled JRE
are emitted first; the three platform tasks then wrap that into an installer.

```bash
cd apps/cli-server

# Self-contained app image (works on any host).
./gradlew appImage
# -> build/dist/postcard.app/  (macOS)   build/dist/postcard/  (Linux / Windows)

# Platform installers. Each is `enabled = false` on the wrong OS, so calling
# jpackageMsi on a Mac just prints `Task :jpackageMsi SKIPPED` and exits 0.
./gradlew jpackageDmg   # macOS  -> build/dist/installer/postcard-<appVersion>.dmg
./gradlew jpackageMsi   # Win    -> build/dist/installer/postcard-<appVersion>.msi
./gradlew jpackageDeb   # Linux  -> build/dist/installer/postcard_<appVersion>_amd64.deb
```

`<appVersion>` is `version` from `build.gradle.kts`, except when it starts with
`0.` (pre-1.0) — `jpackage` rejects a leading-zero first number on the macOS
bundler, so the task pins the bundle to `1.0` while the project version stays
at `0.x` for marketing.

Notes:

- The `.dmg` / `.msi` are **unsigned**; first-launch Gatekeeper / SmartScreen
  warnings are expected. Code signing is a v0.2+ concern.
- The bundled runtime is `jlink`-stripped to the modules postcard actually uses,
  which is about 53 MB on disk rather than the 123 MB of a whole JDK. If you add a
  dependency that needs another module, add it to `--add-modules` in
  `build.gradle.kts`; a missing one shows up as a `NoClassDefFoundError` at runtime,
  not at build time.

## Release workflow

[`.github/workflows/release.yml`](.github/workflows/release.yml) runs on every
`v*.*.*` tag push. It checks the tag against the version in
`apps/cli-server/build.gradle.kts`, builds the three platform installers, publishes
them as a GitHub Release with a `SHASUMS256.txt` beside them, and deploys the
landing page to GitHub Pages.

Cutting a release:

```bash
node scripts/bump-version.mjs 0.2.0   # every package.json plus build.gradle.kts
# update CHANGELOG.md under a "## [0.2.0]" heading, commit, then:
git tag v0.2.0 && git push origin v0.2.0
```

The release notes come from that CHANGELOG section when it exists, and from the
commit list otherwise.

## Debugging with Spotlight

[Spotlight](https://spotlightjs.com) is Sentry's local-only debugger: a sidecar on `:8969` that
collects errors, logs and traces from both halves of postcard and shows them in one place. Nothing
leaves the machine and no DSN exists anywhere in the tree, so it fits the same "no cloud" rule as
the rest of the app.

```bash
pnpm spotlight        # sidecar + UI on http://localhost:8969
```

**Browser.** `pnpm --filter @postcard/web dev` wires itself up automatically. The Sentry browser SDK
is a devDependency, initialised from `apps/web/src/dev/spotlight.ts` behind an
`import.meta.env.DEV` guard that Vite compiles to `false` in a production build — so Rollup drops
the branch and the chunk, and the bundle embedded in the JAR contains none of it. That is enforced,
not assumed: `pnpm --filter @postcard/web build` runs `scripts/assert-no-sentry-in-bundle.mjs`
against the emitted files and fails the build if any of it leaks (an unguarded import takes the
bundle from 55 kB to 203 kB, which is the regression the check exists to catch).

**Server.** Off by default and opt-in per run — the released binary never opens the socket:

```bash
POSTCARD_SPOTLIGHT=1 java -jar build/libs/postcard-cli-server-0.1.0-all.jar
./gradlew test -Ppostcard.spotlight=1      # test-run failures, same place
```

`POSTCARD_SPOTLIGHT` takes `1`/`true`/`yes`/`on`, or a full URL to point at a sidecar elsewhere;
anything else (including an empty value) means off. Delivery is async and best-effort, and gives up
after five consecutive failures, so a missing sidecar can never slow down or break a transfer.

postcard does **not** depend on sentry-java. `io.postcard.dev.SpotlightEnvelope` writes the Sentry
envelope format directly using the Jackson dependency that was already there — a telemetry SDK
inside the shipped JAR is a bigger promise to keep than dev-time debugging is worth. Reporting is
attached as a logback appender rather than a Javalin exception handler, which keeps it purely
additive: response codes and console output are unchanged, and anything the server already logs at
WARN or above is picked up, including the WebSocket and tray paths that never touch a route.

**Agents.** [`.mcp.json`](.mcp.json) registers the `sentry-spotlight` MCP server, giving Claude Code
`search_errors`, `search_logs`, `search_traces` and `get_traces` against the same buffer — so "what
just failed?" is answerable without pasting a stack trace.

> **Version pin.** The root `package.json` pins `hono` to `4.12.31` via `pnpm.overrides`. Spotlight
> 4.11.8's request middleware calls `ctx.req.query().toString()`, and hono `4.12.34+` changed
> `query()` to return a null-prototype object, which has no `toString`. The mismatch makes _every_
> sidecar route return 500 — the UI, envelope ingest and the MCP endpoint alike — while the process
> still looks healthy in `ps`. The MCP server is launched through `pnpm exec` rather than
> `npx @spotlightjs/spotlight@latest` for the same reason: npx resolves its own unpinned tree and
> would reintroduce the broken pairing. Revisit when Spotlight releases a fix.

## Encryption

With `--encrypt`, downloads are AES-256-GCM: a 12-byte nonce and a 16-byte tag per
64 KiB plaintext chunk, with the chunk index, a final-chunk flag and the file id
authenticated alongside so chunks cannot be reordered, truncated, or moved between
files. The key lives in the URL fragment and never reaches the server.

Receivers decrypt in the browser. Two consequences worth knowing:

- **No resume.** Each chunk carries its own nonce and tag, so a byte range is not
  independently decryptable and the server does not offer `Range` for an encrypted
  download. An interrupted transfer starts again from zero.
- **A memory ceiling.** postcard serves over plain HTTP on a local address, which
  browsers do not treat as a secure context, so the two APIs that stream a generated
  file to disk are unavailable: Service Workers and the File System Access API are
  both gated on a secure context, and the latter does not exist on iOS Safari at
  all. The file is therefore decrypted into memory. It is flushed to the browser in
  slabs so the tab is not holding all of it, which carries a large file comfortably
  on a laptop, but a phone will give up well before one does. The dashboard warns
  above 512 MiB. The host's own window is unaffected: it is served plaintext.

## Security

- **PIN** (`--pin`): mixed into the key with PBKDF2-HMAC-SHA256 at 200,000
  iterations, so knowing the URL is not enough to decrypt the stream. Verifying it
  authorizes that one client, not every device on the network.
- **Sharing the link shares the PIN.** `--pin` writes the PIN into the URL fragment
  so a QR code carries everything a receiver needs. If that matters, read the PIN
  from the terminal aloud instead of forwarding the link.
- **A PIN on the command line is visible in `ps`** to every user on the machine. Use
  `POSTCARD_PIN`, or pass `--pin` with no value and read the generated one from
  stdout.
- **Rate limit**: per-IP, 3 wrong PINs → 15-minute lockout (returns 429 with
  `lockoutMsRemaining` so the UI can show a countdown).
- **No WebCrypto, by necessity**: postcard serves from `http://<lan-ip>:<port>`,
  which browsers do not treat as a secure context, so `crypto.subtle` is
  `undefined` on every device that loads the page — including the host. All
  hashing, PBKDF2 and AES-GCM in the browser therefore come from `@noble/*`,
  never from SubtleCrypto. `apps/web/src/security/pin.test.ts` pins this with a
  test that runs the derivation with `crypto.subtle` removed, and with vectors
  generated from the Java side so the two implementations cannot drift apart.
  (Desktop notifications hit the same wall, which is why they are delivered
  through the tray icon rather than the Notification API.)
- **Coverage gate**: `io.postcard.security.*` is held to 100% by the `coverage` CI
  job, apart from `PinSecurityEngine`'s two unreachable `NoSuchAlgorithmException`
  catches. The Java bundle floor is 70% line and 60% branch, and the two web
  bundles are gated at 88% lines / 75% branches and 85% respectively. Those are
  the numbers CI enforces; they ratchet as coverage rises.

- **Same-origin only**: state-changing requests and WebSocket upgrades must present
  the dashboard's own origin, and the `Host` header must name the bind address, a
  loopback address, or an mDNS `.local` name.

See [`apps/cli-server/src/main/java/io/postcard/security/`](apps/cli-server/src/main/java/io/postcard/security/)
for the PIN implementation, and [SECURITY.md](SECURITY.md) for the threat model and
how to report a vulnerability.

## Exit codes

| Code | Meaning                                    |
| ---- | ------------------------------------------ |
| 0    | Normal shutdown, or `--help` / `--version` |
| 1    | Unexpected error                           |
| 2    | Bad command line                           |
| 3    | The address and port could not be bound    |
| 4    | No LAN address to serve on                 |
| 5    | `--pin` could not be armed                 |

## When there is no network

If no usable LAN address is found, postcard tries to bring up a hotspot (Linux only,
via `nmcli`) and serves from there; the QR code then carries the Wi-Fi credentials so
a phone can join by scanning. Elsewhere it exits with instructions and the suggestion
to name an address yourself with `--host`. postcard never tears a hotspot down for
you: it prints the command to do it.
