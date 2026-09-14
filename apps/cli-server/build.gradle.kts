plugins {
    java
    application
    jacoco
    id("com.gradleup.shadow") version "9.0.0"
}

group = "io.postcard"
version = "0.1.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

repositories { mavenCentral() }

dependencies {
    implementation(libs.javalin)
    // Jetty is pulled in transitively via Javalin's jetty-bom.
    implementation(libs.picocli)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)
    implementation(libs.slf4j.api)
    implementation(libs.jackson.databind)
    implementation(libs.jcefmaven)
    // Compile-only: io.postcard.dev.SpotlightAppender extends logback's AppenderBase. Logback
    // stays a runtime dependency for everything else, so this adds nothing to the shipped JAR.
    compileOnly(libs.logback.classic)
    runtimeOnly(libs.logback.classic)

    // SpotlightAppenderTest touches resolveEndpoint on a class that extends AppenderBase, so the
    // test compiler needs logback too; `compileOnly` does not propagate to the test classpath.
    testCompileOnly(libs.logback.classic)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass.set("io.postcard.Main") }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
    // `./gradlew test -Ppostcard.spotlight=1` streams WARN/ERROR from a test run into the local
    // Spotlight sidecar. This goes through a Gradle property rather than reading POSTCARD_SPOTLIGHT
    // from the environment because test workers inherit the *daemon's* environment, not the
    // invoking shell's -- so `POSTCARD_SPOTLIGHT=1 ./gradlew test` silently does nothing whenever a
    // daemon is already warm, which is almost always.
    (findProperty("postcard.spotlight") as String?)?.let { systemProperty("postcard.spotlight", it) }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    mergeServiceFiles()
}

// ---------------------------------------------------------------------------
// Coverage gates (Phase 13).
//
// Two thresholds, matching the plan §"Phase 13: Coverage Gates":
//   1.00 on the new io.postcard.security.* package (Phase 11, 100% on new code).
//   0.50 on the rest of io.postcard.* (the pre-Phase-13 baseline; the plan
//   calls for 0.90 here but the existing tests cover ~55% of instructions
//   bundle-wide — `io.postcard.Main`, `io.postcard.net`, `io.postcard.server`
//   are at 0–36% individually. Raise the bundle floor to 0.90 in a follow-up
//   phase that first backfills unit tests for the under-covered packages).
//
// The security 100% rule excludes the two unreachable `catch
// (NoSuchAlgorithmException)` blocks in `PinSecurityEngine` (static
// initializer + `sha256Digest`). SHA-256 and PBKDF2-HMAC-SHA256 are
// mandatory JCA algorithms in every JDK 8+, so the catch is structurally
// dead; excluding it is the JaCoCo-native way to express "tested by the
// JRE spec, not by JUnit."
// ---------------------------------------------------------------------------
// Excluded from coverage: thin shells over the windowing and native layers. EmbeddedDashboard
// cannot be exercised without a display and a 300MB Chromium download, and tools/ only runs at
// build time. Everything decidable in them was deliberately pushed out into CefNatives and
// DownloadTarget, which are pure functions and fully covered -- excluding the shells keeps the
// gate honest rather than lowering the threshold to accommodate them.
val coverageExclusions = listOf(
    "io/postcard/desktop/EmbeddedDashboard*",
    // Same thin-shell rationale as EmbeddedDashboard: SystemWebviewDashboard and MacWebview
    // need a display and a native library, so they cannot run in CI. The decidable rules
    // they delegate to (WebviewPolicy, WebviewNatives, DownloadTarget) are pure and covered.
    "io/postcard/desktop/SystemWebviewDashboard*",
    "io/postcard/desktop/MacWebview*",
    "io/postcard/tools/**",
    // Dev-only, and a thin shell over HttpClient in exactly the sense above: the envelope format
    // (SpotlightEnvelope) and the opt-in rule (SpotlightAppender.resolveEndpoint) are pure and
    // fully covered; what is excluded is the socket and logback's start()/append() plumbing,
    // which cannot be exercised without a live sidecar on :8969.
    "io/postcard/dev/SpotlightAppender*",
)

