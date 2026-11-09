/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/// Headless uninstall proofs use only synthetic roots, inert helper adapters and explicit Trash adapters.
/// No test launches a native removal helper, desktop application, game or download.
@NotNullByDefault
class SaoUninstallerTest {
    /// JUnit's base directory is configured to an explicitly isolated launcher evidence fixture tree.
    @TempDir Path temporary;

    /// Prevents accidental execution with real/default launcher roots or an unconfigured temporary base.
    @BeforeEach void requireIsolatedFixtureBase() {
        Path evidence = Path.of(System.getProperty("user.home"), "Developer", "sao-launcher-runs");
        Path actual = temporary.toAbsolutePath().normalize();
        assertTrue(actual.startsWith(evidence.resolve("launcherback3/fixtures"))
                || actual.startsWith(evidence.resolve("launcherback4/fixtures"))
                || actual.startsWith(evidence.resolve("launcherback5/fixtures"))
                || actual.startsWith(evidence.resolve("friends1/fixtures"))
                || actual.startsWith(evidence.resolve("argusfix1/fixtures"))
                || actual.startsWith(evidence.resolve("premiumname1/fixtures"))
                || actual.startsWith(evidence.resolve("gitee3/fixtures"))
                || actual.startsWith(evidence.resolve("merge1/fixtures"))
                || actual.startsWith(evidence.resolve("merge2/fixtures"))
                || actual.startsWith(evidence.resolve("loginmail1/fixtures")), "Configure an isolated launcher fixture directory before these tests");
    }

    /// Counts shared/nested roots once and never traverses a link into unrelated data.
    @Test void sizeCountsRegularFilesOnceAndDoesNotFollowLinks() throws Exception {
        Path root = temporary.resolve("launcher");
        Path instance = root.resolve("game");
        Files.createDirectories(instance);
        Files.write(instance.resolve("save.dat"), new byte[23]);
        Files.write(root.resolve("runtime.bin"), new byte[41]);
        Path outside = Files.write(temporary.resolve("other-launcher.dat"), new byte[101]);
        Files.createSymbolicLink(root.resolve("outside-link"), outside);
        SaoUninstaller uninstaller = plan(List.of(root, instance), instance, noTrash());
        assertEquals(64, uninstaller.installedBytes());
        assertEquals(List.of(root), uninstaller.roots());
        assertEquals(101, Files.size(outside));
    }

    /// Copies nested worlds, empty directories and screenshots, verifies bytes, and preserves originals until removal.
    @Test void preservesVerifiedWorldsAndScreenshotsBeforePermanentFallback() throws Exception {
        Path root = temporary.resolve("launcher");
        Path instance = root.resolve("game");
        Files.createDirectories(instance.resolve("saves/world/empty"));
        Files.createDirectories(instance.resolve("screenshots"));
        Files.writeString(instance.resolve("saves/world/level.dat"), "synthetic world");
        Files.write(instance.resolve("screenshots/one.png"), new byte[]{1, 2, 3});
        Files.writeString(root.resolve("launcher-settings.json"), "fixture");
        SaoUninstaller uninstaller = plan(List.of(root), instance, noTrash());
        uninstaller.preserve();
        Path kept = uninstaller.keptSavesFolder();
        assertEquals(temporary.resolve("Desktop/刀剑神域·誓约 存档"), kept);
        assertEquals(-1, Files.mismatch(instance.resolve("saves/world/level.dat"), kept.resolve("saves/world/level.dat")));
        assertTrue(Files.isDirectory(kept.resolve("saves/world/empty")));
        assertEquals(-1, Files.mismatch(instance.resolve("screenshots/one.png"), kept.resolve("screenshots/one.png")));
        assertTrue(Files.exists(root.resolve("launcher-settings.json")));
        uninstaller.remove(List.of());
        assertFalse(Files.exists(root));
        assertTrue(Files.isRegularFile(kept.resolve("saves/world/level.dat")));
    }

