/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.jackhuang.hmcl.sao.SaoData;
import javafx.util.Duration;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jackhuang.hmcl.sao.SaoPackState;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jackhuang.hmcl.sao.SaoAccountException;

/// A stand-in engine for screen work before the real one is installed: Link Start plays a pretend install
/// and launch, nothing touches the disk or the network.
@NotNullByDefault
public final class SaoFakeService implements SaoLauncherService {
    /// The pretend state.
    private final SimpleObjectProperty<SaoPackState> state = new SimpleObjectProperty<>(SaoPackState.NOT_INSTALLED);
    /// The pretend progress.
    private final SimpleDoubleProperty progress = new SimpleDoubleProperty(-1);
    /// The pretend step.
    private final SimpleStringProperty step = new SimpleStringProperty("");
    /// The pretend error.
    private final SimpleObjectProperty<@Nullable String> error = new SimpleObjectProperty<>();
    /// The pretend installed version.
    private final SimpleObjectProperty<@Nullable String> installed = new SimpleObjectProperty<>();
    /// The pretend skin.
    private @Nullable Path skin;
    /// The pretend arm model.
    private boolean slim;
    /// The pretend memory.
    private int memory = 8192;

    /// Sets the state directly (screenshots of each state).
    ///
    /// @param s        the state
    /// @param p        the progress
    /// @param label    the step label
    /// @param err      the error, or null
    public void force(SaoPackState s, double p, String label, @Nullable String err) {
        state.set(s);
        progress.set(p);
        step.set(label);
        error.set(err);
    }

    /// {@inheritDoc}
    @Override
    public ReadOnlyObjectProperty<SaoPackState> state() {
        return state;
    }

    /// {@inheritDoc}
    @Override
    public ReadOnlyDoubleProperty progress() {
        return progress;
    }

    /// {@inheritDoc}
    @Override
    public ReadOnlyStringProperty stepLabel() {
        return step;
    }

    /// {@inheritDoc}
    @Override
    public ReadOnlyObjectProperty<@Nullable String> lastError() {
        return error;
    }

    /// {@inheritDoc}
    @Override
    public ReadOnlyObjectProperty<@Nullable String> installedVersion() {
        return installed;
    }

    /// {@inheritDoc}
    @Override
    public String embeddedVersion() {
        return "0.8";
    }

    /// Plays a pretend install over six seconds, then a pretend game for four.
    @Override
    public void linkStart(Account account, char @Nullable [] authPassword) {
        if (authPassword != null) Arrays.fill(authPassword, '\0');
        String[] steps = {"解压整合包", "下载 Minecraft", "安装 NeoForge", "校验文件", "启动游戏"};
        state.set(SaoPackState.WORKING);
        Timeline t = new Timeline();
        for (int i = 0; i <= 60; i++) {
            int k = i;
            t.getKeyFrames().add(new KeyFrame(Duration.millis(100 * i), e -> {
                progress.set(k / 60.0);
                step.set(steps[Math.min(steps.length - 1, k / 13)]);
            }));
        }
        t.getKeyFrames().add(new KeyFrame(Duration.millis(6200), e -> {
            installed.set("0.8");
            state.set(SaoPackState.RUNNING);
        }));
        t.getKeyFrames().add(new KeyFrame(Duration.millis(10200), e -> state.set(SaoPackState.READY)));
        t.play();
    }

    /// {@inheritDoc}
    @Override
    public void repair() {
    }

    /// Whether the fake was asked to uninstall, and how (tools read it).
    @Nullable Boolean uninstalled;

    /// {@inheritDoc}
    @Override
    public long installedBytes() {
        return 9_870_000_000L;
    }

    /// {@inheritDoc}
    @Override
    public void uninstall(boolean keepSaves) {
        uninstalled = keepSaves;
    }

    /// {@inheritDoc}
    @Override
    public void applyUpdateZip(Path zip) {
    }

    /// {@inheritDoc}
    @Override
    public void setSkin(@Nullable Path png, boolean slim) {
        this.skin = png;
        this.slim = slim;
    }

    /// {@inheritDoc}
    @Override
    public @Nullable Path skin() {
        return skin;
    }

    /// {@inheritDoc}
    @Override
    public boolean skinSlim() {
        return slim;
    }

    /// {@inheritDoc}
    @Override
    public int memoryMiB() {
        return memory;
    }

    /// {@inheritDoc}
    @Override
    public void setMemoryMiB(int mib) {
        memory = mib;
    }