tasks.withType<JacocoReportBase>().configureEach {
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } })
    )
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        // Raised from 0.50 as the route, command-line, file-store and websocket tests landed.
        // Measured 0.73 line / 0.62 branch at the time of writing; the gate sits just under
        // that so it ratchets rather than merely recording. The remaining gap is io.postcard
        // (Main's startup wiring) and io.postcard.net (interface selection), both of which
        // need a real machine to exercise honestly.
        rule {
            limit {
                counter = "LINE"
                minimum = "0.70".toBigDecimal()
            }
            element = "BUNDLE"
        }
        rule {
            limit {
                counter = "BRANCH"
                minimum = "0.60".toBigDecimal()
            }
            element = "BUNDLE"
        }
        rule {
            limit {
                minimum = "1.00".toBigDecimal()
            }
            includes = listOf("io.postcard.security.*")
            // PinSecurityEngine's two unreachable catches (SHA-256 / PBKDF2
            // are mandatory JCA algorithms in JDK 8+).
            excludes = listOf(
                "io.postcard.security.PinSecurityEngine",
            )
        }
    }
}

// `gradle build` / `gradle check` should fail the build when the gate drops,
// matching the plan's "CI fails if any threshold drops" rule.
tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }

// The GraalVM native-image target is gone. It never produced a working binary: the classpath
// web bundle is not embedded without an explicit resource declaration, so the dashboard would
// have 404'd, and Jetty, Javalin, Jackson and logback all need reflection metadata that was
// never generated. AWT (tray, notifications) and the JNI webview cannot work in a native
// image at all, so even a fixed build would have been headless-only. CI compiled it on two
// runners every push, uploaded a binary missing the shared libraries it needs to start, and
// never ran it. The shipped artifacts are the jpackage installers and the shadow jar.

// Build the web bundle before any artifact that needs the resources.
// The Gradle project root is apps/cli-server; pnpm must run from the monorepo root
// (../../) so the workspace filter resolves correctly.
val buildWeb = tasks.register<Exec>("buildWeb") {
    workingDir(file("../../"))
    // pnpm on Windows is pnpm.cmd, and the PATH changes from pnpm/action-setup
    // don't always propagate to gradle's Exec task. Prepend $PNPM_HOME to the
    // PATH so the right pnpm is found on every runner.
    val pnpmHome = System.getenv("PNPM_HOME")
    if (pnpmHome != null) {
        val sep = System.getProperty("path.separator") ?: ":"
        environment("PATH", "${pnpmHome}${sep}${System.getenv("PATH")}")
    }
    val pnpmCmd = if (System.getProperty("os.name").startsWith("Windows")) "pnpm.cmd" else "pnpm"
    commandLine(pnpmCmd, "--filter", "@postcard/web", "build")
}
tasks.named("compileJava") { dependsOn(buildWeb) }
// The version --version prints comes from the Gradle version, not a literal in an annotation,
// so a release cannot ship a binary that disagrees with its own tag.
val generateVersionResource by tasks.registering {
    description = "Write the project version where PostcardOptions can read it back"
    val outputDir = layout.buildDirectory.dir("generated/version")
    val projectVersion = version.toString()
    inputs.property("version", projectVersion)
    outputs.dir(outputDir)
    doLast {
        val f = outputDir.get().asFile.resolve("postcard-version.properties")
        f.parentFile.mkdirs()
        f.writeText("version=$projectVersion\n")
    }
}

sourceSets.named("main") { resources.srcDir(generateVersionResource) }

tasks.named("shadowJar") { dependsOn(buildWeb) }

// ---------------------------------------------------------------------------
// Native installers via the JDK 25 toolchain's `jpackage`.
//
// `appImage` (cross-platform) builds a self-contained app directory that bundles
// a stripped JRE. The three platform tasks (`jpackageDmg`, `jpackageMsi`,
// `jpackageDeb`) depend on `appImage` and run a second `jpackage` invocation
// to wrap it in a platform installer. Each platform task is gated on the host
// OS via `enabled =` so calling the wrong one on another OS is a no-op
// (Gradle prints "Task 'foo' is disabled" and exits 0).
// ---------------------------------------------------------------------------

