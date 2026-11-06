/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see SaoPackState.java for the full notice.
 */
package org.jackhuang.hmcl.sao;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import org.jackhuang.hmcl.auth.Account;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/// The seam between the SAO screen (frontend, Claude) and the pack engine (backend, Codex).
///
/// The screen only reads these properties and calls these methods; it never touches HMCL's
/// version, mod or download pages. All properties change on the JavaFX application thread.
/// Every method returns at once and does its work in the background.
@NotNullByDefault
public interface SaoLauncherService {

    /// The pack's state; drives what Link Start does and what the screen shows.
    ReadOnlyObjectProperty<SaoPackState> state();

    /// Progress of the running install, update or repair, 0 to 1, or -1 while unknown.
    ReadOnlyDoubleProperty progress();

    /// A short game-voice label for the running step (for example "下载 NeoForge"), empty when idle.
    ReadOnlyStringProperty stepLabel();

    /// Why the last operation failed, in the launcher's language, or null when nothing failed.
    ReadOnlyObjectProperty<@Nullable String> lastError();

    /// The pack version installed now (for example "0.8"), or null when not installed.
    ReadOnlyObjectProperty<@Nullable String> installedVersion();

    /// The pack version embedded in this launcher.
    String embeddedVersion();

    /// Link Start: installs or updates when needed, then launches the game with this account and
    /// joins the 誓约 server. Ignored unless the state is NOT_INSTALLED, UPDATE_READY or READY.
    ///
    /// @param account the selected HMCL account (offline or Microsoft)
    /// @param authPassword the sao_auth password to hand to the client mod for the 誓约 server only,
    ///        or null to type it in game; never written to disk in plain text, never in process arguments
    void linkStart(Account account, char @Nullable [] authPassword);

    /// Checks every pack file against the manifest and fixes what is missing or changed,
    /// keeping the player's options, saves and screenshots.
    void repair();

    /// Applies an update zip the player dropped on the window.
    ///
    /// @param zip the dropped file
    void applyUpdateZip(Path zip);

    /// Sets the skin the game sends to the server on join; copies it into the instance as `sao/skin.png`.
    ///
    /// @param png a 64×64 or 64×32 PNG, or null to go back to the default lookup
    /// @param slim true for Slim (Alex) arms, false for Classic
    /// @throws IllegalArgumentException when the file is not a 64×64 or 64×32 PNG
    void setSkin(@Nullable Path png, boolean slim);

    /// The skin set now, or null when none is set.
    @Nullable Path skin();

    /// Whether the skin set now uses Slim arms.
    boolean skinSlim();

    /// Memory for the game in MiB; the settings sheet reads and writes it.
    int memoryMiB();

    /// Sets the game's memory in MiB.
    ///
    /// @param mib between 2048 and the machine's free memory
    void setMemoryMiB(int mib);

    /// The fixed instance folder (opened from the settings sheet).
    Path instanceDir();

    /// The service the running launcher uses.
    static SaoLauncherService get() {
        return SaoServices.service();
    }
}
