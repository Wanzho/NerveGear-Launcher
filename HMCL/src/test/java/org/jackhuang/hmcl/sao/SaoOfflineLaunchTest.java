/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccountFactory;
import org.jackhuang.hmcl.game.LaunchOptions;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// File-only regressions for the real offline account/launch plan; never starts a game, toolkit or network client.
@NotNullByDefault
class SaoOfflineLaunchTest {
    /// Isolated launcher and ticket fixtures, never a player's game directory.
    @TempDir Path temporary;

    /// The exact submitted spelling becomes HMCL's name and deterministic offline UUID, without an injector.
    @Test void standardAccountKeepsExactNameAndNeedsNoInjector() throws Exception {
        String name = "KiRiTo_07";
        OfflineAccount account = SaoGameBridge.offlineAccount(name);
        assertEquals(OfflineAccount.class, account.getClass());
        assertEquals(name, account.getProfileName());
        assertEquals(OfflineAccountFactory.getUUIDFromUserName(name), account.getProfileID());
        assertNull(account.getSkin());
        assertTrue(account.getDownloader().getArtifactInfoImmediately().isEmpty());
        assertThrows(IOException.class, () -> account.getDownloader().getArtifactInfo());
        try (AuthInfo auth = account.logIn()) {
            assertEquals(name, auth.getUsername());
            assertEquals(account.getProfileID(), auth.getUUID());
            assertEquals(AuthInfo.class, auth.getClass());
            assertNull(auth.getLaunchArguments(new LaunchOptions.Builder().create()));
        }
    }

    /// The plan consumed by the real process launcher carries shared settings and no join, API, agent or password values.
    @Test void offlinePlanUsesSharedSettingsAndHasNoServerAuthenticationArguments() throws Exception {
        SaoGameBridge bridge = new SaoGameBridge(temporary, (label, progress) -> { });
        try {
            Path instance = bridge.instanceDir();
            SaoGameBridge.LaunchPlan plan = SaoGameBridge.launchPlan(SaoGameBridge.offlineAccount("Asuna_123"),
                    manifest(), 4096, new SaoData.GameWindow(1440, 900, true), "-XX:+UseG1GC -Dfile.encoding=UTF-8",
                    JavaRuntime.CURRENT_JAVA, instance, true);
            LaunchOptions options = plan.options();
            assertEquals("Asuna_123", plan.auth().getUsername());
            assertEquals(org.jackhuang.hmcl.Metadata.TITLE, options.getProfileName());
            assertEquals(instance, options.getGameDir());
            assertEquals(SaoGameBridge.INSTANCE_ID, options.getInstanceId());
            assertEquals(JavaRuntime.CURRENT_JAVA.getBinary(), options.getJavaExecutable());
            assertEquals(4096, options.getMaxMemory());
            assertEquals(1440, options.getWidth());
            assertEquals(900, options.getHeight());
            assertTrue(options.isFullscreen());
            assertEquals(List.of("-XX:+UseG1GC", "-Dfile.encoding=UTF-8"), options.getJavaArguments());
            assertTrue(options.getJavaAgents().isEmpty());
            assertTrue(options.getOverrideJavaArguments().isEmpty());
            assertTrue(options.getGameArguments().isEmpty());
            assertNull(options.getQuickPlayOption());
            assertEquals("", plan.server());
            assertNull(plan.auth().getLaunchArguments(options));
            assertFalse(Files.exists(instance.resolve(SaoOathLaunchTicket.FILE_NAME)));
        } finally { bridge.close(); }
    }

    /// Ordinary Link Start keeps its ticket target but never auto-joins, independent of the offline account class.
    @Test void ordinaryLinkStartRetainsItsJoinPlan() throws Exception {
        SaoGameBridge.LaunchPlan plan = SaoGameBridge.launchPlan(SaoGameBridge.offlineAccount("Player_1"), manifest(),
                2048, new SaoData.GameWindow(1280, 720, false), "", JavaRuntime.CURRENT_JAVA, temporary, false);
        assertEquals("server.example:25565", plan.server());
        assertNull(plan.options().getQuickPlayOption());
    }