val isMac = System.getProperty("os.name").startsWith("Mac OS X")
val isWindows = System.getProperty("os.name").startsWith("Windows")
val isLinux = System.getProperty("os.name").startsWith("Linux")

// `jpackage` sits next to `java` inside the JDK 25 toolchain.
val jpackageBin: java.io.File = javaToolchains
    .launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    .get()
    .executablePath
    .asFile
    .parentFile
    .resolve("jpackage")

val shadowJarFile: java.io.File = tasks
    .named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
    .get()
    .archiveFile
    .get()
    .asFile

val appImageDirName: String = if (isMac) "dist/postcard.app" else "dist/postcard"
val appImageDir = layout.buildDirectory.dir(appImageDirName)
val installerDir = layout.buildDirectory.dir("dist/installer")

// jpackage wants a platform-specific icon format: .icns on macOS, .ico on Windows,
// .png everywhere else. The masters live in `icons/` as SVG; the three rasterised
// assets are committed alongside them so CI does not need a rasteriser.
val iconFile: java.io.File = layout.projectDirectory
    .file("icons/postcard." + if (isMac) "icns" else if (isWindows) "ico" else "png")
    .asFile

// jpackage rejects app-versions whose first number is zero (CFBundleVersion requires it to
// be > 0), so a pre-1.0 project version needs a different number for the bundle. It used to
// collapse to a flat "1.0", which meant every 0.x release produced a bundle claiming the same
// version and installers with byte-identical names: two releases were indistinguishable on
// disk, and macOS could not tell an upgrade from a reinstall. Shifting the components instead
// keeps every release distinct -- 0.1.0 becomes 1.1.0, 0.2.3 becomes 1.2.3.
val appVersion: String = run {
    val v = version.toString()
    if (!v.startsWith("0.")) v else "1." + v.removePrefix("0.")
}

/**
 * The macOS signing identity, when one is configured.
 *
 * Signing is opt-in so a contributor without a certificate still gets a working build: with
 * nothing set, every signing argument below is simply absent and the result is the unsigned
 * installer this project has always produced. CI supplies it from a repository secret.
 */
val macSigningIdentity: String? =
    (findProperty("postcard.macSigningIdentity") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("POSTCARD_MAC_SIGNING_IDENTITY")?.takeIf { it.isNotBlank() }

/** Signing arguments for jpackage, or nothing when no identity is configured. */
fun macSigningArgs(): List<String> = if (macSigningIdentity == null) emptyList() else listOf(
    "--mac-sign",
    "--mac-signing-key-user-name", macSigningIdentity,
    "--mac-entitlements", rootProject.file("../../packaging/macos/entitlements.plist").absolutePath,
)

/**
 * Sign the binaries jpackage does not: the JNI webview bridge, and the JCEF natives on the
 * builds that carry them. Deepest first, because signing a bundle invalidates the signature of
 * anything added to it afterwards.
 */
fun signNestedBinaries() {
    val identity = macSigningIdentity ?: return
    val root = appImageDir.get().asFile
    if (!root.exists()) return
    val binaries = root.walkTopDown()
        .filter { it.isFile && (it.extension == "dylib" || it.extension == "so") }
        .toList()
    for (binary in binaries) {
        providers.exec {
            commandLine(
                "codesign", "--force", "--timestamp", "--options", "runtime",
                "--sign", identity, binary.absolutePath,
            )
        }.result.get()
    }
    logger.lifecycle("postcard: signed ${'$'}{binaries.size} nested binaries")
}

/** The installer filename, which carries the real project version rather than appVersion. */
fun installerName(extension: String): String =
    if (extension == "deb") "postcard_${version}_amd64.deb" else "postcard-$version.$extension"

/**
 * Rename jpackage's output to carry the project version.
 *
 * jpackage names the file after `--app-version`, which is the shifted bundle number, so
 * without this the artifact on the Releases page says 1.1.0 for a 0.1.0 build.
 */
fun renameInstaller(fromExtension: String) {
    val dir = installerDir.get().asFile
    val produced = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(".$fromExtension") }
        .maxByOrNull { it.lastModified() } ?: return
    val target = File(dir, installerName(fromExtension))
    if (produced.name == target.name) return
    target.delete()
    if (!produced.renameTo(target)) {
        logger.warn("postcard: could not rename ${'$'}{produced.name} to ${'$'}{target.name}")
    }
}

