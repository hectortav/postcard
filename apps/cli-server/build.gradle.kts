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
