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

`jpackage` generates a boilerplate `Info.plist` that includes an `NSMicrophoneUsageDescription`
claiming postcard wants the microphone, and an `LSMinimumSystemVersion` of 10.11, which cannot
be right for a JDK 25 app. `Info.plist` in this directory replaces both. jpackage
picks it up through `--resource-dir`.
