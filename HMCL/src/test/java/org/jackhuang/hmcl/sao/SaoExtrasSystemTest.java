/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/// First-open threshold, persistent preference, async service and pending-preset tests, without game or network work.
@NotNullByDefault
class SaoExtrasSystemTest {
    /// Disposable launcher/instance roots.
    @TempDir Path temporary;

    /// RAM and disk thresholds warn or block at precise boundaries; Java and permission rows remain distinct.
    @Test void thresholdAndArchitectureChecks() {
        long gib = SaoSystemChecks.GIB;
        var good = SaoSystemChecks.evaluate(new SaoSystemChecks.Facts(8 * gib, 6 * gib, 5 * gib, true, true, false));
        assertEquals(4, good.size()); assertTrue(good.stream().allMatch(row -> row.level() == SaoData.CheckLevel.OK));
        var warnings = SaoSystemChecks.evaluate(new SaoSystemChecks.Facts(8 * gib - 1, 6 * gib - 1, 5 * gib, true, false, false));
        assertEquals(SaoData.CheckLevel.WARN, warnings.get(0).level()); assertEquals(SaoData.CheckLevel.WARN, warnings.get(1).level()); assertEquals(SaoData.CheckLevel.WARN, warnings.get(2).level());
        var blocks = SaoSystemChecks.evaluate(new SaoSystemChecks.Facts(16 * gib, 5 * gib - 1, 5 * gib, false, true, true));
        assertEquals(SaoData.CheckLevel.BLOCK, blocks.get(1).level()); assertEquals(SaoData.CheckLevel.BLOCK, blocks.get(2).level()); assertEquals(SaoData.CheckLevel.BLOCK, blocks.get(3).level());
        assertEquals(SaoData.CheckLevel.BLOCK, SaoSystemChecks.evaluate(new SaoSystemChecks.Facts(0, -1, 1, true, false, false)).get(1).level());
        assertEquals(2 * SaoSystemChecks.OATH08_BYTES + SaoSystemChecks.OATH08_ZIP_BYTES + 2 * gib,
                SaoSystemChecks.requiredBytes(SaoSystemChecks.OATH08_BYTES, SaoSystemChecks.OATH08_ZIP_BYTES));
        assertEquals(Long.MAX_VALUE, SaoSystemChecks.requiredBytes(Long.MAX_VALUE, 1));
    }

    /// A real write probe leaves the future instance and existing player files unchanged and rejects a symlink.
    @Test void diskAndWriteProbe() throws Exception {
        Path future = temporary.resolve("not-created/instance");
        assertTrue(SaoSystemChecks.freeBytes(future) > 0); assertTrue(SaoSystemChecks.writable(future)); assertFalse(Files.exists(future));
        Files.createSymbolicLink(temporary.resolve("linked"), temporary);
        assertFalse(SaoSystemChecks.writable(temporary.resolve("linked/instance")));
        assertThrows(IOException.class, () -> SaoSystemChecks.freeBytes(temporary.resolve("linked/instance")));
        try (var files = Files.list(temporary)) { assertEquals(List.of(temporary.resolve("linked")), files.toList()); }
    }

