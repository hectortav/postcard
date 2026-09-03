plugins {
    java
    application
    id("com.gradleup.shadow") version "9.0.0"
    id("org.graalvm.buildtools.native") version "0.10.2"
}

group = "io.sendme"
version = "0.1.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
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

application { mainClass.set("io.sendme.Main") }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    mergeServiceFiles()
}

graalvmNative {
    binaries { named("main") { imageName.set("sendme") } }
}

// Build the web bundle before any artifact that needs the resources.
// The Gradle project root is apps/cli-server; pnpm must run from the monorepo root
// (../../) so the workspace filter resolves correctly.
val buildWeb = tasks.register<Exec>("buildWeb") {
    workingDir(file("../../"))
    commandLine("pnpm", "--filter", "@sendme/web", "build")
}
tasks.named("compileJava") { dependsOn(buildWeb) }
tasks.named("shadowJar") { dependsOn(buildWeb) }
tasks.named("nativeCompile") { dependsOn(buildWeb) }

// ---------------------------------------------------------------------------
// Native installers via the JDK 21 toolchain's `jpackage`.
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

// `jpackage` sits next to `java` inside the JDK 21 toolchain.
val jpackageBin: java.io.File = javaToolchains
    .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
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

val appImageDirName: String = if (isMac) "dist/sendme.app" else "dist/sendme"
val appImageDir = layout.buildDirectory.dir(appImageDirName)
val installerDir = layout.buildDirectory.dir("dist/installer")

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
        "--name", "sendme",
        "--vendor", "io.sendme",
        "--app-version", appVersion,
        "--input", shadowJarFile.parentFile.absolutePath,
        "--main-jar", shadowJarFile.name,
        "--main-class", "io.sendme.Main",
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
        "--name", "sendme",
        "--vendor", "io.sendme",
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
        "--name", "sendme",
        "--vendor", "io.sendme",
        "--app-version", appVersion,
        "--app-image", appImageDir.get().asFile.absolutePath,
        "--dest", installerDir.get().asFile.absolutePath,
        "--win-menu",
        "--win-shortcut",
    )
}
