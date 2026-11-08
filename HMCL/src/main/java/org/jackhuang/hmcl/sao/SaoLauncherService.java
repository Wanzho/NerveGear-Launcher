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
import java.util.concurrent.CompletableFuture;

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

    /// 离线游玩: installs or
    /// updates when needed, then starts the game with an offline account of this name, without ARGUS ID, without a
    /// launch ticket and without joining the 誓约 server. Ignored unless the state is NOT_INSTALLED, UPDATE_READY or
    /// READY.
    ///
    /// @param name 3 to 16 letters, digits or underscores
    default void linkStartOffline(String name) {
    }

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

    /// How much the launcher's game takes on disk: the instance, its runtimes and libraries and the launcher's data.
    ///
    /// @return bytes, or -1 while unknown
    default long installedBytes() {
        return -1;
    }

    /// One-click uninstall for players who would not know how to remove the launcher manually.
    /// Removes everything the launcher put on this computer: the game instance (game, mods, configs, saves), the Java
    /// runtimes and libraries it downloaded, its own settings and the remembered account and password, and the launcher
    /// itself once it has quit. Files go to the system's Trash / Recycle Bin where there is one, so a mistake can be
    /// undone; where there is none they are deleted. Nothing outside the launcher's own folders is touched (not the
    /// official .minecraft, not other launchers). Refused with [#lastError] while the game runs or a download is busy.
    /// When done the launcher quits.
    ///
    /// @param keepSaves first copy the saves and screenshots to a folder on the desktop, [#keptSavesFolder]
    default void uninstall(boolean keepSaves) {
    }

    /// Where [#uninstall] keeps the saves and screenshots when asked to.
    ///
    /// @return the folder, on the desktop
    default Path keptSavesFolder() {
        return Path.of(System.getProperty("user.home"), "Desktop", "刀剑神域·誓约 存档");
    }

    // ---------------------------------------------------------------- 誓约账号 (7 Nov, Claude's screens)
    //
    // James, 7 Nov: "这样，你做个账号系统行不", and on refusing others: "if system recognizes that the player cannot
    // provide correct credentials from launcher, it refuses it". The account server is sao_auth 1.4.0's Yggdrasil API
    // (~/Developer/sao-launcher-runs/accountsrv1/API-CONTRACT.md). Every call below returns at once; its future
    // completes on the JavaFX thread, or fails with a [SaoAccountException] whose message is ready to show in the
    // launcher's language. Passwords are char arrays the engine wipes after use; never logged, never in arguments.

    /// The 誓约 account signed in now, or null when signed out.
    default ReadOnlyObjectProperty<SaoData.@Nullable OathAccount> oathAccount() {
        return new SimpleObjectProperty<>(null);
    }

    /// The name saved with 记住我, whose password the launcher keeps protected, or null. The screen shows the name
    /// and stars; logging in still takes one press (sao_auth decision, 5 Nov).
    default @Nullable String rememberedOathName() {
        return null;
    }

    /// Logs in (`POST /sao/login`), then keeps the session for Link Start.
    ///
    /// @param name     the character name or the account's verified, uniquely assigned mailbox;
    ///                 记住我 keeps the character name either way
    /// @param password the password, or null to use the remembered one for [#rememberedOathName]
    /// @param remember keep the name and password for next time (false forgets a remembered one)
    default CompletableFuture<Void> oathLogin(String name, char @Nullable [] password, boolean remember) {
        return unsupported();
    }

    /// Registers a new account (`POST /sao/register`) and logs it in; a six-digit code goes to the mailbox.
    ///
    /// @param name     the character name, 3-16 letters, digits or underscores
    /// @param email    the recovery mailbox
    /// @param password the password
    default CompletableFuture<Void> oathRegister(String name, String email, char[] password) {
        return unsupported();
    }

    /// Verifies the mailbox of the signed-in account with the six-digit code (`POST /sao/verify`).
    default CompletableFuture<Void> oathVerify(String code) {
        return unsupported();
    }

    /// Sends the code again (`POST /sao/resend`).
    default CompletableFuture<Void> oathResend() {
        return unsupported();
    }

    /// Asks for a password reset code for a name (`POST /sao/forgot`); always reports sent.
    default CompletableFuture<Void> oathForgot(String name) {
        return unsupported();
    }

    /// Resets the password with the mailed code (`POST /sao/reset`); the character keeps its ID and progress.
    default CompletableFuture<Void> oathReset(String name, String code, char[] password) {
        return unsupported();
    }

    /// The signed-in player's card for the player window: level, floor and Col from the account server; the last time
    /// in the game and the play time kept by the launcher; friends online from the server. Null while signed out or
    /// before the first answer. Refreshed on login, when the game exits and with the server status.
    default ReadOnlyObjectProperty<SaoData.@Nullable PlayerCard> playerCard() {
        return new javafx.beans.property.SimpleObjectProperty<>(null);
    }

    // ------------------------------------------------------------------ friends and chat
    // James, 7 Nov: "好友，和好友聊天系统，能做吗，游戏外也可以发消息的那种", then "群聊也要，单人游戏也能收消息".
    // Everything lives on the account server under the ARGUS ID; the launcher shows it live, and the game (on 誓约
    // or in singleplayer) shows new messages and can answer them. Lists change on the JavaFX thread.

    /// The signed-in player's friends, in the game first, then in the launcher, then offline. Empty while signed out.
    default ObservableList<SaoData.Friend> friends() {
        return javafx.collections.FXCollections.observableArrayList();
    }

    /// Friend requests waiting for an answer, both ways.
    default ObservableList<SaoData.FriendRequest> friendRequests() {
        return javafx.collections.FXCollections.observableArrayList();
    }

    /// Conversations, newest message first.
    default ObservableList<SaoData.Chat> chats() {
        return javafx.collections.FXCollections.observableArrayList();
    }

    /// One conversation's messages, oldest first; the newest are fetched first and older ones as the player scrolls.
    ///
    /// @param chatId the conversation
    default ObservableList<SaoData.ChatMessage> messages(String chatId) {
        return javafx.collections.FXCollections.observableArrayList();
    }

    /// Asks a player to be friends, by 游戏昵称.
    default CompletableFuture<Void> addFriend(String name) {
        return unsupported();
    }

    /// Accepts or declines a request.
    default CompletableFuture<Void> answerFriend(String name, boolean accept) {
        return unsupported();
    }

    /// Ends a friendship; the direct conversation goes with it.
    default CompletableFuture<Void> removeFriend(String name) {
        return unsupported();
    }

    /// Blocks a player: no requests, no messages, not shown online to them.
    default CompletableFuture<Void> blockPlayer(String name) {
        return unsupported();
    }

    /// The direct conversation with a friend, made the first time; completes with its ID.
    default CompletableFuture<String> directChat(String name) {
        return unsupported();
    }

    /// Makes a group with friends; completes with its ID.
    ///
    /// @param title   the group's name
    /// @param members friends to put in it (this player is added)
    default CompletableFuture<String> createGroup(String title, List<String> members) {
        return unsupported();
    }

    /// Adds friends to a group.
    default CompletableFuture<Void> inviteToGroup(String chatId, List<String> members) {
        return unsupported();
    }

    /// Leaves a group.
    default CompletableFuture<Void> leaveGroup(String chatId) {
        return unsupported();
    }

    /// Sends a message; it shows at once as pending and turns delivered when the server has it.
    default CompletableFuture<Void> sendMessage(String chatId, String text) {
        return unsupported();
    }

    /// Marks a conversation read (it is open on screen).
    default void markRead(String chatId) {
    }

    /// The address players join, shown under 服务器 (James, 7 Nov: "服务器下面写mc.saooath.com").
    default String serverAddress() {
        return "mc.saooath.com";
    }

    /// The other nodes (relay lines), shown under 其他节点; mc.saooath.com's SRV record points at zz.
    default java.util.List<String> serverNodes() {
        return java.util.List.of("zz.saooath.com", "tz.saooath.com", "sq.saooath.com");
    }

    /// Changes the signed-in account's mailbox (`POST /sao/email`); a code goes to the new one.
    default CompletableFuture<Void> oathChangeEmail(String email) {
        return unsupported();
    }

    /// Whether a 游戏昵称 is free, for the check while the player types it. Names never collide (James, 7 Nov: "名字不能撞，
    /// 永远不能"): one name, ignoring case, belongs to one ARGUS ID only, and the server refuses a taken one on rename and
    /// register whatever this said.
    ///
    /// @param name the name typed
    default CompletableFuture<Boolean> oathNameAvailable(String name) {
        return CompletableFuture.failedFuture(new SaoAccountException("账号服务暂不可用"));
    }

    /// Renames the signed-in ARGUS ID (James, 7 Nov: a pencil by the name, "indication that you can change it"; then
    /// "点击铅笔可以直接在那个界面换昵称": edited in place on the home screen). The
    /// profile ID stays, so worlds, inventory and skin stay with the player. Fails with a message when the name is
    /// taken (ignoring case, never shared: see [#oathNameAvailable]) or not allowed, or when it is the fourth change today (James, 7 Nov: "一天最多换三次名字"; the account
    /// server counts, per account, over the last 24 hours).
    ///
    /// @param name the new name, 3 to 16 letters, digits or underscores
    default CompletableFuture<Void> oathRename(String name) {
        return unsupported();
    }

    /// Changes the signed-in account's password (`POST /sao/password`).
    default CompletableFuture<Void> oathChangePassword(char[] password, char[] newPassword) {
        return unsupported();
    }

    /// 正版验证: proves the signed-in Microsoft account in the launcher and links it to the signed-in 誓约 account
    /// (`/sao/premium/challenge`, Mojang join, `/sao/premium/complete`, `/sao/premium/link`).
    default CompletableFuture<Void> oathLinkPremium() {
        return unsupported();
    }

    /// Logs in to ARGUS ID with the signed-in Microsoft (正版) account, so signing in with Microsoft is signing in to
    /// ARGUS ID (James, 7 Nov: "这样直接登录微软也可以等于登录sao"): the ARGUS ID linked to it, or, the first time, a new
    /// ARGUS ID with the Microsoft name, linked to it. Fails with a message when that name belongs to another ARGUS ID
    /// (then the player logs in to that one and links Microsoft from it).
    default CompletableFuture<Void> oathPremiumLogin() {
        return unsupported();
    }

    /// Signs out and revokes the session (`/authserver/invalidate`), and forgets the remembered password, so the next
    /// opening does not log in again by itself. The launcher logs in with a remembered password on opening (James,
    /// 7 Nov: "登录进去就直接显示已登录，别再显示一整个密码界面"), so signing out has to stop that.
    default void oathLogout() {
    }

    /// Link Start with the signed-in 誓约 account (authlib-injector pointed at the account server). Ignored when
    /// signed out or [SaoData.OathAccount#locked].
    default void linkStartOath() {
    }

    /// The skin and arms the signed-in account wears on the server now (downloaded from its profile), or null for
    /// the default.
    default @Nullable Path oathSkin() {
        return null;
    }

    /// Puts a skin on the signed-in account (`PUT /api/user/profile/{uuid}/skin`), so every player sees it.
    ///
    /// @param png  a 64×64 PNG, or null to go back to the default (`DELETE`)
    /// @param slim Slim (Alex) arms
    default CompletableFuture<Void> uploadSkin(@Nullable Path png, boolean slim) {
        return unsupported();
    }

    /// The skins kept in the launcher's library, newest first.
    default ObservableList<SaoData.SavedSkin> savedSkins() {
        return FXCollections.observableArrayList();
    }

    /// Keeps a skin in the library.
    ///
    /// @param name the name the player gave it
    /// @param png  the 64×64 PNG bytes
    /// @param slim Slim arms
    /// @return the kept skin
    /// @throws IllegalArgumentException when the bytes are not a 64×64 PNG
    default SaoData.SavedSkin saveSkin(String name, byte[] png, boolean slim) {
        throw new IllegalArgumentException("unsupported");
    }

    /// Removes a skin from the library.
    default void deleteSavedSkin(SaoData.SavedSkin skin) {
    }

    /// A failed future for engines that do not have accounts yet.
    private static <T> CompletableFuture<T> unsupported() {
        return CompletableFuture.failedFuture(new SaoAccountException("账号服务暂不可用"));
    }

    /// The service the running launcher uses.
    static SaoLauncherService get() {
        return SaoServices.service();
    }
}
