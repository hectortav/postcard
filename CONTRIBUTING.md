# Contributing

## Getting set up

You need Node 20.11 or newer (see `.nvmrc`), pnpm 9.7, and a JDK 25 toolchain. Gradle
downloads the JDK itself on first use.

```bash
pnpm install
pnpm turbo run build          # the web bundle is compiled into the server's resources
cd apps/cli-server && ./gradlew run --args="--path ~/Desktop"
```

## Checks

```bash
pnpm lint            # oxlint
pnpm format:check    # prettier
pnpm turbo run typecheck
pnpm turbo run test  # vitest, both front ends
cd apps/cli-server && ./gradlew test
```

The end-to-end suites need a running server, which the workflow starts and points at with
`POSTCARD_E2E_URL`. See the `e2e*` jobs in `.github/workflows/build.yml` for the exact flags
each one needs.

## Two rules worth knowing before you start

**The dashboard is one product served two ways.** `apps/web` renders both in the window
postcard owns and in a phone's browser. Nothing in it may branch on the user agent or the
address. When behaviour has to differ, the server decides and sends the answer as data, the
way `/api/session` reports whether this client's downloads are encrypted.

**Nothing leaves the machine.** No telemetry, no update checks, no analytics, no fonts or
scripts from a CDN. The web build fails if debug tooling reaches the production bundle
(`apps/web/scripts/assert-no-sentry-in-bundle.mjs`), and that check is there because a
regression once tripled the bundle size.

## One lint rule is off on purpose

`jsx-a11y/prefer-tag-over-role` is disabled in `.oxlintrc.json`. It fires in three places where
the native element is the wrong answer: the drop zone cannot be a `<button>` because it
contains an `<input type="file">` and interactive content inside a button is invalid HTML; the
PIN screen would have to call `showModal()` to be a real `<dialog>`; and `<progress>` cannot be
styled to match the rest of the sheet. All three carry the correct role and keyboard handling.
The rest of the accessibility rules are on and enforced.

## Two dependencies are deliberately held back

Dependabot will keep offering these. Both are migrations rather than bumps, and both need
their own change with their own testing.

- **Javalin 7** would move off Jetty 11, whose community support has ended, which is the
  reason to want it. It also removes the routing API this server is written against: every
  `app.get` / `app.post` / `app.before` / `app.ws` call moves, and so do the Jetty 11 handles
  used to configure multipart limits and the WebSocket factory.
- **StyleX 0.19** drops the default export and changes how styles are applied, so
  `import stylex from` and every `stylex(styles.x)` call site across both front ends changes
  shape, and the result needs looking at rather than just building.

## Commits

One line, `type: summary`, from feat, fix, docs, style, refactor, test, chore. Keep
implementation and its tests in separate commits.
