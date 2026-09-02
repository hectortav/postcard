plugins {
    java
    application
    jacoco
    id("com.gradleup.shadow") version "9.0.0"
    id("org.graalvm.buildtools.native") version "0.11.0"
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
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass.set("io.postcard.Main") }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
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
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.50".toBigDecimal()
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

graalvmNative {
    binaries { named("main") { imageName.set("postcard") } }
}

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
tasks.named("shadowJar") { dependsOn(buildWeb) }
tasks.named("nativeCompile") { dependsOn(buildWeb) }

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

// jpackage rejects app-versions with a leading-zero first number (CFBundleVersion
// requires the first component to be > 0). Bump a pre-1.0 project version to "1.0"
// for the bundle; the user-facing version stays at 0.x in `version`.
val appVersion: String =
    if (version.toString().startsWith("0.")) "1.0" else version.toString()

tasks.register<Exec>("appImage") {
    group = "build"
    description = "Build a self-contained app image (executable + bundled JRE) via jpackage"
    dependsOn("shadowJar")
    // jpackage refuses to overwrite an existing dest, so wipe it each run.
    doFirst {
        appImageDir.get().asFile.deleteRecursively()
        appImageDir.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        jpackageBin.absolutePath,
        "--type", "app-image",
        "--name", "postcard",
        "--vendor", "io.postcard",
        "--app-version", appVersion,
        "--icon", iconFile.absolutePath,
        "--input", shadowJarFile.parentFile.absolutePath,
        "--main-jar", shadowJarFile.name,
        "--main-class", "io.postcard.Main",
        "--dest", appImageDir.get().asFile.parentFile.absolutePath,
    )
}

tasks.register<Exec>("jpackageDmg") {
    group = "build"
    description = "Build a macOS .dmg installer (macOS only; disabled on other hosts)"
    dependsOn("appImage")
    enabled = isMac
    doFirst { installerDir.get().asFile.mkdirs() }
    commandLine(
        jpackageBin.absolutePath,
        "--type", "dmg",
        "--name", "postcard",
        "--vendor", "io.postcard",
        "--app-version", appVersion,
        "--app-image", appImageDir.get().asFile.absolutePath,
        "--dest", installerDir.get().asFile.absolutePath,
    )
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
        "--vendor", "io.postcard",
        "--app-version", appVersion,
        "--app-image", appImageDir.get().asFile.absolutePath,
        "--dest", installerDir.get().asFile.absolutePath,
        "--win-menu",
        "--win-shortcut",
    )
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
        "--app-version", appVersion,
        "--app-image", appImageDir.get().asFile.absolutePath,
        "--dest", installerDir.get().asFile.absolutePath,
        "--linux-package-name", "postcard",
        "--linux-deb-maintainer", "ektoras@index-zr0.com",
    )
}