    /// Preset selection during a run writes preferences only; the next launch applies once and preserves later game edits.
    @Test void pendingPresetAndPersistentSettings() throws Exception {
        var settings = new SaoSettings(temporary); var running = new AtomicBoolean(true);
        var game = new SaoGameBridge(temporary, (label, progress) -> { });
        var logs = new SaoGameLogs(game.instanceDir());
        var installer = new SaoPackInstaller(game.instanceDir(), (file, target) -> { throw new AssertionError("No downloads"); }, Map.of(), (label, progress) -> { });
        try (var online = new SaoOnlineData(temporary, (request, limit) -> { throw new AssertionError("No network"); });
             var extras = new SaoExtras(temporary, game, settings, online, logs, installer, () -> null, () -> "fixture", running::get, false, temporary.resolve("Downloads"))) {
            assertEquals(SaoData.LauncherBehavior.HIDE, extras.behavior()); assertEquals(SaoData.GraphicsPreset.AUTO, extras.preset());
            extras.behavior(SaoData.LauncherBehavior.KEEP); extras.preset(SaoData.GraphicsPreset.FAST);
            assertFalse(Files.exists(game.instanceDir().resolve("options.txt")));
            assertThrows(IOException.class, extras::beforeLaunch); running.set(false); extras.beforeLaunch();
            Path options = game.instanceDir().resolve("options.txt"); Files.writeString(options, Files.readString(options) + "playerSetting:untouched\nrenderDistance:23\n");
            String edited = Files.readString(options); extras.beforeLaunch(); assertEquals(edited, Files.readString(options));
            extras.preset(SaoData.GraphicsPreset.QUALITY); extras.beforeLaunch(); assertTrue(Files.readString(options).contains("playerSetting:untouched"));
            assertTrue(Files.readString(options).contains("renderDistance:16"));
            assertEquals("KEEP", new SaoSettings(temporary).text("extras.launcherBehavior", ""));
            assertSame(extras.links(), extras.links()); assertThrows(UnsupportedOperationException.class, () -> extras.links().clear());
            extras.refreshLinks(); assertEquals(2, extras.links().size());
            assertTrue(extras.links().stream().noneMatch(link -> link.id().equals("quark")));
            var linksView = extras.links();
            extras.updateQuark("https://pan.quark.cn/s/updated-fixture");
            assertSame(linksView, extras.links());
            assertEquals("https://pan.quark.cn/s/updated-fixture", linksView.get(1).url());
            assertEquals("1104279677", linksView.get(2).copy());
            assertNotEquals(SaoData.GraphicsPreset.AUTO, extras.suggestion().get(30, TimeUnit.SECONDS));
            var rows = extras.systemCheck().get(30, TimeUnit.SECONDS); assertEquals(4, rows.size());
            var zip = extras.feedback().get(30, TimeUnit.SECONDS); assertTrue(Files.isRegularFile(zip.path())); assertEquals(Files.size(zip.path()), zip.size());
            assertEquals(temporary.resolve("Downloads"), zip.path().getParent());
        } finally { game.close(); }
    }

    /// The real service persists exact notice acceptance and settings across restarts, with no UI initialization.
    @Test void serviceNoticeAndDefaults() throws Exception {
        try (var engine = new SaoPackEngine(temporary, null, false)) {
            assertFalse(engine.userNoticeAccepted()); assertThrows(IllegalArgumentException.class, () -> engine.acceptUserNotice("old-version"));
            engine.acceptUserNotice(engine.userNoticeVersion()); assertTrue(engine.userNoticeAccepted());
            assertEquals(SaoData.LauncherBehavior.HIDE, engine.launcherBehavior()); engine.setLauncherBehavior(SaoData.LauncherBehavior.CLOSE);
            engine.setGraphicsPreset(SaoData.GraphicsPreset.BALANCED); assertEquals(SaoData.GraphicsPreset.BALANCED, engine.graphicsPreset());
            assertSame(engine.links(), engine.links()); assertSame(engine.notification(), engine.notification()); engine.setLauncherAttention(false, false);
            for (var method : SaoLauncherService.class.getMethods()) {
                if (method.isDefault()) assertEquals(SaoPackEngine.class,
                        SaoPackEngine.class.getMethod(method.getName(), method.getParameterTypes()).getDeclaringClass(), method.getName());
            }
        }
        try (var engine = new SaoPackEngine(temporary, null, false)) {
            assertTrue(engine.userNoticeAccepted()); assertEquals(SaoData.LauncherBehavior.CLOSE, engine.launcherBehavior());
            assertEquals(SaoData.GraphicsPreset.BALANCED, engine.graphicsPreset());
        }
    }

    /// A settings file cut short by a crash is set aside and the defaults are used instead of failing startup.
    @Test void corruptSettingsFallBackToDefaults() throws Exception {
        Files.writeString(temporary.resolve("launcher-settings.json"), "");
        SaoSettings settings = new SaoSettings(temporary);
        assertEquals(8192, settings.number("memoryMiB", 8192));
        try (var listing = Files.list(temporary)) {
            assertTrue(listing.anyMatch(path -> path.getFileName().toString().startsWith("launcher-settings.json.corrupt-")));
        }
        settings.put("memoryMiB", 4096);
        assertEquals(4096, new SaoSettings(temporary).number("memoryMiB", 8192));
    }
}
