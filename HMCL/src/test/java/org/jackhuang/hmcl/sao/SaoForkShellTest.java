/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.terracotta.TerracottaManager;
import org.jackhuang.hmcl.upgrade.UpdateChannel;
import org.jackhuang.hmcl.upgrade.UpdateChecker;
import org.jackhuang.hmcl.upgrade.UpdateHandler;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// Headless regression checks for isolated storage and disabled upstream runtime entry points.
@NotNullByDefault
final class SaoForkShellTest {
    /// Default storage and runtime caches belong to the fork, independently of the process directory.
    @Test
    void forkOwnsItsDataAndIdentity() {
        assertEquals("NerveGear启动器", Metadata.NAME);
        assertEquals("NerveGear Launcher", Metadata.FULL_NAME);
        assertEquals("NerveGear Launcher v" + Metadata.VERSION, Metadata.FULL_TITLE);
        assertTrue(Metadata.VERSION.matches("\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?"));
        assertEquals("org.saooath.launcher", Metadata.WINDOWS_APP_USER_MODEL_ID);
        assertEquals(".sao-oath-launcher", Metadata.HMCL_LOCAL_HOME.getFileName().toString());
        assertEquals(".sao-oath-launcher", Metadata.HMCL_USER_HOME.getFileName().toString());
        assertTrue(Metadata.MINECRAFT_DIRECTORY.startsWith(Metadata.HMCL_LOCAL_HOME));
        assertTrue(Metadata.DEPENDENCIES_DIRECTORY.startsWith(Metadata.HMCL_LOCAL_HOME));
        assertEquals("", Metadata.HMCL_UPDATE_URL);
        assertEquals("", Metadata.PUBLISH_URL);
        assertEquals("", Metadata.DOCS_URL);
        assertEquals("", Metadata.GROUPS_URL);
        assertFalse(Metadata.isDev());
        assertFalse(Metadata.isNightly());
        assertNull(Metadata.findBundledModpackFile());
    }

    /// The portable data root follows the installed launcher, not the process working directory.
    @Test
    void dataRootFollowsInstallNotWorkingDirectory(@org.junit.jupiter.api.io.TempDir Path temporary) {
        Path user = temporary.resolve("user/.sao-oath-launcher"), elsewhere = temporary.resolve("elsewhere");
        Path windows = temporary.resolve("Games/NerveGear");
        assertEquals(windows.resolve(".sao-oath-launcher"),
                Metadata.defaultLocalHome(windows.resolve("app/launcher.jar"), elsewhere, user));
        Path mac = temporary.resolve("Applications/NerveGear启动器.app/Contents/app/launcher.jar");
        assertEquals(user.resolve("local"), Metadata.defaultLocalHome(mac, Path.of("/"), user));
        assertEquals(user.resolve("local"), Metadata.defaultLocalHome(null, Path.of("/"), user));
        assertEquals(temporary.resolve(".sao-oath-launcher"), Metadata.defaultLocalHome(null, temporary, user));
    }

    /// Even direct legacy calls remain inert without a JavaFX toolkit or network connection.
    @Test
    void updaterDoesNotScheduleWork() {
        UpdateChecker.init();
        UpdateChecker.requestCheckUpdate(UpdateChannel.STABLE, false);
        assertFalse(UpdateChecker.isCheckingUpdate());
        assertFalse(UpdateChecker.isOutdated());
        assertNull(UpdateChecker.getLatestVersion());
        assertNull(UpdateChecker.errorProperty().get());
        assertTrue(UpdateHandler.processArguments(new String[]{"--apply-to", "/must-not-be-written.jar"}));
        assertFalse(UpdateHandler.processArguments(new String[]{}));
    }

    /// Merely loading the legacy facade cannot create a daemon or install a native component.
    @Test
    void terracottaCannotStartOrDownload() {
        TerracottaManager.switchDaemon(true);
        assertNotNull(TerracottaManager.stateProperty().get());
        assertNull(TerracottaManager.download());
        assertNull(TerracottaManager.install(Path.of("/not-a-terracotta-bundle")));
        assertNull(TerracottaManager.setScanning());
        assertNull(TerracottaManager.setWaiting());
        assertNull(TerracottaManager.setGuesting("unused"));
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.getName().contains("Terracotta")));
    }
}