    /// {@inheritDoc}
    @Override
    public Path instanceDir() {
        return Path.of(System.getProperty("user.home"), ".sao-oath-launcher", "instance");
    }

    // ---------------------------------------------------------------- pretend window data

    /// Pretend news.
    private final ObservableList<SaoData.News> news = FXCollections.observableArrayList(
            new SaoData.News(SaoData.NewsKind.NOTICE, "第一层开放测试", "11-06",
                    "起始之城今晚 20:00 开放。\n第一次进入请先在大厅完成 sao_auth 登录。"),
            new SaoData.News(SaoData.NewsKind.UPDATE, "v0.8 更新", "10-30",
                    "· 全新 SAO 界面与 Link Start 开场\n· 二刀流动作与剑技光效\n· 浮游城地图与传送门"),
            new SaoData.News(SaoData.NewsKind.NOTICE, "服务器维护", "10-28",
                    "10-29 02:00 至 04:00 维护，期间无法连接。"));
    /// Pretend ping.
    private final SimpleObjectProperty<SaoData.@Nullable ServerStatus> server = new SimpleObjectProperty<>(
            new SaoData.ServerStatus(true, 23, 100, 38, "刀剑神域·誓约 · 第一层", List.of("Kirito", "Asuna", "Klein", "Agil")));
    /// Pretend saves.
    private final ObservableList<SaoData.World> worlds = FXCollections.observableArrayList(
            new SaoData.World("剑技练习场", Path.of("saves/practice"), System.currentTimeMillis() - 3_600_000L, 182_000_000L, null, "创造"),
            new SaoData.World("新世界", Path.of("saves/new"), System.currentTimeMillis() - 86_400_000L * 3, 64_000_000L, null, "生存"));
    /// Pretend backups.
    private final ObservableList<SaoData.Backup> backups = FXCollections.observableArrayList(
            new SaoData.Backup(Path.of("backups/practice-1.zip"), Path.of("saves/practice"), System.currentTimeMillis() - 86_400_000L, 90_000_000L));
    /// Pretend shader packs.
    private final ObservableList<SaoData.Pack> shaders = FXCollections.observableArrayList(
            new SaoData.Pack("Complementary Reimagined", Path.of("shaderpacks/c.zip"), "柔和的光照与水面", true, null),
            new SaoData.Pack("BSL Shaders", Path.of("shaderpacks/bsl.zip"), "经典光影", false, null),
            new SaoData.Pack("MakeUp Ultra Fast", Path.of("shaderpacks/mu.zip"), "低配也能开", false, null));
    /// Pretend resource packs.
    private final ObservableList<SaoData.Pack> resources = FXCollections.observableArrayList(
            new SaoData.Pack("誓约 界面", Path.of("resourcepacks/oath.zip"), "整合包必需", true, null),
            new SaoData.Pack("Fresh Animations", Path.of("resourcepacks/fa.zip"), "生物动画", true, null),
            new SaoData.Pack("Faithful 32x", Path.of("resourcepacks/f32.zip"), "高清材质", false, null));
    /// Pretend screenshots.
    private final ObservableList<Path> shots = FXCollections.observableArrayList();
    /// Pretend log.
    private final ObservableList<String> log = FXCollections.observableArrayList(
            "[15:52:01] [main/INFO]: ModLauncher running: NeoForge 47.1.106",
            "[15:52:03] [main/INFO]: Loading 228 mods",
            "[15:52:19] [Render thread/INFO]: SAO Interface 0.8 ready",
            "[15:52:20] [Render thread/WARN]: Missing sound for event: minecraft:item.goat_horn.play",
            "[15:53:41] [Render thread/ERROR]: Unable to allocate 512 MiB for chunk buffers",
            "[15:53:41] [Render thread/FATAL]: Out of memory");
    /// Pretend crash.
    private final SimpleObjectProperty<SaoData.@Nullable Crash> crash = new SimpleObjectProperty<>(
            new SaoData.Crash("内存不足：分配给游戏的内存太少", "java.lang.OutOfMemoryError: Java heap space", Path.of("crash-reports/crash.txt")));
    /// Pretend room.
    private final SimpleObjectProperty<SaoData.Room> room = new SimpleObjectProperty<>(SaoData.Room.OFF);
    /// Pretend Javas.
    private final ObservableList<SaoData.JavaRuntime> javas = FXCollections.observableArrayList(
            new SaoData.JavaRuntime("17.0.12", Path.of("/Library/Java/jdk-17/bin/java"), "Eclipse Adoptium", false),
            new SaoData.JavaRuntime("17.0.9", Path.of("~/.sao-oath-launcher/java/17/bin/java"), "Mojang", true));
    /// Pretend downloads.
    private final ObservableList<SaoData.Task> tasks = FXCollections.observableArrayList(
            new SaoData.Task("NeoForge 47.1.106", 1, 0, true),
            new SaoData.Task("资源文件 3112/4870", 0.64, 6_400_000L, false),
            new SaoData.Task("刀剑神域复兴 2.3.jar", 0.18, 2_100_000L, false));
    /// Pretend preferences.
    private final Map<String, String> prefs = new HashMap<>();
    /// Pretend settings.
    private boolean autoMemory;
    private @Nullable Path java;
    private SaoData.Source source = SaoData.Source.AUTO;
    private int threads = 64;
    private SaoData.GameWindow window = new SaoData.GameWindow(1600, 900, false);
    private String jvm = "";

