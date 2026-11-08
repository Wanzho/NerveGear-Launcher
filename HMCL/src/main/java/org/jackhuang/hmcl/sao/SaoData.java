/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see SaoPackState.java for the full notice.
 */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;

/// The plain values the SAO screen shows in its windows (news, server, worlds, packs, logs, multiplayer, Java,
/// downloads). The engine fills them; the screen never builds them from HMCL itself.
@NotNullByDefault
public final class SaoData {
    /// Not instantiable.
    private SaoData() {
    }

    /// What a news entry is.
    @NotNullByDefault
    public enum NewsKind {
        /// A server notice.
        NOTICE,
        /// A pack update's changelog.
        UPDATE
    }

    /// One news entry for the news window.
    ///
    /// @param kind  notice or update
    /// @param title short title
    /// @param date  as shown, for example "11-06"
    /// @param body  plain text, a few lines
    @NotNullByDefault
    public record News(NewsKind kind, String title, String date, String body) {
    }

    /// The 誓约 server as the list ping sees it.
    ///
    /// @param online  whether the ping answered
    /// @param players players online
    /// @param max     player slots
    /// @param pingMs  round trip in milliseconds, -1 while unknown
    /// @param motd    the message of the day, plain text
    /// @param names   the sample of player names the ping returned
    @NotNullByDefault
    public record ServerStatus(boolean online, int players, int max, int pingMs, String motd, @Unmodifiable List<String> names) {
        /// Copies the server's optional player sample so replacing a status never mutates an older record.
        public ServerStatus { names = List.copyOf(names); }
    }

    /// One singleplayer save.
    ///
    /// @param name        the world's name
    /// @param folder      its folder under saves
    /// @param lastPlayed  epoch milliseconds
    /// @param sizeBytes   size on disk
    /// @param icon        its icon.png, or null
    /// @param mode        game mode as shown, for example "生存"
    @NotNullByDefault
    public record World(String name, Path folder, long lastPlayed, long sizeBytes, @Nullable Path icon, String mode) {
    }

    /// One world backup.
    ///
    /// @param file      the backup zip
    /// @param world     the world folder it belongs to
    /// @param time      epoch milliseconds
    /// @param sizeBytes size of the zip
    @NotNullByDefault
    public record Backup(Path file, Path world, long time, long sizeBytes) {
    }

    /// One shader pack or resource pack.
    ///
    /// @param name        file name without the extension
    /// @param file        the zip or folder
    /// @param description the pack's description line, may be empty
    /// @param enabled     whether it is on (for shaders: the one in use)
    /// @param icon        its pack.png, or null
    @NotNullByDefault
    public record Pack(String name, Path file, String description, boolean enabled, @Nullable Path icon) {
    }

    /// Why the last game run crashed.
    ///
    /// @param reason HMCL's crash analysis in one line, in the launcher's language
    /// @param detail the matched part of the log or report
    /// @param report the crash report or log file
    @NotNullByDefault
    public record Crash(String reason, String detail, Path report) {
    }

    /// Where the multiplayer room is.
    @NotNullByDefault
    public enum RoomState {
        /// No room.
        OFF,
        /// Getting the tunnel ready.
        PREPARING,
        /// Hosting: friends join with the code.
        HOSTING,
        /// Joining a friend's room.
        JOINING,
        /// In a friend's room; the game connects to it from the server list.
        JOINED,
        /// The tunnel failed; see the message.
        FAILED
    }

    /// The multiplayer room (HMCL's Terracotta).
    ///
    /// @param state   where it is
    /// @param code    the room code to share or that was joined, or null
    /// @param members people in the room, counting the player
    /// @param message a short line for the window, or null
    @NotNullByDefault
    public record Room(RoomState state, @Nullable String code, int members, @Nullable String message) {
        /// No room.
        public static final Room OFF = new Room(RoomState.OFF, null, 0, null);
    }

    /// One Java the game can run on.
    ///
    /// @param version    for example "17.0.12"
    /// @param executable the java executable
    /// @param vendor     for example "Eclipse Adoptium", may be empty
    /// @param managed    whether the launcher downloaded it
    @NotNullByDefault
    public record JavaRuntime(String version, Path executable, String vendor, boolean managed) {
    }

    /// Where downloads come from.
    @NotNullByDefault
    public enum Source {
        /// The launcher picks (BMCLAPI in China).
        AUTO,
        /// The BMCLAPI mirror.
        BMCLAPI,
        /// Mojang's and NeoForge's own servers.
        OFFICIAL
    }

    /// What the player window shows under the signed-in name: the character's level, floor and Col
    /// from the server, the last time in the game and the play time, and the friends in the game now.
    ///
    /// @param level         the character's level, -1 while unknown
    /// @param floor         the floor the character was last on, -1 while unknown
    /// @param col           the character's Col, -1 while unknown
    /// @param lastPlayed    when the game last ran with this account, epoch milliseconds, 0 for never
    /// @param playSeconds   time in the game with this account, in seconds
    /// @param friendsOnline the friends in the game now, by name
    @NotNullByDefault
    public record PlayerCard(int level, int floor, long col, long lastPlayed, long playSeconds,
                             @Unmodifiable List<String> friendsOnline) {
        /// Copies the friends so a new card never shares a list with an older one.
        public PlayerCard { friendsOnline = List.copyOf(friendsOnline); }
    }

