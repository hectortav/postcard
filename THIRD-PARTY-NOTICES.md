# Third-party notices

postcard is distributed under the MIT licence (see `LICENSE`). The installers bundle the
components below. This file exists because the installers redistribute a complete Java runtime
and a complete Chromium, both of which carry obligations that nothing in the repository
previously acknowledged.

Two of these are the large ones and deserve reading before a public release.

## Bundled runtimes

### Eclipse Temurin (OpenJDK) — GPLv2 with the Classpath Exception

Every installer contains a Java runtime produced by `jpackage` from the JDK that built it.
GPLv2-with-Classpath-Exception permits linking without the application itself becoming GPL,
which is why postcard can stay MIT, but redistributing the runtime carries a source-availability
obligation for the runtime itself. Source: <https://github.com/adoptium/temurin-build>.

### Chromium Embedded Framework, via jcefmaven — BSD 3-Clause (CEF) over Chromium's own licences

The Windows and Linux installers bundle a full Chromium, roughly 300 MB, through
`me.friwi:jcefmaven`. Chromium is BSD 3-Clause plus several hundred component licences shipped
in its own `LICENSES/` tree. The built macOS app contains
`Chromium Embedded Framework.framework` with its licence files inside it; on the other
platforms they sit alongside the natives in `jcef-bundle/`.

- CEF: <https://bitbucket.org/chromiumembedded/cef>
- jcefmaven: <https://github.com/jcefmaven/jcefmaven>
- Chromium: <https://chromium.googlesource.com/chromium/src/+/main/LICENSE>

macOS does not use this at the default settings: the dashboard renders in the system
WKWebView, which is part of the operating system and bundles nothing.

## Server dependencies (in the shadow JAR)

| Component                             | Licence                                                                           |
| ------------------------------------- | --------------------------------------------------------------------------------- |
| Javalin                               | Apache-2.0                                                                        |
| Eclipse Jetty (via Javalin)           | Apache-2.0 or EPL-2.0, at your option                                             |
| Jackson (core, databind, annotations) | Apache-2.0                                                                        |
| picocli                               | Apache-2.0                                                                        |
| ZXing (core, javase)                  | Apache-2.0                                                                        |
| SLF4J                                 | MIT                                                                               |
| Logback                               | EPL-1.0 or LGPL-2.1, at your option. postcard redistributes it under **EPL-1.0**. |
| Kotlin stdlib (via Javalin)           | Apache-2.0                                                                        |

## Dashboard dependencies (in the embedded web bundle)

| Component                     | Licence |
| ----------------------------- | ------- |
| Preact                        | MIT     |
| @noble/ciphers, @noble/hashes | MIT     |
| StyleX                        | MIT     |
| qrcode                        | MIT     |

## Fonts

None. Both the dashboard and the site use system font stacks only, so there is no font
licensing to carry.

## Regenerating this file

It is maintained by hand. `./gradlew dependencies --configuration runtimeClasspath` and
`pnpm licenses list --prod` list what is actually shipped.