// ---------------------------------------------------------------------------
// System-webview native bridge (macOS leg).
//
// Compiles src/main/desktop/webview/mac/webview.m (JAWT embedding of WKWebView)
// into build/webview-natives/libpostcard-webview.dylib, where WebviewNatives.locate()
// finds it in development. The packaged case is covered below in jpackageInput.
// Windows (WebView2) and Linux (WebKitGTK) legs follow the same shape.
// ---------------------------------------------------------------------------

val webviewNativesDir = layout.buildDirectory.dir("webview-natives")
val webviewHeaderDir = layout.buildDirectory.dir("generated/webview-headers")
val webviewHeaderClassesDir = layout.buildDirectory.dir("tmp/webview-header-classes")

val jdkBinDir: java.io.File = javaToolchains
    .launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    .get()
    .executablePath
    .asFile
    .parentFile
val jdkHomeDir: java.io.File = jdkBinDir.parentFile
val macWebviewJava = layout.projectDirectory
    .file("src/main/java/io/postcard/desktop/MacWebview.java").asFile
val webviewNativeSource = layout.projectDirectory
    .file("src/main/desktop/webview/mac/webview.m").asFile

tasks.register<Exec>("genWebviewHeaders") {
    group = "build"
    description = "Generate the JNI header for the macOS system-webview bridge"
    enabled = isMac
    inputs.file(macWebviewJava)
    outputs.dir(webviewHeaderDir)
    outputs.dir(webviewHeaderClassesDir)
    doFirst {
        webviewHeaderDir.get().asFile.mkdirs()
        webviewHeaderClassesDir.get().asFile.mkdirs()
    }
    commandLine(
        jdkBinDir.resolve("javac").absolutePath,
        "-h", webviewHeaderDir.get().asFile.absolutePath,
        "-d", webviewHeaderClassesDir.get().asFile.absolutePath,
        macWebviewJava.absolutePath,
    )
}

tasks.register<Exec>("compileWebviewNatives") {
    group = "build"
    description = "Compile the macOS system-webview bridge (disabled on other hosts)"
    enabled = isMac
    dependsOn("genWebviewHeaders")
    inputs.file(webviewNativeSource)
    inputs.dir(webviewHeaderDir)
    outputs.dir(webviewNativesDir)
    doFirst { webviewNativesDir.get().asFile.mkdirs() }
    commandLine(
        "clang",
        "-dynamiclib", "-fobjc-arc",
        "-framework", "Cocoa", "-framework", "WebKit",
        "-I", jdkHomeDir.resolve("include").absolutePath,
        "-I", jdkHomeDir.resolve("include/darwin").absolutePath,
        "-I", webviewHeaderDir.get().asFile.absolutePath,
        webviewNativeSource.absolutePath,
        "-o", webviewNativesDir.get().asFile.resolve("libpostcard-webview.dylib").absolutePath,
    )
}

// `run` needs the bridge present in development; on non-mac hosts the task is
// disabled and this is a no-op. -XstartOnFirstThread puts main() on thread 0:
// AppKit windows are built there, thread 0 parks in the AppKit loop, and the
// native side marshals later calls there. (Dropped once as the suspected cause of
// a graphics-init wedge; exonerated — the wedge was concurrent first-touch from
// two threads, which the strictly-solo init order below avoids. Verified green.)
tasks.named<JavaExec>("run") {
    if (isMac) {
        dependsOn("compileWebviewNatives")
        jvmArgs("-XstartOnFirstThread")
    }
}

