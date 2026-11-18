/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/// Pure matcher/command tests and real engine actions using an in-memory game process; never starts an OS process.
@NotNullByDefault
class SaoProcessAndFolderTest {
    /// Disposable archives and launcher preferences.
    @TempDir Path temporary;

    /// Unicode, spaces, case and boundary checks reject unrelated Java and prefix collisions.
    @Test void matcherRequiresOurInstanceArgument() {
        String root = "D:\\游戏 目录\\minecraft\\versions\\sao-oath";
        assertTrue(SaoGameProcesses.matches("C:\\Java\\javaw.exe", List.of("--gameDir", root), root, true));
        assertTrue(SaoGameProcesses.matches("java.exe", List.of("-Dgame=" + root.toUpperCase(Locale.ROOT)), root, true));
        assertTrue(SaoGameProcesses.matches("java", List.of("-cp", root + "\\client.jar;C:\\other.jar"), root, true));
        assertFalse(SaoGameProcesses.matches("java.exe", List.of(root + "-other"), root, true));
        assertFalse(SaoGameProcesses.matches("java.exe", List.of(root + "\\..\\other"), root, true));
        assertFalse(SaoGameProcesses.matches("java.exe", List.of("note=" + root), root, true));
        assertFalse(SaoGameProcesses.matches(root + "\\java.exe", List.of("OtherProgram"), root, true));
        assertFalse(SaoGameProcesses.matches("editor.exe", List.of(root), root, true));
        @Unmodifiable List<String> tokens = SaoGameProcesses.tokens("\"C:\\Program Files\\Java\\javaw.exe\" --gameDir \"" + root + "\"");
        assertTrue(SaoGameProcesses.matches(tokens.get(0), tokens.subList(1, tokens.size()), root, true));
        assertFalse(SaoGameProcesses.matches("java", List.of("/data/game-other"), "/data/game", false));
        assertTrue(SaoGameProcesses.matches("/usr/bin/java", List.of("/data/game/client.jar"), "/data/game", false));
    }

    /// CIM JSON preserves Unicode commands and skips unreadable foreign processes without authorizing them.
    @Test void windowsCimMetadataDecodesWithoutNativeAccess() throws Exception {
        String line = "javaw.exe --gameDir \"D:\\游戏 目录\\sao-oath\"";
        var row = new com.google.gson.JsonObject(); row.addProperty("pid", 123); row.addProperty("started", 123456L);
        row.addProperty("executable", "C:\\Java\\javaw.exe"); row.addProperty("line", line);
        var commands = SaoGameProcesses.decodeWindows(row.toString());
        assertEquals(123, commands.get(0).pid()); assertEquals(line, commands.get(0).line());
        assertTrue(SaoGameProcesses.matches(commands.get(0).executable(), SaoGameProcesses.tokens(line), "D:\\游戏 目录\\sao-oath", true));
        row.add("line", com.google.gson.JsonNull.INSTANCE);
        assertTrue(SaoGameProcesses.decodeWindows("[" + row + "]").isEmpty());
        assertTrue(SaoGameProcesses.decodeWindows("").isEmpty());
        assertThrows(IOException.class, () -> SaoGameProcesses.decodeWindows("invalid"));
    }

    /// A complete no-Java snapshot avoids the failing PowerShell path, while possible games still require inspection.
    @Test void windowsSnapshotAvoidsCimOnlyWhenNoOtherJavaExists() throws Exception {
        assertTrue(SaoWindowsProcessSnapshot.isJavaImage("JaVaW.ExE"));
        assertFalse(SaoWindowsProcessSnapshot.isJavaImage("java-helper.exe"));
        assertTrue(SaoGameProcesses.discoverWindowsCommands(() -> false, () -> {
            throw new AssertionError("CIM queried without other Java");
        }).isEmpty());
        var command = new SaoGameProcesses.WindowsCommand(42, 123456L, "javaw.exe", "--gameDir C:\\game");
        assertEquals(List.of(command), SaoGameProcesses.discoverWindowsCommands(() -> true, () -> List.of(command)));
        assertEquals(List.of(command), SaoGameProcesses.discoverWindowsCommands(() -> {
            throw new IOException("snapshot failed");
        }, () -> List.of(command)));
        assertThrows(IOException.class, () -> SaoGameProcesses.discoverWindowsCommands(() -> true, () -> {
            throw new IOException("CIM unavailable");
        }));
    }