    /// SAO resources contain UTF-8 text, including when a player supplies a conflicting JVM encoding.
    @Test void gamePlanForcesUtf8WithAndWithoutConflictingUserEncoding() throws Exception {
        for (String arguments : List.of("-XX:+UseG1GC", "-XX:+UseG1GC -Dfile.encoding=GB18030")) {
            SaoGameBridge.LaunchPlan plan = SaoGameBridge.launchPlan(SaoGameBridge.offlineAccount("Player_1"),
                    manifest(), 2048, new SaoData.GameWindow(1280, 720, false), arguments,
                    JavaRuntime.CURRENT_JAVA, temporary, false);
            assertEquals(List.of("-XX:+UseG1GC", "-Dfile.encoding=UTF-8"), plan.options().getJavaArguments());
        }
    }

    /// An existing ticket is deleted without reading proof bytes; repeated cleanup never creates a new file.
    @Test void deletesStaleTicketAndDoesNotCreateOne() throws Exception {
        SaoGameBridge bridge = new SaoGameBridge(temporary, (label, progress) -> { });
        try {
            Files.createDirectories(bridge.instanceDir());
            Path ticket = bridge.instanceDir().resolve(SaoOathLaunchTicket.FILE_NAME);
            Files.writeString(ticket, "synthetic stale proof");
            bridge.clearOfflineTicket();
            assertFalse(Files.exists(ticket));
            bridge.clearOfflineTicket();
            assertFalse(Files.exists(ticket));
        } finally { bridge.close(); }
    }

    /// A ticket symlink is unlinked while its outside target remains untouched.
    @Test void cleanupNeverFollowsTicketSymlink() throws Exception {
        SaoGameBridge bridge = new SaoGameBridge(temporary.resolve("launcher"), (label, progress) -> { });
        try {
            Files.createDirectories(bridge.instanceDir());
            Path target = Files.writeString(temporary.resolve("kept.txt"), "keep");
            Path ticket = bridge.instanceDir().resolve(SaoOathLaunchTicket.FILE_NAME);
            Files.createSymbolicLink(ticket, target);
            bridge.clearOfflineTicket();
            assertFalse(Files.isSymbolicLink(ticket));
            assertEquals("keep", Files.readString(target));
        } finally { bridge.close(); }
    }

    /// Cleanup precedes even release selection: a missing distribution still removes a stale ARGUS handoff.
    @Test void engineCleansTicketBeforeMissingPackFailureWithoutSavingName() throws Exception {
        try (SaoPackEngine engine = new SaoPackEngine(temporary, null, false)) {
            awaitInspection(engine);
            Files.createDirectories(engine.instanceDir());
            Path ticket = Files.writeString(engine.instanceDir().resolve(SaoOathLaunchTicket.FILE_NAME), "stale");
            engine.linkStartOffline("Offline_Only");
            awaitFailure(engine);
            assertFalse(Files.exists(ticket));
            // 11 Nov: Link Start no longer installs; a missing pack is refused with the readiness line.
            assertEquals("还没下载", engine.lastError().get());
            engine.linkStartOffline("invalid name");
            assertEquals("名字须为 3–16 位英文字母、数字或下划线", engine.lastError().get());
            assertNull(engine.oathAccount().get());
            assertNull(engine.rememberedOathName());
            assertFalse(Files.exists(temporary.resolve("launcher-settings.json")));
        }
    }

    /// A nonempty object at the ticket path aborts startup without deleting unrelated content or preparing a game.
    @Test void cleanupFailureStopsBeforeInstallOrLaunch() throws Exception {
        try (SaoPackEngine engine = new SaoPackEngine(temporary, null, false)) {
            awaitInspection(engine);
            Path ticket = engine.instanceDir().resolve(SaoOathLaunchTicket.FILE_NAME);
            Files.createDirectories(ticket);
            Path keep = Files.writeString(ticket.resolve("keep.txt"), "keep");
            engine.linkStartOffline("Player_1");
            awaitFailure(engine);
            assertEquals("keep", Files.readString(keep));
            assertTrue(engine.tasks().isEmpty());
            assertNull(engine.installedVersion().get());
        }
    }

