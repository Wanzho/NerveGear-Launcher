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

    /// Exact public full-pack identity; bytes are downloaded manually from the shared Quark link.
    /// @param version numeric pack version
    /// @param file suggested portable ZIP basename
    /// @param size exact compressed byte count
    /// @param sha256 lowercase SHA-256 of the complete ZIP
    @NotNullByDefault
    public record PackRelease(String version, String file, long size, String sha256) { }

    /// One directed quick update; a unique from-version determines the next hop.
    /// @param from required installed version
    /// @param to resulting version
    /// @param file suggested portable ZIP basename
    /// @param size exact compressed byte count
    /// @param sha256 lowercase SHA-256 of the complete ZIP
    @NotNullByDefault
    public record QuickRelease(String from, String to, String file, long size, String sha256) { }

    /// Launcher compatibility policy, independent of the pack version.
    /// @param latest newest launcher version
    /// @param min oldest permitted launcher version
    /// @param artifact optional exact JAR download identity; absent means complete launcher download
    /// @param nativeRequired whether runtime/native changes require the complete launcher
    @NotNullByDefault
    public record LauncherRelease(String latest, String min, @Nullable LauncherArtifact artifact, boolean nativeRequired) {
        /// Creates a policy requiring a complete native download when no JAR identity is advertised.
        public LauncherRelease(String latest, String min) { this(latest, min, null, false); }
    }

    /// Exact manually downloaded launcher JAR or single-JAR ZIP identity.
    /// @param file portable suggested basename
    /// @param size compressed download byte count
    /// @param sha256 hash of the complete download
    @NotNullByDefault
    public record LauncherArtifact(String file, long size, String sha256) { }

    /// Persisted native-bootstrap state exposed without touching the packaged application.
    /// @param version staged or active JAR version, empty when using bundled JAR
    /// @param restartRequired whether a verified staged JAR awaits restart
    /// @param message success, rollback or full-download instruction
    @NotNullByDefault
    public record LauncherUpdate(String version, boolean restartRequired, String message) { }

    /// Completely validated schema-2 policy with one shared manual-download link.
    /// @param schema supported schema, always 2
    /// @param sequence authenticated nonnegative release sequence, checked independently of news
    /// @param quark validated shared HTTPS Quark link
    /// @param pack complete pack release
    /// @param updates directed quick-update edges, every path ending at the complete release
    /// @param launcher launcher compatibility policy
    /// @param server optional signed game target, subordinate to an explicit pack target
    /// @param accountApi optional signed HTTPS account API root
    /// @param maintenance optional maintenance notice; null means maintenance is off
    @NotNullByDefault
    public record VersionMetadata(int schema, long sequence, String quark, PackRelease pack,
                                  @Unmodifiable List<QuickRelease> updates, LauncherRelease launcher,
                                  @Nullable String server, @Nullable String accountApi, @Nullable Maintenance maintenance) {
        /// Isolates the immutable release inventory from its parser.
        public VersionMetadata { updates = List.copyOf(updates); }
        /// Constructs metadata predating optional production settings without changing its signed sequence.
        public VersionMetadata(int schema, long sequence, String quark, PackRelease pack,
                               @Unmodifiable List<QuickRelease> updates, LauncherRelease launcher) {
            this(schema, sequence, quark, pack, updates, launcher, null, null, null);
        }
        /// Constructs local builder/test metadata with the initial sequence; network parsing always requires the field.
        public VersionMetadata(int schema, String quark, PackRelease pack, List<QuickRelease> updates, LauncherRelease launcher) {
            this(schema, 0, quark, pack, updates, launcher);
        }
    }

    /// Signed plain-text maintenance notice, independent of ping success or launcher compatibility gates.
    /// @param message bounded maintenance explanation
    /// @param until optional human-readable estimate, never interpreted as a deadline
    @NotNullByDefault
    public record Maintenance(String message, @Nullable String until) { }

    /// Authenticated document availability and independently accepted rollback sequences; null means none accepted this run.
    /// @param versionSequence accepted signed version policy sequence, or null
    /// @param newsSequence accepted signed announcement sequence, or null
    @NotNullByDefault
    public record ReleaseVerification(@Nullable Long versionSequence, @Nullable Long newsSequence) { }

    /// Startup/update comparison for the UI; missing metadata never blocks an installed pack.
    /// @param metadata last-good metadata, or null before a valid document is available
    /// @param packUpdateAvailable whether the advertised pack is newer or no pack is installed
    /// @param launcherBlocked whether this launcher is below the mandatory minimum
    /// @param launcherUpdateAvailable whether a newer optional launcher is advertised and not dismissed
    /// @param message Chinese launcher compatibility message, empty when no note is needed
    @NotNullByDefault
    public record UpdateStatus(@Nullable VersionMetadata metadata, boolean packUpdateAvailable,
                               boolean launcherBlocked, boolean launcherUpdateAvailable, String message) {
        /// Returns the signed notice without requiring the screen to inspect policy JSON; null means maintenance is off.
        public @Nullable Maintenance maintenance() { return metadata == null ? null : metadata.maintenance(); }
    }

    /// Cancellable drop/download prompt with retained quick-update identities across multiple drops.
    /// @param watching whether the prompt is active
    /// @param candidate optional complete-pack Downloads candidate, requiring explicit acceptance
    /// @param message exact Chinese instruction for the missing quick hops or full pack
    /// @param required destination versions in chain order
    /// @param present destination versions whose private verified snapshots are retained
    /// @param missing destination versions still needed
    /// @param fullPackRequired whether no usable quick chain exists
    @NotNullByDefault
    public record DownloadPrompt(boolean watching, @Nullable Path candidate, String message,
                                 @Unmodifiable List<String> required, @Unmodifiable List<String> present,
                                 @Unmodifiable List<String> missing, boolean fullPackRequired) {
        /// Copies all observable inventories so a later drop never mutates an earlier state.
        public DownloadPrompt {
            required = List.copyOf(required); present = List.copyOf(present); missing = List.copyOf(missing);
        }
        /// Constructs an inactive or legacy frontend polling state without quick-update inventories.
        public DownloadPrompt(boolean watching, @Nullable Path candidate, String message) {
            this(watching, candidate, message, List.of(), List.of(), List.of(), true);
        }
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
    /// @param id stable ARGUS profile UUID, empty only in old UI fixtures
    /// @param remark current user's private remark, empty when unset
    @NotNullByDefault
    public record Friend(String name, Presence presence, String where, @Nullable Path head, String id, String remark) {
        /// Protocol-1 and existing UI sample compatibility; new consumers should use the stable UUID.
        public Friend(String name,Presence presence,String where,@Nullable Path head){this(name,presence,where,head,"","");}
        /// Private display label; the server never substitutes a remark into public nicknames.
        public String displayName(){return remark.isEmpty()?name:remark;}
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
    /// @param mentions unread visible messages mentioning this account
    /// @param muted current user's notification suppression preference
    /// @param pinned current user's sort-first preference
    /// @param shouldNotify true only when unread and not muted; wire field is notify
    /// @param peerRead direct peer's monotonic read watermark, zero for groups/unknown
    /// @param typing immutable five-second leases; the UI also expires them locally
    @NotNullByDefault
    public record Chat(String id, String title, boolean group, @Unmodifiable List<String> members, int unread,
                       String lastText, long lastTime, int mentions, boolean muted, boolean pinned, boolean shouldNotify,
                       long peerRead, @Unmodifiable List<Typing> typing) {
        /// Protocol-1 source compatibility; direct read watermark defaults to zero.
        public Chat(String id,String title,boolean group,List<String> members,int unread,String lastText,long lastTime){this(id,title,group,members,unread,lastText,lastTime,0,false,false,unread>0,0,List.of());}
        /// Copies the members so a newer chat never shares a list with an older one.
        public Chat { members = List.copyOf(members); typing = List.copyOf(typing); }
    }

    /// One message.
    ///
    /// @param from    who sent it
    /// @param text    what it says
    /// @param time    when, epoch milliseconds
    /// @param mine    sent by this player
    /// @param pending sent by this player and not delivered to the server yet
    /// @param id stable server message ID, zero for pending/old fixture values
    /// @param chat opaque conversation ID
    /// @param sender stable sender profile UUID
    /// @param nonce sender-scoped retry key
    /// @param revision newest durable replacement revision, initially equal to id
    /// @param kind text, play invitation, or erased-message notice
    /// @param quote bounded author/preview from a visible source, or null
    /// @param invite authoritative target and expiry, or null for other kinds
    /// @param mentions immutable resolved group member UUIDs
    /// @param shouldNotify new-delivery notification eligibility; wire field is notify
    @NotNullByDefault
    public record ChatMessage(String from, String text, long time, boolean mine, boolean pending,
                              long id, String chat, String sender, String nonce, long revision,
                              MessageKind kind, @Nullable Quote quote, @Nullable PlayInvite invite,
                              @Unmodifiable List<String> mentions, boolean shouldNotify) {
        /// Protocol-1 source compatibility for existing screens; pending rows have no durable ID.
        public ChatMessage(String from,String text,long time,boolean mine,boolean pending){this(from,text,time,mine,pending,0,"","","",0,MessageKind.TEXT,null,null,List.of(),!mine);}
        /// Defensively copies resolved mention UUIDs.
        public ChatMessage { mentions=List.copyOf(mentions); }
        /// Whether this confirmed outgoing message lies at or below the direct peer's read watermark.
        public boolean readByPeer(Chat conversation){return !conversation.group()&&mine&&!pending&&id>0&&id<=conversation.peerRead();}
    }

    /// Text, a ten-minute Link Start card, or an erased-message notice.
    @NotNullByDefault
    public enum MessageKind { /// Ordinary Unicode text, optionally quoting an earlier message.
        TEXT, /// Target is validated against knownServers and revalidated by the authority on acceptance.
        INVITE, /// Original text and invite data were erased by the sender.
        RECALLED }
    /// Bounded quoted preview supplied even when the source is outside the local history window.
    @NotNullByDefault
    public record Quote(long replyTo,String sender,String from,String text) { }
    /// Server-produced public destination; acceptance still requires an online membership/expiry check.
    @NotNullByDefault
    public record PlayInvite(String target,long expiresAt) { }
    /// Ephemeral typing snapshot; hide at expiresAt even if the connection stops updating.
    @NotNullByDefault
    public record Typing(String id,String from,long expiresAt) {
        /// Tests the transient five-second lease against the UI clock.
        public boolean active(long now){return now<expiresAt;}
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
    /// @param premium whether this entry belongs to a read-only premium source
    @NotNullByDefault
    public record SavedSkin(String name, Path file, boolean slim, long saved, boolean premium) {
        /// Creates an ordinary, deletable launcher library entry.
        public SavedSkin(String name, Path file, boolean slim, long saved) { this(name, file, slim, saved, false); }
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
    /// Launcher behavior after the OS creates the game process; HIDE restores after every exit.
    @NotNullByDefault
    public enum LauncherBehavior {
        /// 隐藏到后台, mapped to HMCL HIDE_AND_REOPEN.
        HIDE,
        /// 保持打开, mapped to HMCL KEEP.
        KEEP,
        /// 直接关闭, mapped to HMCL CLOSE.
        CLOSE;

        /// Reuses HMCL's existing visibility vocabulary without its non-restoring HIDE mode.
        public org.jackhuang.hmcl.setting.LauncherVisibility visibility() {
            return switch (this) {
                case HIDE -> org.jackhuang.hmcl.setting.LauncherVisibility.HIDE_AND_REOPEN;
                case KEEP -> org.jackhuang.hmcl.setting.LauncherVisibility.KEEP;
                case CLOSE -> org.jackhuang.hmcl.setting.LauncherVisibility.CLOSE;
            };
        }
    }

    /// Presets own only the documented video keys; AUTO is resolved at the next launch.
    @NotNullByDefault
    public enum GraphicsPreset {
        /// Suggest from total RAM and detected graphics hardware.
        AUTO,
        /// 流畅: short distances and shaders disabled.
        FAST,
        /// 均衡: medium distances and shaders disabled.
        BALANCED,
        /// 画质: longer distances and the pack's MakeUp shader.
        QUALITY
    }

    /// One community action with a stable id and plain label; exactly one of url or copy is present.
    /// @param id unique action identifier
    /// @param label plain text shown by the UI
    /// @param url HTTPS destination, or null for a copy-only action
    /// @param copy ASCII digits to display and copy, or null for a URL action
    @NotNullByDefault
    public record CommunityLink(String id, String label, @Nullable String url, @Nullable String copy) {
        /// Enforces exclusive actions and bounded digit-only clipboard values for every construction path.
        public CommunityLink {
            if ((url == null) == (copy == null)) throw new IllegalArgumentException("Expected exactly one link action");
            if (copy != null && !copy.matches("[0-9]{1,32}")) throw new IllegalArgumentException("Invalid group number");
        }
        /// Preserves source compatibility for existing HTTPS link callers.
        public CommunityLink(String id, String label, String url) { this(id, label, url, null); }
    }

    /// Completed feedback artifact; the UI may reveal path in the file manager after successful completion.
    @NotNullByDefault
    public record FeedbackZip(Path path, long size) { }

    /// First-open check severity; BLOCK prevents installation, WARN permits continuing.
    @NotNullByDefault
    public enum CheckLevel {
        /// Check passed.
        OK,
        /// Show a concise advisory.
        WARN,
        /// Installation cannot safely proceed.
        BLOCK
    }

    /// One short Chinese first-open check result.
    @NotNullByDefault
    public record SystemCheck(CheckLevel level, String text) { }

    /// Social notification categories consumed by the frontend's OS notification adapter.
    @NotNullByDefault
    public enum NotificationKind {
        /// New incoming text.
        MESSAGE,
        /// A newly pending incoming friendship request.
        FRIEND_REQUEST,
        /// A new, unexpired play invitation.
        INVITE
    }

    /// One ephemeral event; sequence is local and monotonic, chat is empty for friend requests.
    /// Content is never persisted; the UI must not log it or interpret preview as a server destination.
    @NotNullByDefault
    public record Notification(long sequence, NotificationKind kind, String chat, String title, String preview, long time) { }

}