    /// Explorer receives a full executable path and individual arguments without shell interpretation.
    @Test void windowsCommandKeepsUnicodeSpacesAndSelection() {
        String path = "D:\\游戏 包\\截图 & 样本.png";
        assertEquals(List.of("C:\\Windows\\explorer.exe", "/select,", path),
                SaoDesktopOpen.command(OperatingSystem.WINDOWS, path, true, "C:\\Windows"));
        assertEquals(List.of("E:\\WINDOWS\\explorer.exe", "D:\\游戏 包\\shaderpacks"),
                SaoDesktopOpen.command(OperatingSystem.WINDOWS, "D:\\游戏 包\\shaderpacks", false, "E:\\WINDOWS"));
    }

    /// Cancelling a leftover-process prompt cannot start repair; accepting terminates it before any writes.
    @Test void leftoverCancelAndEndBeforeRepair() throws Exception {
        try (SaoPackEngine engine = new SaoPackEngine(temporary.resolve("root"), archive(), false)) {
            FakeSteps steps = new FakeSteps(); engine.setGameSteps(steps);
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            AtomicBoolean alive = new AtomicBoolean(true); AtomicInteger asked = new AtomicInteger();
            SaoGameProcesses.Running old = new SaoGameProcesses.Running() {
                /// Reads the synthetic old-process state.
                @Override public boolean alive() { return alive.get(); }
                /// Ends no OS process.
                @Override public void end() { alive.set(false); }
            };
            CompletableFuture<Boolean> decision = new CompletableFuture<>();
            engine.processControl(() -> List.of(old), (held, running) -> {
                assertFalse(held); assertTrue(running.getAsBoolean()); asked.incrementAndGet(); return decision;
            });
            engine.repair(); await(() -> asked.get() == 1);
            assertEquals(0, steps.installs.get()); decision.complete(false);
            // A future-returning move provides a deterministic completion boundary for cancellation.
            await(() -> !questionPending(engine));
            assertTrue(alive.get()); assertEquals(0, steps.installs.get());
            engine.processControl(() -> List.of(old), (held, running) -> CompletableFuture.completedFuture(true));
            engine.repair(); await(() -> steps.installs.get() == 1);
            assertFalse(alive.get()); await(() -> engine.state().get() == SaoPackState.READY);
        }
    }

    /// A managed game makes the prompt name the game, and its exit callback frees a queued repair.
    @Test void heldGameEndsAndRetriesRepair() throws Exception {
        try (SaoPackEngine engine = new SaoPackEngine(temporary.resolve("root"), archive(), false)) {
            FakeSteps steps = new FakeSteps(); engine.setGameSteps(steps);
            engine.processControl(List::of, (held, alive) -> CompletableFuture.completedFuture(true));
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            engine.download(); await(() -> engine.state().get() == SaoPackState.READY);
            engine.setGraphicsPreset(SaoData.GraphicsPreset.FAST);
            engine.linkStartOffline("Fixture"); await(() -> engine.state().get() == SaoPackState.RUNNING);
            AtomicBoolean sawHeld = new AtomicBoolean();
            CompletableFuture<Boolean> answer = new CompletableFuture<>();
            engine.processControl(List::of, (held, alive) -> { sawHeld.set(held && alive.getAsBoolean()); return answer; });
            int before = steps.installs.get(); engine.repair(); await(sawHeld::get);
            assertEquals(before, steps.installs.get()); assertTrue(steps.process.isAlive());
            answer.complete(true);
            await(() -> steps.installs.get() > before && engine.state().get() == SaoPackState.READY);
            assertFalse(steps.process.isAlive()); assertNull(engine.lastError().get());
        }
    }