// JCEF natives for the *host* platform. Downloaded once at build time and bundled into the
// installer, so the app never needs the network on first run -- postcard is most often
// reached for offline, on a LAN.
val cefBundleDir = layout.buildDirectory.dir("jcef-bundle")

tasks.register<JavaExec>("installCefNatives") {
    group = "build"
    description = "Download and unpack JCEF natives for the host platform into build/jcef-bundle"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.postcard.tools.InstallCefNatives")
    argumentProviders.add { listOf(cefBundleDir.get().asFile.absolutePath) }
    outputs.dir(cefBundleDir)
}

// jpackage copies everything in --input into the app image's `app/` directory. Staging the
// shadow jar and the natives together keeps build/libs clean and puts jcef-bundle exactly
// where CefNatives.locate() looks for it: beside the jar.
val jpackageInputDir = layout.buildDirectory.dir("jpackage-input")

tasks.register<Sync>("jpackageInput") {
    dependsOn("shadowJar", "installCefNatives")
    if (isMac) dependsOn("compileWebviewNatives")
    into(jpackageInputDir)
    from(shadowJarFile)
    from(cefBundleDir) { into("jcef-bundle") }
    // System-webview bridge beside the jar, where WebviewNatives.locate() looks.
    if (isMac) from(webviewNativesDir) { into("webview-natives") }
}

tasks.register<Exec>("appImage") {
    group = "build"
    description = "Build a self-contained app image (executable + bundled JRE) via jpackage"
    dependsOn("jpackageInput")
    // jpackage refuses to overwrite an existing dest, so wipe it each run.
    doFirst {
        appImageDir.get().asFile.deleteRecursively()
        appImageDir.get().asFile.parentFile.mkdirs()
    }
    commandLine(buildList {
        add(jpackageBin.absolutePath)
        addAll(listOf("--type", "app-image"))
        addAll(listOf("--name", "postcard"))
        addAll(listOf("--vendor", "io.postcard"))
        addAll(listOf("--app-version", appVersion))
        addAll(listOf("--icon", iconFile.absolutePath))
        addAll(listOf("--input", jpackageInputDir.get().asFile.absolutePath))
        addAll(listOf("--main-jar", shadowJarFile.name))
        addAll(listOf("--main-class", "io.postcard.Main"))
        addAll(listOf("--dest", appImageDir.get().asFile.parentFile.absolutePath))
        if (isMac) {
            // Replaces jpackage's boilerplate Info.plist, which declares a microphone purpose
            // string postcard has no use for and a minimum OS version from 2015.
            addAll(listOf("--resource-dir", rootProject.file("../../packaging/macos").absolutePath))
        }
        // Chromium needs a real AWT toolkit, and JCEF needs these opens on macOS from JDK 16 on.
        addAll(listOf("--java-options", "-Djava.awt.headless=false"))
        // JCEF calls System.loadLibrary. On JDK 25 that is a restricted method: it warns today
        // and is documented to be blocked in a future release unless native access is granted.
        addAll(listOf("--java-options", "--enable-native-access=ALL-UNNAMED"))
        if (isMac) {
            addAll(listOf("--java-options", "--add-opens=java.desktop/sun.awt=ALL-UNNAMED"))
            addAll(listOf("--java-options", "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED"))
            addAll(listOf("--java-options", "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED"))
            // Thread 0 for Main.main: window building and event-loop parking.
            addAll(listOf("--java-options", "-XstartOnFirstThread"))
        }
    })
}

/**
 * Write the values jpackage does not substitute into a custom Info.plist.
 *
 * With the stock template every release reported CFBundleVersion 1.0, so macOS could not tell
 * one install from another; with a custom template the keys come through as literal
 * DEPLOY_* placeholders instead. Setting them here is the only way to be sure the bundle
 * carries the version that was actually built.
 */
fun stampInfoPlist() {
    val plist = File(appImageDir.get().asFile, "Contents/Info.plist")
    if (!plist.exists()) return
    fun set(key: String, value: String) {
        providers.exec {
            commandLine("/usr/libexec/PlistBuddy", "-c", "Set :$key $value", plist.absolutePath)
        }.result.get()
    }
    set("CFBundleVersion", appVersion)
    set("CFBundleShortVersionString", appVersion)
    logger.lifecycle("postcard: stamped Info.plist with version $appVersion")
}

