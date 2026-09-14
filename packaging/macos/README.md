# macOS packaging

## Signing and notarization

The installers are unsigned unless a signing identity is configured, and an unsigned,
un-notarized app is quarantined by Gatekeeper on first launch: most users see "postcard is
damaged and can't be opened" rather than a bypass prompt.

To sign locally, put a Developer ID Application certificate in your keychain and pass:

```bash
./gradlew jpackageDmg \
  -Ppostcard.macSigningIdentity="Developer ID Application: Your Name (TEAMID)"
```

Then notarize and staple:

```bash
xcrun notarytool submit build/dist/installer/postcard-0.1.0.dmg \
  --apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" --password "$APPLE_APP_PASSWORD" --wait
xcrun stapler staple build/dist/installer/postcard-0.1.0.dmg
spctl -a -vv -t install build/dist/installer/postcard-0.1.0.dmg   # expect: accepted
```

In CI the same identity comes from repository secrets; see the `installer` job in
`.github/workflows/release.yml`. Without `MACOS_CERTIFICATE`, every signing step is skipped
and the build still produces an unsigned installer.

## What has to be signed

`jpackage` signs the app bundle, but not everything inside it. These need signing first, with
`--options runtime --timestamp`, deepest first:

- `Contents/app/webview-natives/libpostcard-webview.dylib`
- `Contents/app/jcef-bundle/**` on the builds that bundle Chromium (not the default macOS
  path, which uses the system WebKit view)

`jpackageDmg` does this for you when a signing identity is set.

## Info.plist

`jpackage` generates a boilerplate `Info.plist` claiming postcard wants microphone access, with
an `LSMinimumSystemVersion` of 10.11 that no JDK 25 app can honour, and the shifted bundle
version rather than the real one. The `appImage` task corrects all of that afterwards with
PlistBuddy.

It is done that way rather than with a template passed through `--resource-dir`, which is the
documented route: supplying a custom `Info.plist` makes the later `--app-image` dmg step fail
with `app-image-requires-identifier`, because jpackage reads part of the app's identity back
out of the plist it generated itself.
