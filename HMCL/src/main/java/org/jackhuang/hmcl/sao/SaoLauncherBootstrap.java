/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.Gson;
import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/// Toolkit-free native entry point shared by Windows, macOS and Linux packages.
/// Chooses a verified per-user JAR and rolls back a replacement that fails to show its initialized primary stage.
@NotNullByDefault
public final class SaoLauncherBootstrap {
    /// Startup timeout for a newly staged JAR; normal launches have no watchdog. A slow first start (antivirus scanning,
    /// a cold disk, a startup dialog left open) must not be mistaken for a broken update.
    private static final int STARTUP_SECONDS = 300;

    /// Utility entry point.
    private SaoLauncherBootstrap() { }

    /// Native shells invoke -cp BUNDLED.jar this-class ARGS using their bundled runtime and module options.
    /// --self-update-status prints persisted state only and never starts JavaFX or a subprocess.
    /// The macOS classpath overlay supplies sao.bundledJar to retain the original JAR as the update baseline.
    public static void main(String[] args) throws Exception {
        SaoSelfUpdate updates = new SaoSelfUpdate(SaoSelfUpdate.defaultRoot());
        if (args.length == 1 && args[0].equals("--self-update-status")) {
            System.out.println(new Gson().toJson(updates.status())); return;
        }
        @Nullable String nativeBundle = System.getProperty("sao.bundledJar");
        Path bundled = nativeBundle != null ? Path.of(nativeBundle).toAbsolutePath().normalize() : Path.of(SaoLauncherBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(bundled, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Native launcher requires a bundled JAR");
        SaoSelfUpdate.Selection selection;
        try { selection = updates.select(bundled, Metadata.VERSION); }
        // A RuntimeException (for example an unparsable version) must not stop the bundled launcher from opening.
        catch (IOException | RuntimeException failure) {
            // Refuse concurrent startup attempts; a damaged local state still leaves the packaged JAR usable.
            if (failure.getMessage() != null && failure.getMessage().contains("另一个启动器")) throw failure;
            System.err.println("Launcher update state unavailable; using bundled launcher: " + failure.getClass().getSimpleName());
            selection = new SaoSelfUpdate.Selection(bundled, Metadata.VERSION, null);
        }
        Process child = new ProcessBuilder(command(selection, bundled, args)).inheritIO().start();
        @Nullable String token = selection.token();
        if (token == null) return;
        updates.attach(token, child.pid());
        boolean healthy = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_SECONDS);
        while (System.nanoTime() < deadline) {
            if (updates.healthy(token)) { healthy = true; break; }
            if (!child.isAlive()) break;
            Thread.sleep(200);
        }
        if (!healthy && child.isAlive()) {
            child.destroy();
            if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly().waitFor();
        }
        updates.finish(token, healthy);
        if (!healthy) {
            SaoSelfUpdate.Selection restored = updates.select(bundled, Metadata.VERSION);
            new ProcessBuilder(command(restored, bundled, args)).inheritIO().start();
        }
    }

    /// Forwards relevant JVM module, headless and storage options to the selected JAR process.
    /// Inherited environment preserves all native package storage overrides.
    /// On macOS, sao.nativeLauncher routes the UI JVM through the bundle executable and its in-process JLI.
    static @Unmodifiable List<String> command(SaoSelfUpdate.Selection selection, Path bundled, String[] args) {
        List<String> command = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        boolean headless = Boolean.getBoolean("java.awt.headless");
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path javaw = bin.resolve("javaw.exe");
        @Nullable String nativeLauncher = System.getProperty("sao.nativeLauncher");
        if (nativeLauncher != null && System.getProperty("os.name", "").startsWith("Mac")) {
            // Keep the selected UI JVM inside the signed bundle executable as well.
            command.add(nativeLauncher);
            command.add("--sao-jvm");
        } else command.add(windows && !headless && Files.isRegularFile(javaw) ? javaw.toString() : bin.resolve(windows ? "java.exe" : "java").toString());
        for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (option.startsWith("--add-modules=") || option.startsWith("--add-opens=") || option.startsWith("--add-exports=")
                    || option.startsWith("-Djava.awt.headless=") || option.startsWith("-Dsao.") || option.startsWith("-Xdock:name=")) command.add(option);
        }
        if (System.getProperty("os.name", "").startsWith("Mac") && command.stream().noneMatch(value -> value.startsWith("-Xdock:name=")))
            command.add("-Xdock:name=NerveGear启动器");
        command.add("-Dsao.home=" + Metadata.HMCL_USER_HOME);
        // The selected JAR may live in the per-user update folder, so the install-relative data root is fixed here.
        command.add("-Dsao.dir=" + Metadata.HMCL_LOCAL_HOME);
        // Uninstall removes the native install even when the selected JAR is a per-user self-update.
        @Nullable Path install = SaoUninstallHelper.installRoot(bundled);
        if (install != null) command.add("-Dsao.installRoot=" + install);
        command.add("-Dsao.selfUpdateRoot=" + SaoSelfUpdate.defaultRoot());
        if (selection.token() != null) command.add("-Dsao.selfUpdateAttempt=" + selection.token());
        command.add("-jar"); command.add(selection.jar().toString()); command.addAll(Arrays.asList(args));
        return List.copyOf(command);
    }
}
