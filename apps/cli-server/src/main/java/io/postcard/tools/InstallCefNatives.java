package io.postcard.tools;

import me.friwi.jcefmaven.CefAppBuilder;

import java.io.File;

/**
 * Build-time entry point: downloads and unpacks the JCEF natives for the host platform.
 *
 * <p>Run by the {@code installCefNatives} Gradle task so the natives can be bundled into the
 * installer. The application itself never downloads anything — it runs with
 * {@code setSkipInstallation(true)} and reads whatever this produced. That is what keeps a
 * first launch working on a machine with no internet, which is the situation postcard is most
 * often reached for.
 *
 * <p>Lives in {@code tools} rather than {@code desktop} because nothing at runtime should ever
 * call it.
 */
public final class InstallCefNatives {
    private InstallCefNatives() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: InstallCefNatives <install-dir>");
            System.exit(2);
        }
        File dir = new File(args[0]);
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(dir);
        builder.install();
        System.out.println("jcef natives installed to " + dir.getAbsolutePath());
    }
}