    /// Closing Minecraft restores the launcher state even if the process output is still draining.
    @Test void processExitPrecedesDiagnostics() throws Exception {
        try (SaoPackEngine engine = new SaoPackEngine(temporary.resolve("root"), archive(), false)) {
            FakeSteps steps = new FakeSteps();
            engine.setGameSteps(steps);
            engine.processControl(List::of, (held, alive) -> CompletableFuture.completedFuture(true));
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            engine.download();
            await(() -> engine.state().get() == SaoPackState.READY);
            engine.setGraphicsPreset(SaoData.GraphicsPreset.FAST);
            engine.linkStartOffline("Fixture");
            await(() -> engine.state().get() == SaoPackState.RUNNING);

            steps.process.terminate();
            assertFalse(steps.process.isAlive());
            await(() -> engine.state().get() == SaoPackState.WORKING);
            assertEquals("正在整理游戏退出信息", engine.stepLabel().get());

            steps.process.exit.run();
            await(() -> engine.state().get() == SaoPackState.READY);
            assertNull(engine.lastError().get());
        }
    }

    /// A dependency-stage options file receives defaults after pack application; no other bytes are rewritten.
    @Test void earlyOptionsReceiveDistributionListAfterInstall() throws Exception {
        try (SaoPackEngine engine = new SaoPackEngine(temporary.resolve("root"), archive(), false)) {
            FakeSteps steps = new FakeSteps(); engine.setGameSteps(steps);
            engine.processControl(List::of, (held, alive) -> CompletableFuture.completedFuture(true));
            String original = "lang:zh_cn\r\nsoundDevice:耳机\r\nlast:keep";
            steps.prepare = () -> {
                try { Files.createDirectories(engine.instanceDir()); Files.writeString(engine.instanceDir().resolve("options.txt"), original); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            };
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            engine.download(); await(() -> engine.state().get() == SaoPackState.READY);
            String text = Files.readString(engine.instanceDir().resolve("options.txt"));
            assertTrue(text.startsWith(original + "\r\nresourcePacks:"));
            assertTrue(text.contains("[\"file/Minecraft-Mod-Language-Modpack-Converted-1.20.1.zip\",\"file/SAO中文补全.zip\"]"));
            assertFalse(text.contains("SAO像素幻想重绘")); assertFalse(text.contains("SAO技能图标重绘"));
            engine.repair(); await(() -> steps.installs.get() == 2 && engine.state().get() == SaoPackState.READY);
            assertEquals(text, Files.readString(engine.instanceDir().resolve("options.txt")));
        }
    }

    /// A refused action names installation and the notice disappears once the owning task releases the gate.
    @Test void taskContentionClearsWhenInstallFinishes() throws Exception {
        try (SaoPackEngine engine = new SaoPackEngine(temporary.resolve("root"), archive(), false)) {
            FakeSteps steps = new FakeSteps(); engine.setGameSteps(steps);
            engine.processControl(List::of, (held, alive) -> CompletableFuture.completedFuture(true));
            CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
            steps.prepare = () -> {
                started.countDown();
                try { if (!finish.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            };
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            try {
                engine.download(); assertTrue(started.await(5, TimeUnit.SECONDS));
                engine.repair(); await(() -> engine.lastError().get() != null);
                assertEquals("正在安装整合包…", engine.lastError().get());
            } finally { finish.countDown(); }
            await(() -> engine.state().get() == SaoPackState.READY && engine.lastError().get() == null);
        }
    }

    /// Uses the private atomic solely to await cancelled guard completion without timing sleeps in assertions.
    private static boolean questionPending(SaoPackEngine engine) throws Exception {
        var field = SaoPackEngine.class.getDeclaredField("processQuestion"); field.setAccessible(true);
        return ((AtomicBoolean) field.get(engine)).get();
    }

    /// Supplies dependencies and a listener-owned in-memory process.
    @NotNullByDefault
    private static final class FakeSteps implements SaoReadinessCheck.GameSteps {
        /// Number of preparation operations.
        final AtomicInteger installs = new AtomicInteger();
        /// No native resources or process are created.
        final FakeProcess process = new FakeProcess();
        /// Optional file-writing or blocking dependency fixture.
        Runnable prepare = () -> { };
        /// Records dependency preparation and runs only the injected fixture.
        @Override public void ensureInstalled(boolean repair) { installs.incrementAndGet(); prepare.run(); }
        /// Dependencies are synthetic and ready.
        @Override public @Nullable String missing() { return null; }
        /// Delivers the same process/exit listener contract as HMCL.
        @Override public void launch(Account account, SaoPackManifest manifest, int memory, SaoData.GameWindow window,
                String args, char @Nullable [] password, boolean offline, @Nullable String server,
                BooleanSupplier cancelled, ProcessListener listener) {
            process.exited = listener::onProcessExit;
            process.exit = () -> listener.onExit(0, ProcessListener.ExitType.NORMAL);
            listener.setProcess(new ManagedProcess(process, List.of("java", "fixture")));
        }
    }

    /// Java Process contract implemented entirely in memory, including the HMCL exit notification.
    @NotNullByDefault
    private static final class FakeProcess extends Process {
        /// Synthetic lifetime.
        private volatile boolean alive = true;
        /// Immediate notification installed by the fake launch step.
        Runnable exited = () -> { };
        /// Final notification installed by the fake launch step.
        Runnable exit = () -> { };
        /// No input is sent to a child.
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        /// No child output exists.
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        /// No child error output exists.
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        /// A termination has already completed synchronously.
        @Override public int waitFor() { return exitValue(); }
        /// Reports whether the synchronous termination occurred.
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
        /// Implements Process's running-state contract.
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        /// Completes the synthetic exit only once.
        @Override public void destroy() { if (alive) { terminate(); exit.run(); } }
        /// Ends the game without completing diagnostics.
        void terminate() { alive = false; exited.run(); }
        /// No OS force operation is needed.
        @Override public Process destroyForcibly() { destroy(); return this; }
        /// Returns synthetic liveness.
        @Override public boolean isAlive() { return alive; }
    }

    /// Builds a tiny local distribution without network dependencies.
    private SaoPackArchive archive() throws Exception {
        byte @Unmodifiable [] bytes = "fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var file = new SaoPackManifest.PackFile("config/fixture.txt", SaoPackArchive.sha1(new ByteArrayInputStream(bytes)),
                bytes.length, "overrides/config/fixture.txt", List.of());
        String options = "resourcePacks:[\"file/Minecraft-Mod-Language-Modpack-Converted-1.20.1.zip\",\"file/SAO中文补全.zip\"]\n";
        byte @Unmodifiable [] optionBytes = options.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var optionFile = new SaoPackManifest.PackFile("options.txt", SaoPackArchive.sha1(new ByteArrayInputStream(optionBytes)),
                optionBytes.length, "overrides/options.txt", List.of());
        var manifest = new SaoPackManifest("0.8.1.1", "1.20.1", "47.1.106", "", List.of(file, optionFile), List.of());
        Path path = temporary.resolve("pack.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(SaoPackManifest.NAME)); zip.write(manifest.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("overrides/config/fixture.txt")); zip.write(bytes); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("overrides/options.txt")); zip.write(optionBytes); zip.closeEntry();
        }
        return SaoPackArchive.open(path);
    }

    /// Bounded asynchronous assertion; never starts JavaFX or an OS process.
    private static void await(CheckedCondition condition) throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> { while (!condition.get()) Thread.sleep(10); });
    }

    /// Allows reflection and file inspection in bounded waits.
    @NotNullByDefault
    private interface CheckedCondition {
        /// Evaluates the awaited condition.
        boolean get() throws Exception;
    }
}
