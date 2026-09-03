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
| `--max-upload <MiB>` | unbounded | pre-disk enforcement |
| `--auth-token` | none | optional WS handshake secret |

See [`docs/superpowers/specs/2026-09-03-sendme-design.md`](docs/superpowers/specs/2026-09-03-sendme-design.md)
for the design spec and [`docs/superpowers/plans/2026-09-03-sendme-impl.md`](docs/superpowers/plans/2026-09-03-sendme-impl.md)
for the implementation plan.
