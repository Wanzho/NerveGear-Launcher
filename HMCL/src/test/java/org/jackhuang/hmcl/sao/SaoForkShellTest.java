/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later.
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
        assertEquals("刀剑神域·誓约 启动器", Metadata.NAME);
        assertEquals("SAO Oath Launcher", Metadata.FULL_NAME);
        assertEquals("0.1.0", Metadata.VERSION);
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
