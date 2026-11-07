/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see SaoPackState.java for the full notice.
 */
package org.jackhuang.hmcl.sao;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.jackhuang.hmcl.auth.Account;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/// The seam between the SAO screen (frontend, Claude) and the pack engine (backend, Codex).
///
/// The screen only reads these properties and calls these methods; it never touches HMCL's
/// version, mod or download pages. All properties change on the JavaFX application thread.
/// Install, update, repair and launch methods return at once and work in the background.
/// Skin validation/storage and small settings accessors are synchronous.
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

    // ---------------------------------------------------------------- windows (added 6 Nov, defaults until built)
    //
    // Everything below feeds one floating window of the screen. Each has a default so an engine that does not
    // offer a window yet still compiles; the screen shows an empty state for it. Lists change on the JavaFX
    // thread, items are replaced (records), never mutated.

    /// News for the news window, newest first: server notices and the pack's update notes.
    default ObservableList<SaoData.News> news() {
        return FXCollections.emptyObservableList();
    }

    /// The 誓约 server's list ping, or null while unknown or when no server is configured.
    default ReadOnlyObjectProperty<SaoData.@Nullable ServerStatus> serverStatus() {
        return new SimpleObjectProperty<>();
    }

    /// Fetches the news and pings the server again (the screen calls it on open and every minute).
    default void refreshOnline() {
    }

    /// The instance's singleplayer saves, last played first.
    default ObservableList<SaoData.World> worlds() {
        return FXCollections.emptyObservableList();
    }

    /// Every world backup, newest first.
    default ObservableList<SaoData.Backup> backups() {
        return FXCollections.emptyObservableList();
    }

    /// Backs a world up as a zip in the backups folder (HMCL's WorldBackupTask).
    default void backupWorld(SaoData.World world) {
    }

    /// Puts a backup back in place of its world, after backing up the current one first.
    default void restoreBackup(SaoData.Backup backup) {
    }

    /// Exports a world as a zip the player can share.
    ///
    /// @param world the world
    /// @param zip   where to write
    default void exportWorld(SaoData.World world, Path zip) {
    }

    /// The shader packs in the instance.
    default ObservableList<SaoData.Pack> shaderPacks() {
        return FXCollections.emptyObservableList();
    }

    /// Picks the shader pack the game uses, or null for none (written to the shader mod's config).
    default void setShader(SaoData.@Nullable Pack pack) {
    }

    /// The resource packs in the instance, in the order the game applies them.
    default ObservableList<SaoData.Pack> resourcePacks() {
        return FXCollections.emptyObservableList();
    }

    /// Turns a resource pack on or off (written to options.txt; the pack's own required packs stay on).
    default void setResourcePackEnabled(SaoData.Pack pack, boolean enabled) {
    }

    /// Copies files the player dropped on the shader or resource window into the right folder.
    ///
    /// @param files the dropped zips
    /// @param shaders true for shaderpacks, false for resourcepacks
    default void addPacks(List<Path> files, boolean shaders) {
    }

    /// The game's screenshots, newest first.
    default ObservableList<Path> screenshots() {
        return FXCollections.emptyObservableList();
    }

    /// The running or last game's log, one line per item, capped at a few thousand lines.
    default ObservableList<String> gameLog() {
        return FXCollections.emptyObservableList();
    }

    /// HMCL's crash analysis of the last run, or null when it did not crash.
    default ReadOnlyObjectProperty<SaoData.@Nullable Crash> lastCrash() {
        return new SimpleObjectProperty<>();
    }

    /// Zips the logs and crash reports for sending to the team.
    ///
    /// @param zip where to write
    default void exportLogs(Path zip) {
    }

    /// The multiplayer room (HMCL's Terracotta).
    default ReadOnlyObjectProperty<SaoData.Room> room() {
        return new SimpleObjectProperty<>(SaoData.Room.OFF);
    }

    /// Opens a room friends can join with its code.
    default void hostRoom() {
    }

    /// Joins a friend's room.
    ///
    /// @param code the code the friend shared
    default void joinRoom(String code) {
    }

    /// Leaves or closes the room.
    default void leaveRoom() {
    }

    /// Every Java found or downloaded that can run the pack (Java 17).
    default ObservableList<SaoData.JavaRuntime> javas() {
        return FXCollections.emptyObservableList();
    }

    /// The Java picked by the player, or null for automatic.
    default @Nullable Path java() {
        return null;
    }

    /// Picks a Java, or null for automatic.
    default void setJava(@Nullable Path executable) {
    }

    /// Downloads the right Java into the launcher's folder.
    default void downloadJava() {
    }

    /// Where downloads come from.
    default SaoData.Source downloadSource() {
        return SaoData.Source.AUTO;
    }

    /// Sets where downloads come from.
    default void setDownloadSource(SaoData.Source source) {
    }

    /// Parallel downloads, 1 to 256.
    default int downloadThreads() {
        return 64;
    }

    /// Sets the parallel downloads.
    default void setDownloadThreads(int threads) {
    }

    /// The running and recent downloads, for the downloads window.
    default ObservableList<SaoData.Task> tasks() {
        return FXCollections.emptyObservableList();
    }

    /// Whether the memory follows the machine (HMCL's automatic memory).
    default boolean autoMemory() {
        return false;
    }

    /// Sets automatic memory.
    default void setAutoMemory(boolean auto) {
    }

    /// The machine's memory in MiB, for the memory slider's end.
    default int systemMemoryMiB() {
        return 16384;
    }

    /// The game window.
    default SaoData.GameWindow gameWindow() {
        return new SaoData.GameWindow(1280, 720, false);
    }

    /// Sets the game window.
    default void setGameWindow(SaoData.GameWindow window) {
    }

    /// Extra JVM arguments, empty for none.
    default String jvmArgs() {
        return "";
    }

    /// Sets extra JVM arguments.
    default void setJvmArgs(String args) {
    }

    /// A small screen preference kept in the launcher's config (for example "sound" = "helmet").
    ///
    /// @param key      the key
    /// @param fallback when unset
    /// @return the value
    default String preference(String key, String fallback) {
        return fallback;
    }

    /// Keeps a small screen preference.
    default void setPreference(String key, String value) {
    }

    /// Opens a folder in the system file manager.
    default void openFolder(SaoData.Folder folder) {
    }

    /// Opens a file with its default app (a screenshot) or shows it in the file manager when it cannot.
    default void openFile(Path file) {
    }

    /// The service the running launcher uses.
    static SaoLauncherService get() {
        return SaoServices.service();
    }
}
