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

| Flag | Default | Notes |
|---|---|---|
| `-p, --port` | 8080 | `auto` for :0 fallback |
| `-h, --host` | auto | bind override |
| `-d, --path` | temp dir | directory to share |
| `-e, --encrypt` | off | AES-256-GCM, key in URL hash |
| `--pin [code]` | off | 4-digit PIN gates `/api/files` and `/api/download/{id}`. Auto-generates a PIN if no value is given; the PIN mixes into the AES-256 key derivation (PBKDF2-HMAC-SHA256, 200k iter). 3 wrong attempts from the same IP lock that IP out for 15 min. The dashboard's PIN protection section can enable, change or disable the PIN at runtime (host computer only; receivers never see it). |
| `--no-browser` | off | start without opening the dashboard window (the tray can still open it, and no Chromium is loaded until it does) |
| `--headless` | off | daemon mode: no tray icon, no auto-browser |
| `--max-upload <MiB>` | unbounded | pre-disk enforcement |
| `--auth-token` | none | optional WS handshake secret |

The dashboard opens in a window postcard owns, rendered by an embedded Chromium
(JCEF) bundled inside the app — no external browser is launched, and the natives
ship in the installer so a first launch works with no network. **Closing that
window quits postcard**, including any transfer a phone still has in flight.
Bundling Chromium is why the installers are large (the macOS app image is ~458 MB
before compression).

When a tray icon is available, postcard shows a desktop notification whenever
*another* device uploads a file or downloads one of yours. Your own uploads and
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
- The bundled JRE is the full JDK 25 runtime (~170 MB on disk before DMG
  compression). A `jlink`-stripped runtime is a future enhancement.

## Release workflow

[`.github/workflows/release.yml`](.github/workflows/release.yml) builds the
platform installers on every `v*.*.*` tag push and uploads them as
`postcard-installer-{ubuntu,macos,windows}` artifacts. The GitHub Pages
deploy step is scaffolded (commented out) at the bottom of the file —
uncomment it when the first hand-cut tag is ready and the landing page
should go live alongside the installers.

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
> `query()` to return a null-prototype object, which has no `toString`. The mismatch makes *every*
> sidecar route return 500 — the UI, envelope ingest and the MCP endpoint alike — while the process
> still looks healthy in `ps`. The MCP server is launched through `pnpm exec` rather than
> `npx @spotlightjs/spotlight@latest` for the same reason: npx resolves its own unpinned tree and
> would reintroduce the broken pairing. Revisit when Spotlight releases a fix.

## Security

- **Encryption** (`--encrypt` / `--pin`): AES-256-GCM, 12-byte nonce + 16-byte
  tag per 64 KiB plaintext chunk. Key in URL hash, never on the wire. The
  PIN is mixed into the key via PBKDF2-HMAC-SHA256 (200k iterations) so
  knowing the URL alone is insufficient to decrypt the file stream.
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
- **Coverage gate**: new `io.postcard.security.*` code is held to 100% line +
  branch coverage by the `coverage` CI job; the rest of the repo is held
  to 90% (JaCoCo + Vitest thresholds).

See [`apps/cli-server/src/main/java/io/postcard/security/`](apps/cli-server/src/main/java/io/postcard/security/)
for the PIN-security implementation.