    /// Where a friend is now.
    public enum Presence {
        /// In the game (on 誓约 or in singleplayer).
        GAME,
        /// The launcher is open, the game is not.
        LAUNCHER,
        /// Neither.
        OFFLINE
    }

    /// One ARGUS ID friend, available to chat both in game and from the launcher.
    ///
    /// @param name     their 游戏昵称
    /// @param presence where they are
    /// @param where    a few words on where in the game ("誓约", "单人游戏"; no floor), empty when not in it
    /// @param head     a picture of their skin's face, or null while not downloaded
    @NotNullByDefault
    public record Friend(String name, Presence presence, String where, @Nullable Path head) {
    }

    /// A friend request.
    ///
    /// @param name     the other player's 游戏昵称
    /// @param incoming true when they asked this player, false when this player asked them
    @NotNullByDefault
    public record FriendRequest(String name, boolean incoming) {
    }

    /// One conversation: with one friend, or a group ("群聊也要").
    ///
    /// @param id       the server's ID for it
    /// @param title    the friend's name, or the group's name
    /// @param group    whether it is a group
    /// @param members  the members' names, this player included
    /// @param unread   messages not read yet
    /// @param lastText the last message, for the list
    /// @param lastTime when it was sent, epoch milliseconds, 0 when there is none
    @NotNullByDefault
    public record Chat(String id, String title, boolean group, @Unmodifiable List<String> members, int unread,
                       String lastText, long lastTime) {
        /// Copies the members so a newer chat never shares a list with an older one.
        public Chat { members = List.copyOf(members); }
    }

    /// One message.
    ///
    /// @param from    who sent it
    /// @param text    what it says
    /// @param time    when, epoch milliseconds
    /// @param mine    sent by this player
    /// @param pending sent by this player and not delivered to the server yet
    @NotNullByDefault
    public record ChatMessage(String from, String text, long time, boolean mine, boolean pending) {
    }

    /// One download task.
    ///
    /// @param name          short label
    /// @param progress      0 to 1, or -1 while unknown
    /// @param bytesPerSecond speed, 0 when unknown
    /// @param done          finished
    @NotNullByDefault
    public record Task(String name, double progress, long bytesPerSecond, boolean done) {
    }

    /// The game window.
    ///
    /// @param width      pixels
    /// @param height     pixels
    /// @param fullscreen start full screen
    @NotNullByDefault
    public record GameWindow(int width, int height, boolean fullscreen) {
    }

    /// The 誓约 account signed in now (API `GET /sao/account` and the login reply).
    ///
    /// @param name          the character name, exact spelling
    /// @param profileId     the character's UUID without hyphens
    /// @param maskedEmail   the recovery mailbox masked for display (for example "ja***@163.com"), empty when none
    /// @param emailVerified whether that mailbox is verified
    /// @param locked        `lockedVerify`: the two-day grace ran out; the account can be managed but cannot join
    /// @param graceSeconds  seconds left to verify while unverified and not locked, else 0
    /// @param premiumName   the linked 正版 (Microsoft) name after 正版验证, or null when none is linked
    /// @param mustRename    a 正版 player proved ownership and took this name; the account keeps its UUID and progress but cannot join
    ///                      until it has a new name
    @NotNullByDefault
    public record OathAccount(String name, String profileId, String maskedEmail, boolean emailVerified, boolean locked,
                              long graceSeconds, @Nullable String premiumName, boolean mustRename) {
        /// An account whose name is its own.
        public OathAccount(String name, String profileId, String maskedEmail, boolean emailVerified, boolean locked,
                           long graceSeconds, @Nullable String premiumName) {
            this(name, profileId, maskedEmail, emailVerified, locked, graceSeconds, premiumName, false);
        }

        /// Whether the account may join the game now.
        public boolean canJoin() {
            return !locked && !mustRename;
        }
    }

    /// A skin kept in the launcher's own library (the skin page), not on the server.
    ///
    /// @param name  the name the player gave it
    /// @param file  the 64×64 PNG in the launcher's data folder
    /// @param slim  Slim (Alex) arms
    /// @param saved when it was saved, epoch milliseconds
    @NotNullByDefault
    public record SavedSkin(String name, Path file, boolean slim, long saved) {
    }

    /// A folder the screen can open in the file manager.
    @NotNullByDefault
    public enum Folder {
        /// The instance.
        GAME,
        /// saves
        SAVES,
        /// The world backups.
        BACKUPS,
        /// shaderpacks
        SHADERS,
        /// resourcepacks
        RESOURCES,
        /// screenshots
        SCREENSHOTS,
        /// logs and crash-reports
        LOGS
    }
}