    @Override public ObservableList<SaoData.News> news() { return news; }
    @Override public ReadOnlyObjectProperty<SaoData.@Nullable ServerStatus> serverStatus() { return server; }
    @Override public ObservableList<SaoData.World> worlds() { return worlds; }
    @Override public ObservableList<SaoData.Backup> backups() { return backups; }
    @Override public void backupWorld(SaoData.World w) {
        backups.add(0, new SaoData.Backup(Path.of("backups/" + w.name() + ".zip"), w.folder(), System.currentTimeMillis(), w.sizeBytes() / 2));
    }
    @Override public ObservableList<SaoData.Pack> shaderPacks() { return shaders; }
    @Override public void setShader(SaoData.@Nullable Pack pack) {
        shaders.replaceAll(p -> new SaoData.Pack(p.name(), p.file(), p.description(), pack != null && p.file().equals(pack.file()), p.icon()));
    }
    @Override public ObservableList<SaoData.Pack> resourcePacks() { return resources; }
    @Override public void setResourcePackEnabled(SaoData.Pack pack, boolean on) {
        resources.replaceAll(p -> p.file().equals(pack.file()) ? new SaoData.Pack(p.name(), p.file(), p.description(), on, p.icon()) : p);
    }
    @Override public ObservableList<Path> screenshots() {
        if (shots.isEmpty()) {
            // The pack's wallpapers stand in for screenshots.
            try {
                Path dir = Files.createTempDirectory("sao-fake-shots");
                for (int i = 1; i <= 8; i++) {
                    String n = String.format("wallpaper_%02d.jpg", i);
                    try (InputStream in = SaoFakeService.class.getResourceAsStream("/assets/sao-launcher/wallpapers/" + n)) {
                        if (in == null) continue;
                        Path f = dir.resolve(n);
                        Files.copy(in, f, StandardCopyOption.REPLACE_EXISTING);
                        shots.add(f);
                    }
                }
            } catch (java.io.IOException ignored) {
                // No shots then.
            }
        }
        return shots;
    }
    @Override public ObservableList<String> gameLog() { return log; }
    @Override public ReadOnlyObjectProperty<SaoData.@Nullable Crash> lastCrash() { return crash; }
    @Override public ReadOnlyObjectProperty<SaoData.Room> room() { return room; }
    @Override public void hostRoom() {
        room.set(new SaoData.Room(SaoData.RoomState.PREPARING, null, 1, "准备联机通道"));
        Timeline t = new Timeline(new KeyFrame(Duration.millis(1500), e -> room.set(new SaoData.Room(SaoData.RoomState.HOSTING, "U/7KQ2-9XWA-3MPD", 1, "把房间码发给朋友"))));
        t.play();
    }
    @Override public void joinRoom(String code) {
        room.set(new SaoData.Room(SaoData.RoomState.JOINING, code, 0, "连接中"));
        Timeline t = new Timeline(new KeyFrame(Duration.millis(1500), e -> room.set(new SaoData.Room(SaoData.RoomState.JOINED, code, 2, "在多人游戏里选择「誓约联机」"))));
        t.play();
    }
    @Override public void leaveRoom() { room.set(SaoData.Room.OFF); }
    /// Shows a room state directly (screenshots).
    public void forceRoom(SaoData.Room r) { room.set(r); }
    @Override public ObservableList<SaoData.JavaRuntime> javas() { return javas; }
    @Override public @Nullable Path java() { return java; }
    @Override public void setJava(@Nullable Path p) { java = p; }
    @Override public SaoData.Source downloadSource() { return source; }
    @Override public void setDownloadSource(SaoData.Source s) { source = s; }
    @Override public int downloadThreads() { return threads; }
    @Override public void setDownloadThreads(int t) { threads = t; }
    @Override public ObservableList<SaoData.Task> tasks() { return tasks; }
    @Override public boolean autoMemory() { return autoMemory; }
    @Override public void setAutoMemory(boolean a) { autoMemory = a; }
    @Override public int systemMemoryMiB() { return 32768; }
    @Override public SaoData.GameWindow gameWindow() { return window; }
    @Override public void setGameWindow(SaoData.GameWindow w) { window = w; }
    @Override public String jvmArgs() { return jvm; }
    @Override public void setJvmArgs(String a) { jvm = a; }
    @Override public String preference(String key, String fallback) { return prefs.getOrDefault(key, fallback); }
    @Override public void setPreference(String key, String value) { prefs.put(key, value); }

