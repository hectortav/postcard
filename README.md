# sendme

Local file-sharing CLI + web UI. Drop files in the browser, share the printed URL,
let other devices on the LAN (or your hotspot) download — all in-process, no cloud,
no accounts.

The marketing site lives at [`apps/landing`](apps/landing) and is published to
GitHub Pages at <https://index-zr0.github.io/sendme/>.

## Quick start

```bash
# build a fat JAR (with the web bundle embedded)
pnpm install
pnpm turbo run build
cd apps/cli-server && ./gradlew shadowJar

# run it
java -jar build/libs/sendme-cli-server-0.1.0-all.jar

# or with encryption
java -jar build/libs/sendme-cli-server-0.1.0-all.jar --encrypt
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
| `--no-browser` | off | skip `Desktop.browse` |
| `--headless` | off | daemon mode: no tray icon, no auto-browser |
| `--max-upload <MiB>` | unbounded | pre-disk enforcement |
| `--auth-token` | none | optional WS handshake secret |

See [`docs/superpowers/specs/2026-09-03-sendme-design.md`](docs/superpowers/specs/2026-09-03-sendme-design.md)
for the design spec and [`docs/superpowers/plans/2026-09-03-sendme-impl.md`](docs/superpowers/plans/2026-09-03-sendme-impl.md)
for the implementation plan.

## Building installers

`apps/cli-server` exposes four `Exec` tasks that wrap the JDK 21 toolchain's
`jpackage` to produce platform-native installers. The shadow JAR + bundled JRE
are emitted first; the three platform tasks then wrap that into an installer.

```bash
cd apps/cli-server

# Self-contained app image (works on any host).
./gradlew appImage
# -> build/dist/sendme.app/  (macOS)   build/dist/sendme/  (Linux / Windows)

# Platform installers. Each is `enabled = false` on the wrong OS, so calling
# jpackageMsi on a Mac just prints `Task :jpackageMsi SKIPPED` and exits 0.
./gradlew jpackageDmg   # macOS  -> build/dist/installer/sendme-<appVersion>.dmg
./gradlew jpackageMsi   # Win    -> build/dist/installer/sendme-<appVersion>.msi
./gradlew jpackageDeb   # Linux  -> build/dist/installer/sendme_<appVersion>_amd64.deb
```

`<appVersion>` is `version` from `build.gradle.kts`, except when it starts with
`0.` (pre-1.0) — `jpackage` rejects a leading-zero first number on the macOS
bundler, so the task pins the bundle to `1.0` while the project version stays
at `0.x` for marketing.

Notes:
- The `.dmg` / `.msi` are **unsigned**; first-launch Gatekeeper / SmartScreen
  warnings are expected. Code signing is a v0.2+ concern.
- The bundled JRE is the full JDK 21 runtime (~170 MB on disk before DMG
  compression). A `jlink`-stripped runtime is a future enhancement.
- Cross-platform CI builds (run each platform's task on its own runner) are
  Phase 10.
