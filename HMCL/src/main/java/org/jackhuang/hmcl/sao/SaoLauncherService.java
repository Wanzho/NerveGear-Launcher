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

    /// Starts headless Microsoft OAuth and immediately opens the browser; the page owns presentation.
    default SaoMicrosoftFlow microsoftSignIn() { return SaoMicrosoftFlows.start(); }


    /// The pack's state; drives what Link Start does and what the screen shows.
    ReadOnlyObjectProperty<SaoPackState> state();

    /// Progress of the running install, update or repair, 0 to 1, or -1 while unknown.
    ReadOnlyDoubleProperty progress();

    /// A short game-voice label for the running step (for example "下载 NeoForge"), empty when idle.
    ReadOnlyStringProperty stepLabel();

    /// Measured install stage, verified file counts, bytes, speed and all terminal dependency failures.
    /// Production changes on the UI publication thread; -1 totalBytes means HMCL has no known byte total.
    default ReadOnlyObjectProperty<SaoInstallProgress> installProgress() {
        return new SimpleObjectProperty<>(SaoInstallProgress.phase(SaoInstallProgress.Stage.IDLE, ""));
    }

    /// Why the last operation failed, in the launcher's language, or null when nothing failed.
    ReadOnlyObjectProperty<@Nullable String> lastError();

    /// The pack version installed now (for example "0.8"), or null when not installed.
    ReadOnlyObjectProperty<@Nullable String> installedVersion();

    /// The pack version embedded in this launcher.
    String embeddedVersion();

    /// Whether Link Start can launch now and what 下载/更新 does. A cheap check (versions against the signed release,
    /// the instance, Java 17, pack file presence and size), recomputed off the UI thread after start-up, import,
    /// update, repair, uninstall, 下载 and each new signed release; changes on the JavaFX thread.
    default ReadOnlyObjectProperty<SaoReadiness> readiness() {
        return new SimpleObjectProperty<>(SaoReadiness.READY);
    }

    /// 下载/更新 (James, 11 Nov: "下载还是老实叫下载吧"): prepares everything Link Start needs (Java 17, Minecraft,
    /// NeoForge, the pack and its mods, required resources) with the usual progress, then stops. Never launches and
    /// needs no account. When [#readiness()] says [SaoReadiness.Action#QUARK] it opens the Quark link and watches
    /// Downloads instead. Ignored while busy or running, and while a launcher update is mandatory.
    default void download() {
    }

    /// Link Start: re-runs the [#readiness()] check, then launches the game with this account and joins the 誓约
    /// server. Not ready, it does not launch or download: the state becomes FAILED with the reason in [#lastError()]
    /// and readiness turns the button back to 下载/更新. Ignored unless the state is NOT_INSTALLED, UPDATE_READY or READY.
    ///
    /// @param account the selected HMCL account (offline or Microsoft)
    /// @param authPassword the sao_auth password to hand to the client mod for the 誓约 server only,
    ///        or null to type it in game; never written to disk in plain text, never in process arguments
    void linkStart(Account account, char @Nullable [] authPassword);

    /// 离线游玩: the same readiness check as Link Start (not ready: no launch, the reason in [#lastError()]),
    /// then starts the game with an offline account of this name, without ARGUS ID, without a
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

    /// Last-good remote pack/launcher comparison; listeners receive replacements on the UI publication thread.
    /// A pack update is advisory; launcherBlocked is the mandatory Link Start gate.
    default ReadOnlyObjectProperty<SaoData.UpdateStatus> updateStatus() {
        return new SimpleObjectProperty<>(new SaoData.UpdateStatus(null, false, false, false, ""));
    }

    /// Accepted signed news/version sequences; rejected or unavailable fetches retain the previous immutable value.
    default ReadOnlyObjectProperty<SaoData.ReleaseVerification> releaseVerification() {
        return new SimpleObjectProperty<>(new SaoData.ReleaseVerification(null, null));
    }

    /// Downloads-prompt state; a candidate still requires explicit applyDownloadedPack and checksum verification.
    default ReadOnlyObjectProperty<SaoData.DownloadPrompt> downloadPrompt() {
        return new SimpleObjectProperty<>(new SaoData.DownloadPrompt(false, null, ""));
    }

    /// Refreshes announcements and version policy asynchronously; explicit policy attempts are limited to once per 30 seconds.
    default void refreshUpdates() { }

    /// Dismisses the optional launcher note for this advertised latest version; cannot dismiss the minimum gate.
    default void dismissLauncherUpdate() { }

    /// Opens the validated pack Quark link through the system link opener; never downloads a package itself.
    default void openPackDownload() { }

    /// Opens the validated launcher Quark link through the system link opener.
    default void openLauncherDownload() { }

    /// Starts three-second Downloads polling while the UI update prompt is open; never installs automatically.
    default void watchPackDownload() { }

    /// Stops prompt polling and discards its candidate; call when the UI prompt closes or is cancelled.
    default void cancelPackDownload() { }

    /// Accepts a manually chosen or discovered ZIP, verifies size/SHA-256, then uses the preserving pack installer.
    default void applyDownloadedPack(Path zip) { applyUpdateZip(zip); }

    /// Accepts full packs, accumulated quick hops or verified launcher JARs in any drop order.
    /// Files are snapshotted privately; completion and public errors are also exposed through observable state.
    default CompletableFuture<Void> acceptDroppedFiles(List<Path> paths) {
        for (Path path : paths) applyUpdateZip(path);
        return CompletableFuture.completedFuture(null);
    }

    /// Staged launcher replacement and last bootstrap rollback result; restarting uses the native shell.
    default ReadOnlyObjectProperty<SaoData.LauncherUpdate> launcherUpdate() {
        return new SimpleObjectProperty<>(new SaoData.LauncherUpdate("", false, ""));
    }

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

    /// What happens to the launcher when the game process starts; defaults to 隐藏到后台.
    default SaoData.LauncherBehavior launcherBehavior() { return SaoData.LauncherBehavior.HIDE; }

    /// Persists behavior for the next launch; a running game's captured policy does not change.
    default void setLauncherBehavior(SaoData.LauncherBehavior behavior) { }

    /// Builds a scrubbed feedback ZIP asynchronously in Downloads; failures complete exceptionally.
    /// Never opens a file manager itself. The result includes the actual committed file size.
    default CompletableFuture<SaoData.FeedbackZip> buildFeedbackZip() { return unsupported(); }

    /// Stable, read-only, last-good community actions; defaults are 爱发电, the shared 夸克网盘 and QQ群.
    /// Render non-null copy values as numbers with a copy button; only non-null URLs are browser actions.
    default @org.jetbrains.annotations.UnmodifiableView ObservableList<SaoData.CommunityLink> links() { return javafx.collections.FXCollections.emptyObservableList(); }

    /// Refreshes links asynchronously; a failed fetch preserves the last-good list and disk cache.
    default void refreshLinks() { }

    /// Selected preset, initially AUTO. Merely reading it does not edit game files.
    default SaoData.GraphicsPreset graphicsPreset() { return SaoData.GraphicsPreset.AUTO; }

    /// Queues the preset for the next launch; never edits options or mod configs while a game is running.
    default void setGraphicsPreset(SaoData.GraphicsPreset preset) { }

    /// RAM/GPU-based suggestion, asynchronously detected through HMCL; returns a concrete preset.
    default CompletableFuture<SaoData.GraphicsPreset> suggestedGraphicsPreset() { return unsupported(); }

    /// First-open RAM, disk, Java/architecture and actual write checks, without installing or downloading.
    default CompletableFuture<@org.jetbrains.annotations.Unmodifiable List<SaoData.SystemCheck>> systemCheck() { return unsupported(); }

    /// Version of the 用户须知 and licences bundle the UI must present before recording acceptance.
    default String userNoticeVersion() { return "2026-11-09.1"; }

    /// Whether the user explicitly accepted this exact notice version.
    default boolean userNoticeAccepted() { return userNoticeVersion().equals(preference("notice.accepted", "")); }

    /// Records explicit acceptance only for the displayed current version; stale versions are refused.
    default void acceptUserNotice(String version) {
        if (!userNoticeVersion().equals(version)) throw new IllegalArgumentException("用户须知已更新，请重新阅读");
        setPreference("notice.accepted", version);
    }

    /// Reports actual window visibility/focus, initially visible and focused to avoid unsolicited notifications.
    /// The UI calls this whenever either changes; hidden launch policy also updates it automatically.
    default void setLauncherAttention(boolean visible, boolean focused) { }

    /// Stable read-only event property. Listen for replacements; no replay, no durable notification history.
    /// New incoming messages/requests/invites emit only while hidden or unfocused and respect protocol-2 mute.
    default ReadOnlyObjectProperty<SaoData.@Nullable Notification> notification() { return new SimpleObjectProperty<>(); }

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

    /// Refreshes news/status on open or timer; signed policy attempts are limited to once per three minutes.
    default void refreshOnline() {
    }

    /// Refreshes online data for an explicit button action, allowing a policy attempt once per 30 seconds.
    /// Timer/open callers use the no-argument method; UI implementations may inherit this delegation.
    default void refreshOnline(boolean forcePolicy) { refreshOnline(); }

    /// The instance's singleplayer saves, last played first.
    default ObservableList<SaoData.World> worlds() {
        return FXCollections.emptyObservableList();
    }

    /// Every world backup, newest first.
    default ObservableList<SaoData.Backup> backups() {
        return FXCollections.emptyObservableList();
    }

    /// Backs a world up as a zip in the backups folder (HMCL's WorldBackupTask).
    default java.util.concurrent.CompletableFuture<Void> backupWorld(SaoData.World world) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /// Puts a backup back in place of its world, after backing up the current one first.
    default java.util.concurrent.CompletableFuture<Void> restoreBackup(SaoData.Backup backup) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /// Exports a world as a zip the player can share.
    ///
    /// @param world the world
    /// @param zip   where to write
    default java.util.concurrent.CompletableFuture<Void> exportWorld(SaoData.World world, Path zip) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /// The shader packs in the instance.
    default ObservableList<SaoData.Pack> shaderPacks() {
        return FXCollections.emptyObservableList();
    }

    /// Picks the shader pack the game uses, or null for none (written to the shader mod's config).
    default java.util.concurrent.CompletableFuture<Void> setShader(SaoData.@Nullable Pack pack) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /// The resource packs in the instance, in the order the game applies them.
    default ObservableList<SaoData.Pack> resourcePacks() {
        return FXCollections.emptyObservableList();
    }

    /// Turns a resource pack on or off (written to options.txt; the pack's own required packs stay on).
    default java.util.concurrent.CompletableFuture<Void> setResourcePackEnabled(SaoData.Pack pack, boolean enabled) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /// Copies files the player dropped on the shader or resource window into the right folder.
    ///
    /// @param files the dropped zips
    /// @param shaders true for shaderpacks, false for resourcepacks
    default java.util.concurrent.CompletableFuture<Void> addPacks(List<Path> files, boolean shaders) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
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
    default java.util.concurrent.CompletableFuture<Void> setJava(@Nullable Path executable) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /// Downloads the right Java into the launcher's folder.
    default java.util.concurrent.CompletableFuture<Void> downloadJava() {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
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

    /// Sends text quoting a confirmed message in the same conversation; zero means ordinary text.
    default CompletableFuture<Void> sendMessage(String chatId,String text,long replyTo) { return unsupported(); }

    /// Sends a ten-minute Link Start invitation using authoritative sender presence, with no caller-supplied host.
    default CompletableFuture<Void> sendPlayInvite(String chatId) { return unsupported(); }

    /// Revalidates this card with the authority and returns a known target for the UI's Link Start action.
    /// This method never launches a game automatically.
    default CompletableFuture<String> acceptInvite(SaoData.ChatMessage message) { return unsupported(); }

    /// Erases a confirmed own message if it is at most two minutes old; replaces it with a recall notice.
    default CompletableFuture<Void> recallMessage(SaoData.ChatMessage message) { return unsupported(); }

    /// Emits a transient typing lease; callers may invoke on keystrokes, the service coalesces to three seconds.
    default CompletableFuture<Void> typing(String chatId) { return unsupported(); }

    /// Stores both private per-conversation flags; mute keeps unread/mentions but suppresses notify.
    default CompletableFuture<Void> setChatPreferences(String chatId,boolean muted,boolean pinned) { return unsupported(); }

    /// Sets a private friend remark of at most twelve Unicode scalar values; empty clears it.
    default CompletableFuture<Void> setFriendRemark(String name,String remark) { return unsupported(); }

    /// Searches visible stored text, case-insensitive, newest first, at most 100 results; empty chatId searches all.
    default CompletableFuture<@org.jetbrains.annotations.Unmodifiable List<SaoData.ChatMessage>> searchMessages(String text,String chatId,int limit) { return unsupported(); }

    /// Explicit scroll callback; messages(chatId) remains source-compatible with the old paging seam.
    default void loadOlderMessages(String chatId) { }

    /// Exact destinations currently offered by this launcher; invitations never add arbitrary hosts.
    default @org.jetbrains.annotations.Unmodifiable List<String> knownServers() { var result=new java.util.ArrayList<String>();result.add(serverAddress());result.addAll(serverNodes());return List.copyOf(result); }

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

    /// Changes the signed-in account's mailbox (`POST /sao/email`); a code goes to the new one. The current password is
    /// required (sao_auth 1.9.3); a passwordless Microsoft-created account passes an empty one and signs in to Microsoft.
    ///
    /// @param email the new mailbox
    /// @param password the current password, wiped by the call; empty only for a passwordless account
    default CompletableFuture<Void> oathChangeEmail(String email, char[] password) {
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

    /// Display-only Microsoft identity, available before ARGUS admission; null after cancellation/logout.
    default ReadOnlyObjectProperty<@Nullable SaoMicrosoftIdentity> microsoftIdentity() {
        return new javafx.beans.property.ReadOnlyObjectWrapper<@Nullable SaoMicrosoftIdentity>().getReadOnlyProperty();
    }
    /// ARGUS admission state; the screen may show the reason while displaying Microsoft's identity.
    default ReadOnlyObjectProperty<SaoOathLinking> oathLinking() {
        return new javafx.beans.property.ReadOnlyObjectWrapper<>(SaoOathLinking.idle()).getReadOnlyProperty();
    }
    /// True while Link Start is waiting for ARGUS; use the existing sign-in waiting presentation.
    default javafx.beans.property.ReadOnlyBooleanProperty oathLinkWaiting() {
        return new javafx.beans.property.ReadOnlyBooleanWrapper().getReadOnlyProperty();
    }
    /// Cancels background Microsoft-to-ARGUS admission and clears its display identity.
    default void cancelOathLinking() { }

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

    /// Account appearance sources, safe image previews and synchronization status; cleared on signout.
    default ReadOnlyObjectProperty<SaoTextureState> inheritedTextures() { return new SimpleObjectProperty<>(SaoTextureState.empty()); }

    /// Chooses own or premium skin while retaining both stored images.
    default CompletableFuture<Void> setSkinSource(String source) { return unsupported(); }

    /// Chooses own, mojang, optifine, minecraftcapes or none; unavailable images fall back to the account default.
    default CompletableFuture<Void> setCapeSource(String source) { return unsupported(); }

    /// Starts a provider refresh; the account server enforces one manual attempt every five minutes.
    default CompletableFuture<Void> refreshInheritedTextures() { return unsupported(); }

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