    // ------------------------------------------------------------------ 誓约账号 (pretend)

    /// The pretend account.
    final SimpleObjectProperty<SaoData.@Nullable OathAccount> oath = new SimpleObjectProperty<>();
    /// The pretend remembered name.
    @Nullable String remembered = "Kirito_07";
    /// The pretend library.
    final ObservableList<SaoData.SavedSkin> library = FXCollections.observableArrayList();

    /// Signs the pretend account in, verified or waiting for its code (screenshots).
    ///
    /// @param verified whether the mailbox is verified
    void forceOath(boolean verified) {
        card.set(new SaoData.PlayerCard(24, 1, 12_400, System.currentTimeMillis() - 20 * 3600_000L, 12 * 3600 + 40 * 60,
                List.of("Asuna", "Klein")));
        oath.set(new SaoData.OathAccount("Kirito_07", "0f3b6a1c2d4e4f50a1b2c3d4e5f60718", "ja***@163.com", verified,
                false, verified ? 0 : 47 * 3600 + 12 * 60, null));
    }

    /// Puts this many pretend friends online on the player card (screenshots: 0, 2, 5, 30).
    ///
    /// @param n how many
    void forceFriends(int n) {
        String[] names = {"Asuna", "Klein", "Agil", "Silica", "Lisbeth", "Leafa", "Sinon", "Yui", "Eugeo", "Alice"};
        List<String> online = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) online.add(i < names.length ? names[i] : "Player" + (i + 1));
        SaoData.PlayerCard c = card.get();
        card.set(new SaoData.PlayerCard(24, 1, 12_400, c == null ? System.currentTimeMillis() - 20 * 3600_000L : c.lastPlayed(),
                12 * 3600 + 40 * 60, online));
    }

    /// Renames today (pretend limit of three).
    private int renames;

    // ---- pretend friends and chat
    private static long ago(long minutes) {
        return System.currentTimeMillis() - minutes * 60_000L;
    }

    private final ObservableList<SaoData.Friend> friends = FXCollections.observableArrayList(
            new SaoData.Friend("Asuna", SaoData.Presence.GAME, "誓约", null),
            new SaoData.Friend("Klein", SaoData.Presence.GAME, "单人游戏", null),
            new SaoData.Friend("Agil", SaoData.Presence.LAUNCHER, "", null),
            new SaoData.Friend("Silica", SaoData.Presence.OFFLINE, "", null),
            new SaoData.Friend("Lisbeth", SaoData.Presence.OFFLINE, "", null));
    private final ObservableList<SaoData.FriendRequest> requests = FXCollections.observableArrayList(
            new SaoData.FriendRequest("Sachi", true));
    private final ObservableList<SaoData.Chat> chats = FXCollections.observableArrayList(
            new SaoData.Chat("d-asuna", "Asuna", false, List.of("Kirito_07", "Asuna"), 2, "今晚八点打第一层 boss？", ago(3)),
            new SaoData.Chat("g-fuurinkazan", "风林火山", true, List.of("Kirito_07", "Klein", "Agil", "Asuna"), 5, "Klein：谁来帮我刷素材", ago(12)),
            new SaoData.Chat("d-agil", "Agil", false, List.of("Kirito_07", "Agil"), 0, "店里进了新货", ago(60 * 26)));
    private final Map<String, ObservableList<SaoData.ChatMessage>> messages = new HashMap<>();
    {
        messages.put("d-asuna", FXCollections.observableArrayList(
                new SaoData.ChatMessage("Asuna", "在吗？", ago(40), false, false),
                new SaoData.ChatMessage("Kirito_07", "在，刚下线", ago(38), true, false),
                new SaoData.ChatMessage("Asuna", "第一层的 boss 房找到了", ago(5), false, false),
                new SaoData.ChatMessage("Asuna", "今晚八点打第一层 boss？", ago(3), false, false)));
        messages.put("g-fuurinkazan", FXCollections.observableArrayList(
                new SaoData.ChatMessage("Agil", "大家装备修好了吗", ago(30), false, false),
                new SaoData.ChatMessage("Kirito_07", "修好了", ago(28), true, false),
                new SaoData.ChatMessage("Asuna", "我这边也好了", ago(20), false, false),
                new SaoData.ChatMessage("Klein", "谁来帮我刷素材", ago(12), false, false)));
        messages.put("d-agil", FXCollections.observableArrayList(
                new SaoData.ChatMessage("Agil", "店里进了新货", ago(60 * 26), false, false)));
    }

    @Override public ObservableList<SaoData.Friend> friends() { return friends; }
    @Override public ObservableList<SaoData.FriendRequest> friendRequests() { return requests; }
    @Override public ObservableList<SaoData.Chat> chats() { return chats; }

    @Override
    public ObservableList<SaoData.ChatMessage> messages(String chatId) {
        return messages.computeIfAbsent(chatId, k -> FXCollections.observableArrayList());
    }

    @Override
    public CompletableFuture<Void> addFriend(String name) {
        if (name.equalsIgnoreCase("Kirito_07")) return CompletableFuture.failedFuture(new SaoAccountException("不能加自己", "self"));
        if (friends.stream().anyMatch(f -> f.name().equalsIgnoreCase(name))) return CompletableFuture.failedFuture(new SaoAccountException("已经是好友了", "already"));
        requests.add(new SaoData.FriendRequest(name, false));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> answerFriend(String name, boolean accept) {
        requests.removeIf(r -> r.name().equals(name));
        if (accept) friends.add(new SaoData.Friend(name, SaoData.Presence.LAUNCHER, "", null));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> removeFriend(String name) {
        friends.removeIf(f -> f.name().equals(name));
        chats.removeIf(c -> !c.group() && c.title().equals(name));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> blockPlayer(String name) {
        return removeFriend(name);
    }

    @Override
    public CompletableFuture<String> directChat(String name) {
        for (SaoData.Chat c : chats) if (!c.group() && c.title().equals(name)) return CompletableFuture.completedFuture(c.id());
        String id = "d-" + name.toLowerCase(Locale.ROOT);
        chats.add(new SaoData.Chat(id, name, false, List.of("Kirito_07", name), 0, "", 0));
        return CompletableFuture.completedFuture(id);
    }

    @Override
    public CompletableFuture<String> createGroup(String title, List<String> members) {
        String id = "g-" + System.nanoTime();
        List<String> all = new java.util.ArrayList<>(members);
        all.add(0, "Kirito_07");
        chats.add(0, new SaoData.Chat(id, title, true, all, 0, "", System.currentTimeMillis()));
        return CompletableFuture.completedFuture(id);
    }

    @Override
    public CompletableFuture<Void> leaveGroup(String chatId) {
        chats.removeIf(c -> c.id().equals(chatId));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> inviteToGroup(String chatId, List<String> members) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendMessage(String chatId, String text) {
        messages(chatId).add(new SaoData.ChatMessage("Kirito_07", text, System.currentTimeMillis(), true, false));
        for (int i = 0; i < chats.size(); i++) {
            SaoData.Chat c = chats.get(i);
            if (c.id().equals(chatId)) {
                chats.remove(i);
                chats.add(0, new SaoData.Chat(c.id(), c.title(), c.group(), c.members(), 0, text, System.currentTimeMillis()));
                break;
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void markRead(String chatId) {
        for (int i = 0; i < chats.size(); i++) {
            SaoData.Chat c = chats.get(i);
            if (c.id().equals(chatId) && c.unread() > 0)
                chats.set(i, new SaoData.Chat(c.id(), c.title(), c.group(), c.members(), 0, c.lastText(), c.lastTime()));
        }
    }

    /// Pretend player card.
    private final ObjectProperty<SaoData.@Nullable PlayerCard> card = new SimpleObjectProperty<>();

    @Override
    public ReadOnlyObjectProperty<SaoData.@Nullable PlayerCard> playerCard() {
        return card;
    }

    @Override
    public ReadOnlyObjectProperty<SaoData.@Nullable OathAccount> oathAccount() {
        return oath;
    }

    @Override
    public @Nullable String rememberedOathName() {
        return remembered;
    }

    @Override
    public CompletableFuture<Void> oathLogin(String name, char @Nullable [] password, boolean remember) {
        if (password != null && password.length == 0) return fail("请输入密码");
        remembered = remember ? name : null;
        oath.set(new SaoData.OathAccount(name, "0f3b6a1c2d4e4f50a1b2c3d4e5f60718", "ja***@163.com", true, false, 0, null));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> oathRegister(String name, String email, char[] password) {
        oath.set(new SaoData.OathAccount(name, "0f3b6a1c2d4e4f50a1b2c3d4e5f60718", mask(email), false, false,
                48 * 3600, null));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> oathVerify(String code) {
        if (!code.equals("123456")) return fail("验证码不对或已过期");
        SaoData.@Nullable OathAccount a = oath.get();
        if (a != null) oath.set(new SaoData.OathAccount(a.name(), a.profileId(), a.maskedEmail(), true, false, 0, a.premiumName()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> oathResend() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> oathForgot(String name) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> oathReset(String name, String code, char[] password) {
        return code.equals("123456") ? CompletableFuture.completedFuture(null) : fail("验证码不对或已过期");
    }

    @Override
    public CompletableFuture<Void> oathChangeEmail(String email) {
        SaoData.@Nullable OathAccount a = oath.get();
        if (a != null) oath.set(new SaoData.OathAccount(a.name(), a.profileId(), mask(email), false, false, 48 * 3600, a.premiumName()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Boolean> oathNameAvailable(String name) {
        return CompletableFuture.completedFuture(!List.of("kirito", "asuna", "klein", "agil").contains(name.toLowerCase(Locale.ROOT)));
    }

    @Override
    public CompletableFuture<Void> oathRename(String name) {
        if (!oathNameAvailable(name).join()) return CompletableFuture.failedFuture(new SaoAccountException("这个昵称已经有人用了", "nameTaken"));
        if (++renames > 3) return CompletableFuture.failedFuture(new SaoAccountException("今天已经改了 3 次，明天再来", "renameLimit"));
        SaoData.@Nullable OathAccount a = oath.get();
        if (a != null) oath.set(new SaoData.OathAccount(name, a.profileId(), a.maskedEmail(), a.emailVerified(), a.locked(), a.graceSeconds(), a.premiumName()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> oathChangePassword(char[] password, char[] newPassword) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> oathLinkPremium() {
        SaoData.@Nullable OathAccount a = oath.get();
        if (a != null) oath.set(new SaoData.OathAccount(a.name(), a.profileId(), a.maskedEmail(), a.emailVerified(), a.locked(), a.graceSeconds(), "Kirito"));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> oathPremiumLogin() {
        oath.set(new SaoData.OathAccount("Kirito", "0f3b6a1c2d4e4f50a1b2c3d4e5f60718", "", false, false, 48 * 3600, "Kirito"));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void oathLogout() {
        oath.set(null);
        remembered = null;
    }

    @Override
    public void linkStartOath() {
        linkStart(null, null);
    }

    @Override
    public void linkStartOffline(String name) {
        linkStart(null, null);
    }

    @Override
    public @Nullable Path oathSkin() {
        return skin;
    }

    @Override
    public CompletableFuture<Void> uploadSkin(@Nullable Path png, boolean slim) {
        setSkin(png, slim);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public ObservableList<SaoData.SavedSkin> savedSkins() {
        return library;
    }

    @Override
    public SaoData.SavedSkin saveSkin(String name, byte[] png, boolean slim) {
        try {
            Path f = Files.createTempFile("sao-fake-skin-", ".png");
            Files.write(f, png);
            f.toFile().deleteOnExit();
            SaoData.SavedSkin k = new SaoData.SavedSkin(name, f, slim, System.currentTimeMillis());
            library.add(0, k);
            return k;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void deleteSavedSkin(SaoData.SavedSkin skin) {
        library.remove(skin);
    }

    /// A failed pretend call.
    private static CompletableFuture<Void> fail(String message) {
        return CompletableFuture.failedFuture(new SaoAccountException(message));
    }

    /// Masks a mailbox the way the server does.
    private static String mask(String email) {
        int at = email.indexOf('@');
        return at < 2 ? email : email.substring(0, 2) + "***" + email.substring(at);
    }
}
