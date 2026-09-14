# Releasing postcard

A release is a tag push. Everything else is automated, and the workflow refuses to build if
the tag and the project version disagree.

## Before you start

Decide the version. postcard follows semantic versioning, and is pre-1.0, so a breaking change
bumps the minor.

## Cutting it

```bash
node scripts/bump-version.mjs 0.2.0     # every package.json plus build.gradle.kts
$EDITOR CHANGELOG.md                    # move Unreleased entries under "## [0.2.0]"
git add -A && git commit -m "chore: release 0.2.0"
git tag v0.2.0
git push origin main
git push origin v0.2.0
```

The tag push runs `.github/workflows/release.yml`, which:

1. checks `v0.2.0` against the version in `apps/cli-server/build.gradle.kts` and stops if they
   differ;
2. builds `.dmg`, `.msi` and `.deb` on their own runners, signing and notarizing where
   certificates are configured;
3. publishes a GitHub Release with `SHASUMS256.txt` and the notes from the CHANGELOG section;
4. deploys the landing page to GitHub Pages.

## Afterwards

- `spctl -a -vv -t install postcard-0.2.0.dmg` should say **accepted**. A rejection means the
  notarization step was skipped, which happens when the signing secrets are absent.
- `signtool verify /pa postcard-0.2.0.msi` on Windows.
- Check the landing page offers all three downloads. If Windows says "not in this release",
  the asset name did not classify: see `classifyAsset` in `apps/landing/src/lib/fetchRelease.ts`.
- Download one installer and check the checksum against `SHASUMS256.txt`.

## One-time repository settings

These live in GitHub's settings, not in the repository, so they cannot be set from a commit.
None of them is optional for a public release.

- **Branch protection on `main`**, requiring `lint`, `typecheck`, `unit-test`, `coverage` and
  the `e2e*` jobs. Without it the coverage gate is advisory: a failing run does not stop a
  push. Settings → Branches → Add rule.
- **Dependabot alerts and security updates**, which are currently off. `.github/dependabot.yml`
  opens version-update pull requests, but alerts for known vulnerabilities are a separate
  switch. Settings → Code security.
- **Private vulnerability reporting**, which SECURITY.md points people at. Settings → Code
  security → Private vulnerability reporting.
- **Pages**, source set to GitHub Actions. Already enabled; the `pages` job fails without it.

## The signing secrets

Everything below is optional. Without them the build still produces installers, unsigned, and
users get a Gatekeeper or SmartScreen warning on first launch.

| Secret                                                | What it is                                      |
| ----------------------------------------------------- | ----------------------------------------------- |
| `MACOS_CERTIFICATE`                                   | Developer ID Application `.p12`, base64 encoded |
| `MACOS_CERTIFICATE_PASSWORD`                          | its password                                    |
| `MACOS_SIGNING_IDENTITY`                              | e.g. `Developer ID Application: Name (TEAMID)`  |
| `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD`     | notarization, using an app-specific password    |
| `WINDOWS_CERTIFICATE`, `WINDOWS_CERTIFICATE_PASSWORD` | Authenticode `.pfx`, base64 encoded             |

See [`packaging/macos/README.md`](../packaging/macos/README.md) for what has to be signed
inside the bundle and why.

## The manual smoke test

CI cannot drive a window. Before announcing a release, on macOS:

1. Install from the `.dmg` and launch from Finder.
2. The window opens and shows the dashboard.
3. Drag a file from Finder into it; it uploads.
4. Click a file; it downloads and a notification appears.
5. Close the window with the red button. The process exits, the log ends with `goodbye`, and
   no crash report appears in `~/Library/Logs/DiagnosticReports`.
6. Relaunch, minimize, then click the Dock icon. The window comes back.
7. Relaunch and press Cmd-Q. It exits cleanly.
8. Run `postcard --no-browser`, then use the tray's Open Dashboard.
9. Run `postcard --headless`: no window, no tray icon.
10. From a phone on the same network, scan the QR and download something.

With `--encrypt --pin`, repeat step 10 and confirm the downloaded file opens: that is the path
that used to hand receivers a file they could not read.

## What a release does not do

There is no auto-update. A shipped installer keeps its bundled runtime and, on Windows and
Linux, its bundled Chromium until someone downloads a new one. That is deliberate, because an
update check is a network call home and postcard's entire claim is that it does not make one,
but it does mean security fixes travel only as fast as people re-download.