    /// Existing recovery folders are never replaced; creation selects the first available numbered suffix atomically.
    @Test void existingRecoveryFoldersUseParenthesizedCollisionSuffix() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("launcher"));
        Path instance = Files.createDirectory(root.resolve("game"));
        Path desktop = Files.createDirectory(temporary.resolve("Desktop"));
        Files.createDirectory(desktop.resolve("刀剑神域·誓约 存档"));
        Files.createDirectory(desktop.resolve("刀剑神域·誓约 存档 (2)"));
        SaoUninstaller uninstaller = plan(List.of(root), instance, noTrash());
        uninstaller.preserve();
        assertEquals(desktop.resolve("刀剑神域·誓约 存档 (3)"), uninstaller.keptSavesFolder());
        assertTrue(Files.isDirectory(desktop.resolve("刀剑神域·誓约 存档")));
    }

    /// An external save link fails preservation and leaves original roots and unrelated targets untouched.
    @Test void saveSymlinkRefusesPreservationWithoutDeletingAnything() throws Exception {
        Path root = temporary.resolve("launcher");
        Path instance = root.resolve("game");
        Files.createDirectories(instance.resolve("saves/world"));
        Path outside = Files.writeString(temporary.resolve("unrelated.dat"), "outside");
        Files.createSymbolicLink(instance.resolve("saves/world/data"), outside);
        SaoUninstaller uninstaller = plan(List.of(root), instance, noTrash());
        assertThrows(IOException.class, uninstaller::preserve);
        assertTrue(Files.isSymbolicLink(instance.resolve("saves/world/data")));
        assertEquals("outside", Files.readString(outside));
    }

    /// Native Trash is used when available, and a refusal is never converted to permanent deletion.
    @Test void trashFailureKeepsRootInsteadOfPermanentlyDeleting() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("launcher"));
        Path file = Files.writeString(root.resolve("account-fixture"), "synthetic");
        AtomicBoolean invoked = new AtomicBoolean();
        SaoUninstaller.Trash refuse = new SaoUninstaller.Trash() {
            /// Advertises a real Trash capability for this fixture.
            @Override public boolean supported() { return true; }
            /// Proves refusal does not call the permanent-delete fallback.
            @Override public void move(Path path) throws IOException { invoked.set(true); throw new IOException("fixture refusal"); }
        };
        assertThrows(IOException.class, () -> plan(List.of(root), root.resolve("game"), refuse).remove(List.of()));
        assertTrue(invoked.get());
        assertEquals("synthetic", Files.readString(file));
    }

    /// An injected Trash implementation moves exactly the allowed root, preserving files as recoverable content.
    @Test void availableTrashReceivesOnlyKnownRoot() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("launcher"));
        Files.writeString(root.resolve("fixture"), "keep in Trash");
        Path trash = temporary.resolve("fake-Trash");
        SaoUninstaller.Trash adapter = new SaoUninstaller.Trash() {
            /// Uses an ordinary fixture move instead of any operating-system desktop action.
            @Override public boolean supported() { return true; }
            /// Rejects unexpected targets before moving the single fixture root.
            @Override public void move(Path path) throws IOException { assertEquals(root, path); Files.move(path, trash); }
        };
        plan(List.of(root), root.resolve("game"), adapter).remove(List.of());
        assertFalse(Files.exists(root));
        assertEquals("keep in Trash", Files.readString(trash.resolve("fixture")));
    }

    /// Broad directories, official-game roots, symlink aliases and unsafe production overrides all refuse before mutation.
    @Test void guardRejectsBroadOfficialAndAliasedRoots() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        for (Path invalid : List.of(Path.of("/"), home, home.getParent(), home.resolve("Desktop"),
                temporary.resolve(".minecraft"), temporary.resolve(".hmcl/child")))
            assertThrows(IOException.class, () -> SaoUninstaller.guardRoot(invalid, home));
        Path outside = Files.createDirectory(temporary.resolve("unrelated"));
        Path alias = Files.createSymbolicLink(temporary.resolve("alias"), outside);
        assertThrows(IOException.class, () -> plan(List.of(alias.resolve("data")), alias.resolve("data/game"), noTrash()).validate());
        Path custom = Files.createDirectory(temporary.resolve("shared-config"));
        assertThrows(IOException.class, () -> new SaoUninstaller(List.of(custom), custom.resolve("game"),
                temporary.resolve("Desktop"), noTrash(), true).validate());
        assertTrue(Files.isDirectory(outside));
    }

    /// Desktop-local installs are allowed, while recovery folders inside a removal root are refused.
    @Test void desktopInstallIsAllowedButOverlappingPreservationIsNot() throws Exception {
        Path desktop = Files.createDirectory(temporary.resolve("Desktop"));
        Path root = Files.createDirectory(desktop.resolve(".sao-oath-launcher"));
        new SaoUninstaller(List.of(root), root.resolve("game"), desktop, noTrash(), true).validate();
        assertThrows(IOException.class, () -> new SaoUninstaller(List.of(root), root.resolve("game"), root.resolve("Desktop"), noTrash(), false).validate());
    }

    /// A root containing the live runtime remains for the post-exit helper; other owned roots are removed now.
    @Test void runningRuntimeRootIsDeferred() throws Exception {
        Path local = Files.createDirectory(temporary.resolve("local"));
        Path user = Files.createDirectory(temporary.resolve("user"));
        Files.writeString(local.resolve("fixture"), "local");
        Path runtime = Files.createDirectories(user.resolve("java/runtime"));
        plan(List.of(local, user), local.resolve("game"), noTrash()).remove(List.of(runtime));
        assertFalse(Files.exists(local));
        assertTrue(Files.isDirectory(runtime));
    }

    /// Permanent fallback deletes a symlink entry itself while the unrelated destination remains byte-identical.
    @Test void permanentFallbackNeverTraversesAnOutsideLink() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("launcher"));
        Path outside = Files.createDirectory(temporary.resolve("other-launcher"));
        Path original = Files.writeString(outside.resolve("world.dat"), "unrelated world");
        Files.createSymbolicLink(root.resolve("linked-world"), outside);
        plan(List.of(root), root.resolve("game"), noTrash()).remove(List.of());
        assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS));
        assertEquals("unrelated world", Files.readString(original));
    }

    /// An instance outside the explicit root plan is refused before any file is moved or deleted.
    @Test void outsideInstanceRefusesRemovalWithoutTouchingEitherRoot() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("launcher"));
        Path outside = Files.createDirectory(temporary.resolve("other-instance"));
        Path original = Files.writeString(outside.resolve("world.dat"), "outside");
        SaoUninstaller uninstaller = plan(List.of(root), outside, noTrash());
        assertThrows(IOException.class, uninstaller::validate);
        assertThrows(IOException.class, () -> uninstaller.remove(List.of()));
        assertTrue(Files.isDirectory(root));
        assertEquals("outside", Files.readString(original));
    }

    /// Cached sizing initializes asynchronously without a toolkit and a successful synthetic uninstall preserves data before quit.
    @Test void engineCachesSizeAndQuitsOnlyAfterPreservationAndRemoval() throws Exception {
        Path root = temporary.resolve("launcher");
        Path instance = instance(root);
        Files.createDirectories(instance.resolve("saves/world"));
        Files.writeString(instance.resolve("saves/world/level.dat"), "world");
        SaoUninstaller uninstaller = plan(List.of(root), instance, noTrash());
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        SaoUninstallHelper.Factory factory = (roots, apps) -> {
            assertTrue(Files.isRegularFile(uninstaller.keptSavesFolder().resolve("saves/world/level.dat")));
            events.add("prepared");
            return prepared(events, root);
        };
        try (SaoPackEngine engine = new SaoPackEngine(root, uninstaller, factory, () -> events.add("shutdown"), () -> events.add("quit"))) {
            awaitIdle(engine);
            await(() -> engine.installedBytes() >= 0);
            assertTrue(engine.installedBytes() >= 5);
            engine.uninstall(true);
            await(() -> events.contains("quit"));
            assertEquals(List.of("prepared", "shutdown", "armed", "quit"), events);
            assertFalse(Files.exists(root));
            assertEquals("world", Files.readString(engine.keptSavesFolder().resolve("saves/world/level.dat")));
            assertNull(engine.lastError().get());
        }
    }

    /// Every accepted task/game-busy refusal exposes lastError and leaves the original directory present.
    @Test void engineRefusesTaskAndGameBusyWithoutPreparingHelper() throws Exception {
        Path root = temporary.resolve("launcher");
        Path instance = instance(root);
        Files.createDirectories(instance);
        SaoUninstallHelper.Factory never = (roots, apps) -> { throw new AssertionError("must refuse before helper"); };
        try (SaoPackEngine engine = new SaoPackEngine(root, plan(List.of(root), instance, noTrash()), never, () -> { }, () -> fail("must not quit"))) {
            awaitIdle(engine);
            AtomicBoolean busy = flag(engine, "busy");
            busy.set(true);
            engine.uninstall(false);
            assertNotNull(engine.lastError().get());
            busy.set(false);
            flag(engine, "gameRunning").set(true);
            engine.uninstall(false);
            assertNotNull(engine.lastError().get());
            flag(engine, "gameRunning").set(false);
            assertTrue(Files.isDirectory(root));
        }
    }

    /// A bad save copy never prepares the helper, disables persistence, removes roots or requests exit.
    @Test void engineCopyFailureNeverShutsDownOrQuits() throws Exception {
        Path root = temporary.resolve("launcher");
        Path instance = instance(root);
        Files.createDirectories(instance.resolve("saves"));
        Files.createSymbolicLink(instance.resolve("saves/link"), Files.writeString(temporary.resolve("outside"), "outside"));
        try (SaoPackEngine engine = new SaoPackEngine(root, plan(List.of(root), instance, noTrash()),
                (roots, apps) -> { throw new AssertionError("must not prepare"); }, () -> fail("must not stop saver"), () -> fail("must not quit"))) {
            awaitIdle(engine);
            engine.uninstall(true);
            await(() -> engine.lastError().get() != null);
            assertTrue(Files.exists(instance.resolve("saves/link"), LinkOption.NOFOLLOW_LINKS));
        }
    }

    /// A primary removal failure cancels the prepared helper and does not request normal launcher exit.
    @Test void engineRemovalFailureCancelsHelperAndDoesNotQuit() throws Exception {
        Path root = temporary.resolve("launcher");
        Path instance = instance(root);
        Files.createDirectories(instance);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        SaoUninstaller.Trash refuse = new SaoUninstaller.Trash() {
            /// Simulates a present native Trash API.
            @Override public boolean supported() { return true; }
            /// Keeps the synthetic data in place to test engine failure handling.
            @Override public void move(Path path) throws IOException { throw new IOException("fixture refusal"); }
        };
        try (SaoPackEngine engine = new SaoPackEngine(root, plan(List.of(root), instance, refuse),
                (roots, apps) -> prepared(events, root), () -> events.add("shutdown"), () -> fail("must not quit"))) {
            awaitIdle(engine);
            engine.uninstall(false);
            await(() -> engine.lastError().get() != null);
            assertEquals(List.of("shutdown", "cancelled"), events);
            assertTrue(Files.exists(root));
        }
    }

    /// Native helper code is only generated and parsed; PID/arming fences precede targets and Trash failures skip self-removal.
    @Test void helperDryRunGeneratesGuardedPostExitScriptsWithoutExecutingThem() throws Exception {
        Path root = temporary.resolve("launcher with ' and $ characters");
        Path app = temporary.resolve("app's.jar");
        for (String os : List.of("Mac OS X", "Linux", "Windows 11")) {
            String script = SaoUninstallHelper.script(List.of(root), List.of(app), temporary.resolve("helper"), 1234567, os);
            assertTrue(script.contains("armed"));
            assertTrue(script.contains("cancelled"));
            assertTrue(script.contains("1234567"));
            assertTrue(script.indexOf("1234567") < script.indexOf("app"));
            assertTrue(script.contains("UNINSTALL_INCOMPLETE"));
            if (!os.startsWith("Windows")) {
                Path file = Files.writeString(temporary.resolve(os.startsWith("Mac") ? "mac.sh" : "linux.sh"), script);
                assertEquals(0, new ProcessBuilder("/bin/sh", "-n", file.toString()).start().waitFor());
                assertTrue(script.contains("[ -L"));
                assertTrue(script.contains("if [ \"$failed\" -eq 0 ]; then"));
                if (os.startsWith("Mac") && Files.isExecutable(Path.of("/usr/bin/osacompile"))) {
                    String start = "<<'SAO_APPLESCRIPT'\n";
                    int offset = script.indexOf(start) + start.length();
                    String apple = script.substring(offset, script.indexOf("\nSAO_APPLESCRIPT", offset));
                    Path appleSource = Files.writeString(temporary.resolve("trash.applescript"), apple);
                    Path compilerLog = temporary.resolve("osacompile.log");
                    Process compiler = new ProcessBuilder("/usr/bin/osacompile", "-o", temporary.resolve("trash.scpt").toString(), appleSource.toString())
                            .redirectErrorStream(true).redirectOutput(compilerLog.toFile()).start();
                    assertEquals(0, compiler.waitFor(), Files.readString(compilerLog));
                }
            } else {
                assertTrue(script.contains("ReparsePoint"));
                assertTrue(script.contains("SendToRecycleBin"));
                assertTrue(script.contains("if (!$failed)"));
            }
        }
        assertFalse(Files.exists(root));
        assertFalse(Files.exists(app));
    }

    /// Self-target discovery selects only fake packaged JAR/app files and skips development class/build paths.
    @Test void selfDiscoveryDryRunSelectsPackagedArtifactAndSkipsDevelopment() throws Exception {
        Path jar = Files.writeString(temporary.resolve("launcher.jar"), "fake jar, never executed");
        assertEquals(List.of(jar), SaoUninstallHelper.discover(jar, null, null, "Linux"));
        assertEquals(List.of(), SaoUninstallHelper.discover(temporary, null, null, "Mac OS X"));
        Path development = Files.createDirectories(temporary.resolve("project/build/libs"));
        Path built = Files.writeString(development.resolve("launcher.jar"), "development");
        assertEquals(List.of(), SaoUninstallHelper.discover(built, null, null, "Mac OS X"));
        Path bundle = temporary.resolve("Launcher.app");
        Files.createDirectories(bundle.resolve("Contents/app"));
        Files.writeString(bundle.resolve("Contents/Info.plist"), "fixture");
        Path bundleJar = Files.writeString(bundle.resolve("Contents/app/launcher.jar"), "fixture");
        assertEquals(List.of(bundle), SaoUninstallHelper.discover(bundleJar, null, null, "Mac OS X"));
        assertEquals("fake jar, never executed", Files.readString(jar));
    }

    /// Builds a plan whose desktop and every source reside in the synthetic fixture root.
    private SaoUninstaller plan(List<Path> roots, Path instance, SaoUninstaller.Trash trash) {
        return new SaoUninstaller(roots, instance, temporary.resolve("Desktop"), trash, false);
    }

    /// Returns the real fixed-instance layout without authentication, networking, toolkit or game creation.
    private static Path instance(Path root) { return new SaoGameBridge(root, (label, progress) -> { }).instanceDir(); }

    /// Disables native Trash explicitly so only ordinary fixture files can be deleted by the fallback test.
    private static SaoUninstaller.Trash noTrash() {
        return new SaoUninstaller.Trash() {
            /// Claims unsupported capability without touching AWT.
            @Override public boolean supported() { return false; }
            /// Fails loudly if the fallback unexpectedly tries a desktop action.
            @Override public void move(Path path) { throw new AssertionError("Unexpected native Trash call"); }
        };
    }

    /// Records helper sequencing without starting a process or touching an executable.
    private static SaoUninstallHelper.Prepared prepared(List<String> events, Path root) {
        return new SaoUninstallHelper.Prepared() {
            /// Arming is allowed only after primary synthetic data removal completed.
            @Override public void arm() { assertFalse(Files.exists(root)); events.add("armed"); }
            /// Cancellation proves the helper cannot act after primary removal failure.
            @Override public void cancel() { events.add("cancelled"); }
        };
    }

    /// Waits for the constructor's file inventory and busy gate to settle without starting a toolkit.
    private static void awaitIdle(SaoPackEngine engine) throws Exception {
        await(() -> engine.state().get() != SaoPackState.CHECKING && !flag(engine, "busy").get());
        assertNull(engine.lastError().get());
    }

    /// Reads a synthetic operation/process gate, avoiding any real game or download to exercise refusals.
    private static AtomicBoolean flag(SaoPackEngine engine, String name) throws Exception {
        var field = SaoPackEngine.class.getDeclaredField(name);
        field.setAccessible(true);
        return (AtomicBoolean) field.get(engine);
    }

    /// Polls short background fixture work with a hard failure deadline.
    private static void await(CheckedCondition condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> { while (!condition.get()) Thread.sleep(5); });
    }

    /// Allows checked reflection failures to propagate through the bounded fixture wait.
    @NotNullByDefault
    private interface CheckedCondition {
        /// Returns true when the expected synthetic state has been observed.
        boolean get() throws Exception;
    }
}
