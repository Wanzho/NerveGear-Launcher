/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/// The cheap Link Start readiness check and the game steps 下载 and Link Start share.
/// Runs on the engine worker only; never hashes, downloads or launches.
@NotNullByDefault
final class SaoReadinessCheck {
    /// Shown when the pack or the game files are missing.
    static final String NOT_DOWNLOADED = "还没下载";
    /// Shown when a pack file, the Minecraft/NeoForge instance or the client is missing.
    static final String INCOMPLETE = "游戏文件不完整";
    /// Shown when no Java 17 is selected or found.
    static final String NO_JAVA = "缺少 Java 17";

    private SaoReadinessCheck() { }

    /// The game-side steps of 下载 and Link Start; tests replace them to stay offline and never start a process.
    @NotNullByDefault
    interface GameSteps {
        /// Selects or downloads Java 17, Minecraft 1.20.1 and NeoForge 47.1.106 and their libraries.
        void ensureInstalled(boolean repair) throws Exception;

        /// Cheap presence check of the instance and Java 17, without downloading.
        ///
        /// @return a short reason, or null when both are there
        @Nullable String missing() throws Exception;

        /// Starts the game exactly as [SaoGameBridge#launch] does.
        void launch(Account account, SaoPackManifest manifest, int memoryMiB, SaoData.GameWindow window, String jvmArgs,
                    char @Nullable [] password, boolean offline, @Nullable String signedServer, BooleanSupplier cancelled,
                    ProcessListener listener) throws Exception;
    }

    /// The production steps, straight through to the bridge.
    static GameSteps of(SaoGameBridge game) {
        return new GameSteps() {
            @Override public void ensureInstalled(boolean repair) throws Exception { game.ensureInstalled(repair); }
            @Override public @Nullable String missing() throws Exception { return game.readinessProblem(); }
            @Override public void launch(Account account, SaoPackManifest manifest, int memoryMiB, SaoData.GameWindow window, String jvmArgs,
                                         char @Nullable [] password, boolean offline, @Nullable String signedServer,
                                         BooleanSupplier cancelled, ProcessListener listener) throws Exception {
                game.launch(account, manifest, memoryMiB, window, jvmArgs, password, offline, signedServer, cancelled, listener);
            }
        };
    }

    /// Decides readiness from the versions first (no disk work), then the game, then the pack files.
    ///
    /// @param source    the pack this launcher would install (imported/verified or embedded), or null
    /// @param installed the committed receipt, or null
    /// @param signed    the signed last-good release's pack version, or null when no valid metadata is known
    /// @param quark     whether a valid Quark link is known
    static SaoReadiness check(@Nullable String source, @Nullable SaoPackManifest installed, @Nullable String signed,
                              boolean quark, Path instance, GameSteps steps) throws Exception {
        SaoReadiness.Action fetch = quark ? SaoReadiness.Action.QUARK : SaoReadiness.Action.PACK_WINDOW;
        if (installed == null && source == null)
            return new SaoReadiness(SaoReadiness.Kind.DOWNLOAD, NOT_DOWNLOADED, SaoReadiness.Action.PACK_WINDOW);
        if (installed == null) return new SaoReadiness(SaoReadiness.Kind.DOWNLOAD, NOT_DOWNLOADED, SaoReadiness.Action.PREPARE);
        // A newer signed release than every local pack: only Quark has it.
        if (signed != null && newer(signed, installed.version()) && (source == null || newer(signed, source)))
            return new SaoReadiness(SaoReadiness.Kind.UPDATE, "有新版本 " + signed, fetch);
        if (source != null && newer(source, installed.version()))
            return new SaoReadiness(SaoReadiness.Kind.UPDATE, "有新版本 " + source, SaoReadiness.Action.PREPARE);
        @Nullable String game = steps.missing();
        if (game != null) return new SaoReadiness(SaoReadiness.Kind.DOWNLOAD, game, SaoReadiness.Action.PREPARE);
        if (!filesPresent(installed, instance))
            return new SaoReadiness(SaoReadiness.Kind.DOWNLOAD, INCOMPLETE, SaoReadiness.Action.PREPARE);
        return SaoReadiness.READY;
    }

    /// Every managed pack file exists with its manifest size; player files (options, saves, shaders) may be absent.
    static boolean filesPresent(SaoPackManifest installed, Path instance) {
        for (SaoPackManifest.PackFile file : installed.files()) {
            if (SaoPackManifest.isPlayerFile(file.path())) continue;
            try {
                Path target = SaoPackFiles.resolve(instance, file.path());
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != file.size()) return false;
            } catch (Exception unreadable) { return false; }
        }
        return true;
    }

    /// Whether version a is later than b.
    private static boolean newer(String a, String b) { return VersionNumber.compare(a, b) > 0; }
}