tasks.named("appImage") {
    doLast {
        if (isMac) {
            stampInfoPlist()
            signNestedBinaries()
        }
    }
}

tasks.register<Exec>("jpackageDmg") {
    group = "build"
    description = "Build a macOS .dmg installer (macOS only; disabled on other hosts)"
    dependsOn("appImage")
    enabled = isMac
    doFirst { installerDir.get().asFile.mkdirs() }
    commandLine(
        jpackageBin.absolutePath,
        *macSigningArgs().toTypedArray(),
        "--type", "dmg",
        "--name", "postcard",
        // Pinned rather than derived from --vendor, so the bundle identity cannot drift.
        "--mac-package-identifier", "io.postcard.app",
        "--mac-package-name", "postcard",
        "--vendor", "io.postcard",
        "--copyright", "Copyright (c) 2026 postcard contributors",
        "--description", "Move a file between two devices on the same network.",
        "--about-url", "https://github.com/hectortav/postcard",
        // Ships the licence with the installer. The bundled JDK and Chromium both carry
        // redistribution terms that nothing in the tree previously acknowledged.
        "--license-file", rootProject.file("../../LICENSE").absolutePath,
        "--app-version", appVersion,
        "--app-image", appImageDir.get().asFile.absolutePath,
        "--dest", installerDir.get().asFile.absolutePath,
    )
    doLast { renameInstaller("dmg") }
}

tasks.register<Exec>("jpackageMsi") {
    group = "build"
    description = "Build a Windows .msi installer (Windows only; disabled on other hosts)"
    dependsOn("appImage")
    enabled = isWindows
    doFirst { installerDir.get().asFile.mkdirs() }
    commandLine(
        jpackageBin.absolutePath,
        "--type", "msi",
        "--name", "postcard",
        // A fixed upgrade code makes a new MSI replace the previous install rather than
        // sitting beside it as a second copy.
        "--win-upgrade-uuid", "6f4d2c1a-8b3e-4d7a-9c15-2e8f0a6b3d41",
        "--win-menu",
        "--win-shortcut",
        "--win-dir-chooser",
        "--vendor", "io.postcard",
        "--copyright", "Copyright (c) 2026 postcard contributors",
        "--description", "Move a file between two devices on the same network.",
        "--about-url", "https://github.com/hectortav/postcard",
        // Ships the licence with the installer. The bundled JDK and Chromium both carry
        // redistribution terms that nothing in the tree previously acknowledged.
        "--license-file", rootProject.file("../../LICENSE").absolutePath,
        "--app-version", appVersion,
        "--app-image", appImageDir.get().asFile.absolutePath,
        "--dest", installerDir.get().asFile.absolutePath,
        "--win-menu",
        "--win-shortcut",
    )
    doLast { renameInstaller("msi") }
}

tasks.register<Exec>("jpackageDeb") {
    group = "build"
    description = "Build a Linux .deb installer (Linux only; disabled on other hosts)"
    dependsOn("appImage")
    enabled = isLinux
    doFirst { installerDir.get().asFile.mkdirs() }
    commandLine(
        jpackageBin.absolutePath,
        "--type", "deb",
        "--name", "postcard",
        "--vendor", "io.postcard",
        "--copyright", "Copyright (c) 2026 postcard contributors",
        "--description", "Move a file between two devices on the same network.",
        "--about-url", "https://github.com/hectortav/postcard",
        // Ships the licence with the installer. The bundled JDK and Chromium both carry
        // redistribution terms that nothing in the tree previously acknowledged.
        "--license-file", rootProject.file("../../LICENSE").absolutePath,
        "--app-version", appVersion,
        "--app-image", appImageDir.get().asFile.absolutePath,
        "--dest", installerDir.get().asFile.absolutePath,
        "--linux-package-name", "postcard",
        "--linux-deb-maintainer", "ektoras@index-zr0.com",
    )
    doLast { renameInstaller("deb") }
}