    /// A failed Link Start can be retried directly: the second click runs again instead of being ignored.
    @Test void failedLaunchCanBeRetried() throws Exception {
        try (SaoPackEngine engine = new SaoPackEngine(temporary, null, false)) {
            awaitInspection(engine);
            Path ticket = engine.instanceDir().resolve(SaoOathLaunchTicket.FILE_NAME);
            Files.createDirectories(ticket);
            Files.writeString(ticket.resolve("keep.txt"), "keep");
            // The inspection worker may still hold the busy flag briefly; a click during it is ignored.
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (engine.state().get() != SaoPackState.FAILED) { engine.linkStartOffline("Player_1"); Thread.sleep(20); }
            });
            Files.delete(ticket.resolve("keep.txt"));
            Files.delete(ticket);
            Files.writeString(ticket, "stale");
            engine.linkStartOffline("Player_1");
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> { while (Files.exists(ticket)) Thread.sleep(5); });
            awaitFailure(engine);
        }
    }

    /// A failing window action reports its reason through the returned future instead of only the error property.
    @Test void failedActionReportsThroughItsFuture() throws Exception {
        try (SaoPackEngine engine = new SaoPackEngine(temporary, null, false)) {
            awaitInspection(engine);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> engine.addPacks(List.of(temporary.resolve("missing.zip")), true).get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(java.io.IOException.class, failure.getCause());
            assertFalse(java.util.Objects.toString(failure.getCause().getMessage(), "").isBlank());
        }
    }

    /// Validation rejects Unicode, whitespace, separators and out-of-range lengths without touching the instance.
    @Test void invalidNamesFailLocallyAndKeepReadyState() throws Exception {
        @Unmodifiable List<String> invalid = List.of("", "ab", "abcdefghijklmnopq", " 玩家 ", "Player-1", "a b", " abc", "abc ", "abc\n", "Ａbc", "a/b", "a\\b");
        try (SaoPackEngine engine = new SaoPackEngine(temporary, null, false)) {
            awaitInspection(engine);
            for (String name : invalid) {
                engine.linkStartOffline(name);
                assertEquals(SaoPackState.NOT_INSTALLED, engine.state().get());
                assertEquals("名字须为 3–16 位英文字母、数字或下划线", engine.lastError().get());
                assertThrows(IllegalArgumentException.class, () -> SaoGameBridge.offlineAccount(name));
            }
            assertFalse(Files.exists(engine.instanceDir()));
            engine.close();
            engine.linkStartOffline("invalid name");
            assertEquals(SaoPackState.NOT_INSTALLED, engine.state().get());
        }
    }

    /// Both allowed length boundaries retain their exact case and spelling.
    @Test void acceptsThreeAndSixteenAsciiCharacters() {
        assertEquals("A_1", SaoGameBridge.offlineAccount("A_1").getProfileName());
        assertEquals("Abcdefghijklm_12", SaoGameBridge.offlineAccount("Abcdefghijklm_12").getProfileName());
    }

    /// Constructs account/server metadata as data only; no endpoint is opened by these fixtures.
    private static SaoPackManifest manifest() {
        return new SaoPackManifest("0.8", "1.20.1", "47.1.106", "server.example:25565", "",
                "https://account.example/", List.of(), List.of(new SaoPackManifest.PackFile("config/offline.txt",
                "da39a3ee5e6b4b0d3255bfef95601890afd80709", 0, "overrides/config/offline.txt", List.of())), List.of());
    }

    /// Waits only for the engine's initial local inspection.
    private static void awaitInspection(SaoPackEngine engine) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (engine.state().get() == SaoPackState.CHECKING) Thread.sleep(5);
            assertNull(engine.lastError().get());
        });
    }

    /// Waits for a deliberately failing file-only offline request, without starting an application toolkit.
    private static void awaitFailure(SaoPackEngine engine) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (engine.state().get() != SaoPackState.FAILED) Thread.sleep(5);
        });
    }
}
