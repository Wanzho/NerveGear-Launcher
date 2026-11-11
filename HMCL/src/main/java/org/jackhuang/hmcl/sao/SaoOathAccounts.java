/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.glavo.uuid.UUIDs;
import org.jackhuang.hmcl.auth.*;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorServer;
import org.jackhuang.hmcl.auth.yggdrasil.GameProfile;
import org.jackhuang.hmcl.auth.yggdrasil.YggdrasilSession;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/// Serialized account state machine, separate from pack installation and the UI, with memory-only bearer sessions.
@NotNullByDefault
final class SaoOathAccounts implements AutoCloseable {
    /// Acquires a real Microsoft/Minecraft session locally; tests inject a fixture without browser or public requests.
    @FunctionalInterface
    @NotNullByDefault
    interface PremiumProof {
        /// Returns the original Microsoft session; its access token is sent only to the original Mojang join endpoint.
        AuthInfo session() throws Exception;
    }

    /// One checked operation on the account worker.
    @FunctionalInterface
    @NotNullByDefault
    private interface Operation {
        /// Executes the operation; public failures are translated before future completion.
        void run() throws Exception;
    }

    /// Ephemeral session bound to the endpoint that issued it; never written to any settings/account store.
    @NotNullByDefault
    private record Session(URI root, String access, String client, String name, String id) {
        /// Never formats token or player data for diagnostics.
        @Override public String toString() { return "OathSession[redacted]"; }
    }

    /// Schedules optional initial-refresh polling; every HTTP operation still runs on the serialized account worker.
    private final ScheduledExecutorService appearanceTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "argus-appearance-poll"); thread.setDaemon(true); return thread;
    });
    /// Limits initial/background polling to one scheduled callback per active account.
    private final AtomicBoolean appearancePolling = new AtomicBoolean();
    /// Owns all HTTP calls and sensitive state transitions.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sao-oath-accounts"); thread.setDaemon(true); return thread;
    });
    /// Transport enforcing origin, TLS, bounds, locale and safe failure text.
    private final SaoAccountClient client;
    /// Last applied verified account API, null to use explicit deployment/pack/default resolution.
    private volatile @Nullable String signedAccountApi;
    /// Resolves explicit deployment overrides and legacy pack/default account roots.
    private final Supplier<URI> fallbackEndpoint;
    /// Live social engine shares only the current memory-only ARGUS session.
    final SaoFriends friends;
    /// Root enclosing only this launcher's credentials, texture cache and libraries.
    private final Path root;
    /// Launcher data root that owns the game instance; launch tickets are hardened from here down.
    private final Path launcherRoot;
    /// Explicit protected storage implementation, replaceable in isolated tests.
    private final SaoOathCredentials credentials;
    /// Original local Microsoft session acquisition.
    private final PremiumProof premium;
    /// Production uses the original Mojang URI; fixtures may inject only literal loopback.
    private final URI mojangJoin;
    /// Starts the existing pack pipeline only after account refresh and verification checks.
    private final Consumer<Account> launch;
    /// Short sanitized public error sink for non-future actions.
    private final Consumer<String> errors;
    /// Pinned bundled injector; no public download is needed to prepare an account.
    private final SaoOathInjector injector;
    /// Observable account state, published with future completion on JavaFX.
    private final ReadOnlyObjectWrapper<SaoData.@Nullable OathAccount> account = new ReadOnlyObjectWrapper<>();
    /// Server statistics merged with local process time, published alongside account state.
    private final ReadOnlyObjectWrapper<SaoData.@Nullable PlayerCard> card = new ReadOnlyObjectWrapper<>();
    /// Observable source preferences and safe preview descriptors, published with the account snapshot.
    private final ReadOnlyObjectWrapper<SaoTextureState> inherited = new ReadOnlyObjectWrapper<>(SaoTextureState.empty());
    /// Worker-owned appearance snapshot, cleared together with bearer state.
    private volatile SaoTextureState currentInherited = SaoTextureState.empty();
    /// Latest card never escapes without an immutable friends list.
    private volatile SaoData.@Nullable PlayerCard currentCard;
    /// Per-user stable-profile process accounting; removed by the existing uninstall root plan.
    private final SaoOathPlayTime playTime;
    /// Active process owner; signout never changes which ARGUS ID receives elapsed time.
    private volatile @Nullable GameRun gameRun;
    /// One running process's namespace and monotonic recorder.
    @NotNullByDefault
    private record GameRun(URI api, String id, SaoOathPlayTime.Run time) { }
    /// Latest immutable library snapshot, populated outside JavaFX callbacks.
    private volatile @org.jetbrains.annotations.Unmodifiable List<SaoData.SavedSkin> libraryEntries = List.of();
    /// Mutable skin library backing the stable read-only view.
    private final ObservableList<SaoData.SavedSkin> saved = FXCollections.observableArrayList();
    /// Screen-facing stable read-only library.
    private final @UnmodifiableView ObservableList<SaoData.SavedSkin> savedView = FXCollections.unmodifiableObservableList(saved);
    /// Internal state does not depend on queued JavaFX publication timing.
    private volatile SaoData.@Nullable OathAccount current;
    /// Session exists only until logout, revocation, refresh failure or close.
    private volatile @Nullable Session session;
    /// Protected remembered name copied to memory for the login screen; password is read only for explicit login.
    private volatile @Nullable String remembered;
    /// Current server-profile skin; cleared immediately when the account changes or signs out.
    private volatile @Nullable Path skin;
    /// Arm model read from the current server profile texture metadata.
    private volatile boolean slim;
    /// Whether the signed-in account is passwordless (created by Microsoft sign-in); it proves Microsoft ownership
    /// instead of a current password when changing the mailbox.
    private volatile boolean premiumOnly;
    /// Per-user local library, available independently of account sign-in.
    private final SaoOathSkins library;
    /// Current server profile cache, separated from the per-user local library.
    private volatile @Nullable SaoOathSkins profileCache;
    /// Cancels stale queued/publishing operations after logout or close.
    private final AtomicLong generation = new AtomicLong();
    /// Serializes acceptance of account/library writes with the uninstall fence.
    private final Object gate = new Object();
    /// Refuses new work once uninstall has atomically observed an idle account worker.
    private boolean frozen;
    /// Set while a Link Start account refresh is queued or running.
    private final AtomicBoolean launching = new AtomicBoolean();
        /// Stops accepting work after close or uninstall.
    private final AtomicBoolean closed = new AtomicBoolean();
    /// Counts queued and active operations so uninstall cannot race credential or library writes.
    private final AtomicInteger pending = new AtomicInteger();
    /// Password buffers owned by queued operations, wiped even when the executor is stopped before execution.
    private final Set<char[]> passwords = ConcurrentHashMap.newKeySet();
    /// Futures awaiting worker or JavaFX completion, failed when the service closes.
    private final Set<CompletableFuture<Void>> futures = ConcurrentHashMap.newKeySet();

    /// Creates a production controller using the original, unrewriteable Mojang endpoint.
    SaoOathAccounts(Path root, Supplier<URI> endpoint, Supplier<String> language, SaoOathCredentials credentials,
                    PremiumProof premium, Consumer<Account> launch, Consumer<String> errors) {
        this(root, endpoint, language, credentials, premium,
                SaoAccountClient.originalMojangJoin(), launch, errors);
    }

    /// Fully injectable controller for literal-loopback headless contracts, without a game process or windows.
    SaoOathAccounts(Path root, Supplier<URI> endpoint, Supplier<String> language, SaoOathCredentials credentials,
                    PremiumProof premium, URI mojangJoin, Consumer<Account> launch, Consumer<String> errors) {
        this(root, root, endpoint, language, credentials, premium, mojangJoin, launch, errors);
    }

    /// Production layout: per-user account storage and the launcher data root that holds the instance may differ.
    SaoOathAccounts(Path root, Path launcherRoot, Supplier<URI> endpoint, Supplier<String> language, SaoOathCredentials credentials,
                    PremiumProof premium, URI mojangJoin, Consumer<Account> launch, Consumer<String> errors) {
        this.root = root; this.launcherRoot = launcherRoot; this.fallbackEndpoint = endpoint;
        this.client = new SaoAccountClient(this::accountEndpoint, language); this.credentials = credentials;
        this.friends = new SaoFriends(root, client, errors);
        this.premium = premium; this.mojangJoin = mojangJoin; this.launch = launch; this.errors = errors;
        this.injector = new SaoOathInjector(root);
        this.playTime = new SaoOathPlayTime(root);
        this.library = new SaoOathSkins(root);
        try { libraryEntries = library.list(); } catch (IOException ignored) { }
        publish(this::publishState);
        try {
            SaoOathCredentials.@Nullable Credential credential = credentials.read();
            if (credential != null) { remembered = credential.name(); Arrays.fill(credential.password(), '\0'); }
        } catch (IOException failure) { errors.accept("读取已保存账号失败，请重新输入密码"); }
    }

    /// Resolves the selected signed root; the engine's fallback retains explicit test/deployment override priority.
    URI accountEndpoint() {
        @Nullable String selected = signedAccountApi;
        return selected == null ? fallbackEndpoint.get() : SaoAccountClient.checkedRoot(selected);
    }

    /// Applies a verified root only while signed out and idle, atomically with account-operation submission.
    /// A skipped change is retried by the next verified refresh or applied from cache on the next launcher start.
    boolean applyAccountApi(@Nullable String selected) {
        synchronized (gate) {
            if (Objects.equals(selected, signedAccountApi)) return true;
            if (session != null || pending.get() != 0 || closed.get() || frozen) {
                System.getLogger(SaoOathAccounts.class.getName()).log(System.Logger.Level.INFO,
                        "Signed account API change deferred until signed out and idle or launcher restart");
                return false;
            }
            signedAccountApi = selected;
            System.getLogger(SaoOathAccounts.class.getName()).log(System.Logger.Level.INFO,
                    "Signed account API applied: " + (selected == null ? "deployment/pack/default" : selected));
            return true;
        }
    }

    /// The current account property; only refreshed server values are published.
    ReadOnlyObjectProperty<SaoData.@Nullable OathAccount> account() { return account.getReadOnlyProperty(); }
    /// Returns the stable card property, cleared on signout.
    ReadOnlyObjectProperty<SaoData.@Nullable PlayerCard> playerCard() { return card.getReadOnlyProperty(); }
    /// Returns the observable current source choices and inherited previews.
    ReadOnlyObjectProperty<SaoTextureState> inheritedTextures() { return inherited.getReadOnlyProperty(); }
    /// Chooses the uploaded or linked premium skin without replacing either image.
    CompletableFuture<Void> setSkinSource(String source) { return submit(() -> { Session active = requireSession(); currentInherited = client.textureChoices(active.root(), source, null, active.access()); refreshSkin(requireSession()); }); }
    /// Chooses a cape provider, own upload or none, preserving every other source slot.
    CompletableFuture<Void> setCapeSource(String source) { return submit(() -> { Session active = requireSession(); currentInherited = client.textureChoices(active.root(), null, source, active.access()); refreshSkin(requireSession()); }); }
    /// Refreshes inherited images on the account worker, polling queued work without blocking JavaFX or launching a browser.
    CompletableFuture<Void> refreshInheritedTextures() { return submit(() -> {
        Session active = requireSession(); currentInherited = client.refreshInheritedTextures(active.root(), active.access());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (currentInherited.status().equals("syncing") && System.nanoTime() < deadline) {
            SaoTextureState snapshot = currentInherited; publish(() -> inherited.set(snapshot));
            if (closed.get() || session != active) throw new SaoAccountException("账号操作已取消", "cancelled");
            Thread.sleep(300); currentInherited = client.inheritedTextures(active.root(), active.access());
        }
        refreshSkin(active);
        if (currentInherited.status().equals("syncing")) { scheduleAppearancePoll(active); throw new SaoAccountException("同步中", "textureSyncPending"); }
        if (currentInherited.status().equals("failed")) throw new SaoAccountException("同步失败", "textureSyncFailed");
    }); }
    /// Returns the protected remembered name from memory, or null.
    @Nullable String rememberedName() { return remembered; }
    /// Returns the current account's downloaded texture, or null for the server's default skin.
    @Nullable Path skin() { return skin; }
    /// Returns the current profile texture arm model.
    boolean skinSlim() { return slim; }
    /// Returns the stable read-only per-user library.
    @UnmodifiableView ObservableList<SaoData.SavedSkin> savedSkins() { return savedView; }
    /// Whether queued account work must finish before uninstall.
    boolean busy() { return pending.get() != 0; }
    /// Freezes new account/library writes only when no queued or active operation remains.
    boolean tryFreeze() { synchronized (gate) { if (closed.get() || frozen || pending.get() != 0) return false; frozen = true; return true; } }
    /// Restores account operations after an uninstall failure before the terminal commit point.
    void unfreeze() { synchronized (gate) { if (!closed.get()) frozen = false; } }
    /// Identifies the storage provider without exposing credentials.
    String credentialBackend() { return credentials.backend(); }

    /// Sends the entered nickname or mailbox unchanged, then refreshes authoritative account state.
    /// Remembering stores the returned current nickname, including after mailbox or displaced-alias sign-in.
    CompletableFuture<Void> login(String name, char @Nullable [] supplied, boolean remember) {
        char @Nullable [] password = own(supplied);
        long expected = generation.get();
        return submit(() -> {
            char @Nullable [] actual = password;
            try {
                clear();
                if (actual == null) {
                    SaoOathCredentials.@Nullable Credential stored = credentials.read();
                    if (stored == null) throw new SaoAccountException("请输入密码", "missingPassword");
                    actual = stored.password();
                    if (!stored.name().equalsIgnoreCase(name)) throw new SaoAccountException("请输入密码", "missingPassword");
                }
                if (!remember) forget();
                JsonObject body = object("username", name, "password", new String(actual), "clientToken", UUID.randomUUID().toString());
                JsonObject reply;
                try { reply = client.json("POST", "sao/login", body, null); }
                finally { body.remove("password"); }
                accept(reply);
                refresh();
                if (remember) synchronized (gate) {
                    if (expected != generation.get()) throw new SaoAccountException("账号操作已取消", "cancelled");
                    credentials.write(requireSession().name(), actual); remembered = requireSession().name();
                }
            } finally {
                if (actual != null) Arrays.fill(actual, '\0');
                if (!remember) forget();
            }
        }, password);
    }

    /// Registers without remembering a password, then obtains authoritative account and texture state.
    CompletableFuture<Void> register(String name, String email, char[] supplied) {
        char[] password = Objects.requireNonNull(own(supplied));
        return submit(() -> {
            clear();
            JsonObject body = object("username", name, "email", email, "password", new String(password));
            try { accept(client.json("POST", "sao/register", body, null)); }
            finally { body.remove("password"); }
            refresh();
        }, password);
    }

    /// Consumes an email code and refreshes the unlocked/verification state from the server.
    CompletableFuture<Void> verify(String code) { return authenticated("sao/verify", object("code", code), false); }
    /// Requests another verification message without claiming actual inbox delivery.
    CompletableFuture<Void> resend() { return authenticated("sao/resend", new JsonObject(), false); }
    /// Uses the non-enumerating recovery operation and refreshes any existing session.
    CompletableFuture<Void> forgot(String name) {
        return submit(() -> { client.json("POST", "sao/forgot", object("username", name), null); if (session != null) refresh(); });
    }

    /// Changes a recovered password; matching remembered credentials and revoked sessions are removed.
    CompletableFuture<Void> reset(String name, String code, char[] supplied) {
        char[] password = Objects.requireNonNull(own(supplied));
        return submit(() -> {
            JsonObject body = object("username", name, "code", code, "password", new String(password));
            try { client.json("POST", "sao/reset", body, null); }
            finally { body.remove("password"); }
            if (remembered != null && remembered.equalsIgnoreCase(name)) forget();
            if (session != null && requireSession().name().equalsIgnoreCase(name)) refreshRevoked();
            else if (session != null) refresh();
        }, password);
    }

    /// Changes the mailbox after re-proving the current password, then observes the server's session revocation. A
    /// passwordless (Microsoft-created) account leaves the password empty and proves Microsoft ownership instead.
    /// A bearer alone is never enough (sao_auth 1.9.3), so a mod holding any token cannot move the mailbox.
    CompletableFuture<Void> changeEmail(String email, char[] supplied) {
        if (supplied.length == 0) {
            if (!premiumOnly) return CompletableFuture.failedFuture(new SaoAccountException("请输入当前密码", "missingPassword"));
            return submit(() -> {
                Session active = requireSession();
                JsonObject proof = premiumProof(premium.session());
                JsonObject body = object("email", email, "premiumToken", required(proof, "premiumToken"));
                try { client.json("POST", "sao/email", body, active.access()); }
                finally { body.remove("premiumToken"); proof.remove("premiumToken"); }
                refreshRevoked();
            });
        }
        char[] password = Objects.requireNonNull(own(supplied));
        return submit(() -> {
            Session active = requireSession();
            JsonObject body = object("email", email, "password", new String(password));
            try { client.json("POST", "sao/email", body, active.access()); }
            finally { body.remove("password"); }
            refreshRevoked();
        }, password);
    }

    /// Proves the current password, changes it, forgets the old protected pair and observes token revocation.
    CompletableFuture<Void> changePassword(char[] supplied, char[] suppliedNew) {
        char[] password = Objects.requireNonNull(own(supplied));
        char[] next = Objects.requireNonNull(own(suppliedNew));
        return submit(() -> {
            Session active = requireSession();
            JsonObject body = object("username", active.name(), "password", new String(password), "newPassword", new String(next));
            try { client.json("POST", "sao/password", body, null); }
            finally { body.remove("password"); body.remove("newPassword"); }
            forget(); refreshRevoked();
        }, password, next);
    }

    /// Obtains challenge, performs original Mojang join locally, completes the proof and links with the SAO bearer.
    CompletableFuture<Void> linkPremium() {
        return submit(() -> {
            Session active = requireSession();
            AuthInfo microsoft = premium.session();
            if (!microsoft.getUsername().equals(active.name())) throw new SaoAccountException("请使用同名正版账号验证", "premiumNameMismatch");
            JsonObject complete = premiumProof(microsoft);
            JsonObject link = object("premiumToken", required(complete, "premiumToken"));
            try { client.json("POST", "sao/premium/link", link, active.access()); }
            finally { link.remove("premiumToken"); }
            refreshRevoked();
        });
    }

    /// Signs in with a fresh Microsoft ownership proof, creating a linked passwordless ARGUS ID on first use.
    CompletableFuture<Void> premiumLogin() {
        return submit(() -> {
            clear();
            JsonObject proof = premiumProof(premium.session());
            JsonObject body = object("premiumToken", required(proof, "premiumToken"), "clientToken", UUID.randomUUID().toString());
            try { accept(client.json("POST", "sao/premium/login", body, null)); }
            finally { body.remove("premiumToken"); }
            forget(); refresh();
        });
    }

    /// Keeps Microsoft session credentials exclusively on the original Mojang join exchange.
    private JsonObject premiumProof(AuthInfo microsoft) throws SaoAccountException {
        JsonObject challenge = client.json("POST", "sao/premium/challenge", object("username", microsoft.getUsername()), null);
        JsonObject join = object("accessToken", microsoft.getAccessToken(), "selectedProfile", microsoft.getUUID().toString().replace("-", ""),
                "serverId", required(challenge, "serverId"));
        try { client.mojangJoin(mojangJoin, join); }
        finally { join.remove("accessToken"); }
        JsonObject body = object("challengeToken", required(challenge, "challengeToken"));
        try { return client.json("POST", "sao/premium/complete", body, null); }
        finally { body.remove("challengeToken"); }
    }

    /// Requests advisory availability; the server atomically enforces uniqueness again on every write.
    CompletableFuture<Boolean> nameAvailable(String name) {
        if (!name.matches("[A-Za-z0-9_]{3,16}")) return CompletableFuture.completedFuture(false);
        AtomicBoolean available = new AtomicBoolean();
        return submit(() -> available.set(client.json("GET", "sao/name/available?name=" + name, null, null).get("available").getAsBoolean()))
                .thenApply(ignored -> available.get());
    }

    /// Replaces tokens after the server's atomic stable-ID rename and updates an opted-in protected name.
    CompletableFuture<Void> rename(String name) {
        long expected = generation.get();
        return submit(() -> {
            Session active = requireSession();
            if (!name.matches("[A-Za-z0-9_]{3,16}")) throw new SaoAccountException("昵称限 3—16 位字母、数字或下划线", "invalidName");
            JsonObject reply = client.json("POST", "sao/rename", object("name", name), active.access());
            accept(reply);
            if (!active.id().equalsIgnoreCase(requireSession().id())) { clear(); throw new SaoAccountException("角色信息无效", "invalidResponse"); }
            synchronized (gate) {
                if (expected != generation.get()) throw new SaoAccountException("账号操作已取消", "cancelled");
                SaoOathCredentials.@Nullable Credential stored = credentials.read();
                if (stored != null) try {
                    if (stored.name().equalsIgnoreCase(active.name())) { credentials.write(requireSession().name(), stored.password()); remembered = requireSession().name(); }
                } finally { Arrays.fill(stored.password(), '\0'); }
            }
            refresh();
        });
    }

    /// Forgets the protected pair before returning, fences pending saves, and asynchronously revokes the old session.
    void logout() {
        @Nullable Session previous;
        synchronized (gate) {
            if (closed.get() || frozen) { clear(); publish(this::publishState); return; }
            generation.incrementAndGet(); previous = session; clear(); remembered = null;
            try { credentials.delete(); }
            catch (IOException failure) { errors.accept("清除已保存账号失败，请重试退出"); }
        }
        publish(this::publishState);
        if (previous != null && !closed.get()) {
            pending.incrementAndGet();
            try { worker.execute(() -> {
                try { if (previous.root().equals(client.root())) client.json("POST", "authserver/invalidate", object("accessToken", previous.access()), null); }
                catch (SaoAccountException ignored) { }
                finally { pending.decrementAndGet(); }
            }); } catch (RejectedExecutionException ignored) { pending.decrementAndGet(); }
        }
    }

    /// Refreshes before launch; accounts awaiting mailbox verification or a replacement nickname cannot start.
    /// Root from which launch-ticket ancestors are hardened; it must contain the game instance.
    Path ticketRoot() { return launcherRoot; }

    /// Refreshes the session and hands a launch account to the engine; the future fails with the shown reason.
    CompletableFuture<Void> linkStart() {
        SaoData.@Nullable OathAccount checked = current;
        if (checked == null) return CompletableFuture.completedFuture(null);
        if (checked.mustRename()) {
            errors.accept("昵称已被正版玩家使用，请在启动器里更换昵称");
            return CompletableFuture.failedFuture(new SaoAccountException("昵称已被正版玩家使用，请在启动器里更换昵称", "mustRename"));
        }
        if (checked.locked()) return CompletableFuture.completedFuture(null);
        // A second click while the first refresh is in flight would rotate the token under the first launch.
        if (!launching.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> future = submit(() -> { try { launch.accept(prepareLaunchAccount()); } finally { launching.set(false); } });
        future.exceptionally(failure -> {
            launching.set(false);
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            errors.accept(cause instanceof SaoAccountException accountFailure ? accountFailure.getMessage() : "启动验证失败，请重新登录");
            return null;
        });
        return future;
    }

    /// Production launch preparation shared with headless tests; issues no game process and never falls back offline.
    AuthlibInjectorAccount prepareLaunchAccount() throws Exception {
        try {
            SaoData.@Nullable OathAccount known = current;
            if (known != null && known.mustRename()) throw new SaoAccountException("昵称已被正版玩家使用，请在启动器里更换昵称", "mustRename");
            Session active = requireSession();
            JsonObject refreshed = client.json("POST", "authserver/refresh", object("accessToken", active.access(), "clientToken", active.client()), null);
            JsonObject selected = refreshed.getAsJsonObject("selectedProfile");
            if (!active.id().equalsIgnoreCase(required(selected, "id")) || !active.name().equals(required(selected, "name")))
                throw new SaoAccountException("角色信息已改变，请重新登录", "invalidSession");
            accept(refreshed);
            refresh();
            SaoData.OathAccount checked = Objects.requireNonNull(current);
            if (checked.mustRename()) throw new SaoAccountException("昵称已被正版玩家使用，请在启动器里更换昵称", "mustRename");
            if (checked.locked()) throw new SaoAccountException("请先验证邮箱", "needVerify");
            active = requireSession();
            JsonObject metadata = client.json("GET", "", null, null);
            String encodedMetadata = Base64.getEncoder().encodeToString(metadata.toString().getBytes(StandardCharsets.UTF_8));
            var artifact = injector.getArtifactInfo();
            GameProfile profile = new GameProfile(UUIDs.parse(active.id()), active.name());
            YggdrasilSession yggdrasil = new YggdrasilSession(active.client(), active.access(), profile, List.of(profile), Map.of());
            Session prepared = active;
            long preparedGeneration = generation.get();
            return new SaoOathLaunchAccount(new AuthlibInjectorServer(active.root().toString()), injector, active.name(), yggdrasil,
                    client, encodedMetadata, artifact,
                    () -> !closed.get() && generation.get() == preparedGeneration && session == prepared && current != null && current.canJoin(),
                    () -> { if (session == prepared) invalidSession(); },
                    (instance, server) -> {
                        if (session != prepared || preparedGeneration != generation.get()) throw new SaoAccountException("请重新登录 ARGUS ID", "invalidSession");
                        JsonObject ticket = client.json("POST", "sao/launch", new JsonObject(), prepared.access());
                        try {
                            if (session != prepared || preparedGeneration != generation.get()) throw new SaoAccountException("请重新登录 ARGUS ID", "invalidSession");
                            return new SaoOathLaunchTicket(launcherRoot, instance, prepared.root().toString(), prepared.id(), server, ticket);
                        }
                        finally { ticket.remove("launchTicket"); }
                    });
        } catch (Exception failure) {
            if (!(failure instanceof SaoAccountException accountFailure && (accountFailure.code().equals("needVerify") || accountFailure.code().equals("mustRename")))
                    && !transientFailure(failure)) invalidSession();
            throw failure;
        }
    }

    /// Sanitizes uploads, sends a standard multipart body and reloads profile texture metadata and bytes.
    CompletableFuture<Void> uploadSkin(@Nullable Path png, boolean slim) {
        return submit(() -> {
            Session active = requireSession();
            byte @Nullable [] bytes = null;
            if (png != null) {
                if (!Files.isRegularFile(png) || Files.size(png) > 1024 * 1024) throw new IllegalArgumentException("皮肤必须是 64×64 PNG");
                bytes = SaoOathSkins.sanitized(Files.readAllBytes(png), false);
            }
            client.skin(active.id(), bytes, slim, active.access()); refresh();
        });
    }

    /// Saves a sanitized 64×64 entry in the per-user library, also while signed out.
    SaoData.SavedSkin saveSkin(String name, byte[] png, boolean slim) {
        synchronized (gate) {
        try {
            if (frozen) throw new IOException("启动器正在卸载");
            SaoOathSkins storage = requireLibrary();
            if (closed.get()) throw new IOException("账号服务已关闭");
            SaoData.SavedSkin kept = storage.save(name, png, slim);
            publishLibrary(storage); return kept;
        } catch (IOException | SaoAccountException failure) { throw new IllegalArgumentException("保存皮肤失败"); }
        }
    }

    /// Removes only an owned per-user library entry and publishes the resulting list.
    void deleteSavedSkin(SaoData.SavedSkin skin) {
        synchronized (gate) {
        try { if (frozen) throw new IOException("启动器正在卸载"); SaoOathSkins storage = requireLibrary(); storage.delete(skin); publishLibrary(storage); }
        catch (IOException | SaoAccountException failure) { errors.accept("删除皮肤失败"); }
        }
    }

    /// Permanently removes protected native/fallback credentials before uninstall removes the enclosing data tree.
    void uninstallCredentials() throws IOException { credentials.delete(); remembered = null; }

    /// Runs one bearer mutation and refreshes authoritative state; expected revocation still means mutation success.
    private CompletableFuture<Void> authenticated(String route, JsonObject body, boolean revokes) {
        return submit(() -> { client.json("POST", route, body, requireSession().access()); if (revokes) refreshRevoked(); else refresh(); });
    }

    /// Accepts only complete tokens and a selected stable profile, without persisting any token or bearer secret.
    private void accept(JsonObject reply) throws SaoAccountException {
        String access = required(reply, "accessToken"); String clientToken = required(reply, "clientToken");
        JsonObject profile;
        try { profile = reply.getAsJsonObject("selectedProfile"); }
        catch (RuntimeException failure) { throw new SaoAccountException("角色信息无效", "invalidResponse"); }
        String id = required(profile, "id"); String name = required(profile, "name");
        if (!id.matches("[a-fA-F0-9]{32}") || !name.matches("[A-Za-z0-9_]{3,16}")) throw new SaoAccountException("角色信息无效", "invalidResponse");
        @Nullable Session previous = session;
        if (previous != null && !previous.id().equalsIgnoreCase(id)) { currentInherited = SaoTextureState.empty(); skin = null; profileCache = null; }
        Logger.registerAccessToken(access);
        session = new Session(client.root(), access, clientToken, name, id);
        @Nullable GameRun running=gameRun;
        friends.playing(running!=null&&running.id().equalsIgnoreCase(id)&&running.api().equals(client.root()));
        friends.session(new SaoFriends.Session(client.root(), access, id, name));
    }

    /// GET /sao/account is the only source of published verification/lock state, followed by signed profile texture lookup.
    private void refresh() throws Exception {
        try {
            Session active = requireSession();
            JsonObject state = client.json("GET", "sao/account", null, active.access());
            String id = required(state, "id"); String name = required(state, "name");
            if (!id.equalsIgnoreCase(active.id()) || !name.equals(active.name())) throw new SaoAccountException("账号状态已改变，请重新登录", "invalidSession");
            String accountState = required(state, "state");
            if (!accountState.equals("ready") && !accountState.equals("lockedVerify")) throw new SaoAccountException("账号状态无效", "invalidResponse");
            @Nullable String premiumName = optional(state, "premiumName");
            current = new SaoData.OathAccount(name, id, optional(state, "maskedEmail") == null ? "" : Objects.requireNonNull(optional(state, "maskedEmail")),
                    state.get("emailVerified").getAsBoolean(), accountState.equals("lockedVerify"), Math.max(0, state.get("graceSeconds").getAsLong()), premiumName,
                    state.has("mustRename") && state.get("mustRename").getAsBoolean());
            premiumOnly = state.has("premiumOnly") && state.get("premiumOnly").getAsBoolean();
            profileCache = new SaoOathSkins(root, namespace(active.root()), active.id());
            refreshSkin(active);
            refreshInheritedState(active);
            refreshCardNow();
        } catch (Exception failure) { if (!transientFailure(failure)) clear(); throw failure; }
    }

    /// Refreshes optional appearance support without turning an older service or cosmetic outage into signout.
    private void refreshInheritedState(Session active) {
        try { currentInherited = client.inheritedTextures(active.root(), active.access()); if (awaitingAppearance(currentInherited)) scheduleAppearancePoll(active); }
        catch (SaoAccountException unavailable) { if (currentInherited.linked()) currentInherited = new SaoTextureState(true, currentInherited.skinSource(), currentInherited.capeSource(), currentInherited.effectiveSkinSource(), currentInherited.effectiveCapeSource(), currentInherited.images(), currentInherited.lastRefresh(), currentInherited.refreshAfter(), "failed"); }
    }

    /// Includes linked accounts whose first initialization task is still queued before a syncing row exists.
    private static boolean awaitingAppearance(SaoTextureState value) { return value.linked() && (value.status().equals("syncing") || value.status().equals("idle") && value.lastRefresh() == 0); }

    /// Polls first-link work without delaying sign-in; completions update both the source property and current skin.
    private void scheduleAppearancePoll(Session active) {
        if (closed.get() || session != active || !appearancePolling.compareAndSet(false, true)) return;
        try { appearanceTimer.schedule(() -> submit(() -> {
            if (closed.get() || session != active) return;
            currentInherited = client.inheritedTextures(active.root(), active.access());
            if (!awaitingAppearance(currentInherited)) refreshSkin(active);
        }).whenComplete((ignored, failure) -> {
            appearancePolling.set(false);
            @Nullable Session latest = session;
            if (failure == null && latest != null && awaitingAppearance(currentInherited)) scheduleAppearancePoll(latest);
        }), 1, TimeUnit.SECONDS); }
        catch (RejectedExecutionException closedTimer) { appearancePolling.set(false); }
    }

    /// Refreshes the card without turning a statistics outage into a logout or discarding previous known values.
    private void refreshCardNow() {
        @Nullable Session active = session;
        if (active == null) { currentCard = null; return; }
        SaoData.@Nullable PlayerCard previous = currentCard;
        int level = previous == null ? -1 : previous.level(), floor = -1;
        long col = previous == null ? -1 : previous.col();
        @Unmodifiable List<String> friends = previous == null ? List.of() : previous.friendsOnline();
        try {
            JsonObject values = client.json("GET", "sao/card", null, active.access());
            if (!active.id().equalsIgnoreCase(required(values, "id"))) throw new SaoAccountException("角色信息无效", "invalidResponse");
            level = Math.max(-1, values.get("level").getAsInt());
            col = Math.max(-1, values.get("col").getAsLong());
            List<String> names = new ArrayList<>();
            if (values.get("friends") instanceof JsonArray list) {
                if (list.size() > 500) throw new IllegalArgumentException("Friend list too long");
                for (JsonElement friend : list) {
                    String name = friend.getAsString();
                    if (!name.matches("[A-Za-z0-9_]{3,16}")) throw new IllegalArgumentException("Invalid friend name");
                    names.add(name);
                }
            }
            friends = List.copyOf(names);
        } catch (Exception unavailable) { /* Statistics are optional; account admission is checked independently. */ }
        long last = 0, millis = 0;
        try {
            @Nullable GameRun running = gameRun;
            SaoOathPlayTime.Snapshot local = running != null && running.api().equals(active.root()) && running.id().equals(active.id())
                    ? running.time().snapshot() : playTime.read(active.root(), active.id());
            last = local.lastPlayed(); millis = local.playMillis();
        } catch (IOException unavailable) { /* Unknown local history stays zero until storage is readable. */ }
        if (session == active) currentCard = new SaoData.PlayerCard(level, floor, col, last, millis / 1000, friends);
    }

    /// Queues the card refresh used by the server-status timer and the process-exit callback.
    void refreshCard() {
        if (session == null || closed.get()) return;
        submit(this::refreshCardNow);
    }

    /// Records the actual created process under its prepared account, independently of later signout or renames.
    synchronized void gameStarted(SaoOathLaunchAccount launched) {
        @Nullable Session active=session;
        friends.playing(active!=null&&active.id().equalsIgnoreCase(launched.profileId())&&active.root().equals(launched.apiRoot()));
        try { gameRun = new GameRun(launched.apiRoot(), launched.profileId(), playTime.start(launched.apiRoot(), launched.profileId())); }
        catch (IOException failure) { errors.accept("保存游玩记录失败"); }
        refreshCard();
    }

    /// Persists monotonic elapsed time once, then refreshes the signed-in card after process exit.
    synchronized void gameExited() {
        friends.playing(false);
        @Nullable GameRun running = gameRun;
        if (running != null) try { running.time().close(); gameRun = null; }
        catch (IOException failure) { errors.accept("保存游玩记录失败"); }
        refreshCard();
    }

    /// Observes server-side epoch revocation without converting a successfully completed mutation into a failure.
    private void refreshRevoked() throws Exception {
        try { refresh(); }
        catch (SaoAccountException failure) { if (!failure.code().equals("invalidCredentials")) throw failure; }
    }

    /// Downloads only an API-origin texture declared by the current profile; association removal clears the cache.
    private void refreshSkin(Session active) throws Exception {
        JsonObject profile = client.jsonAt(active.root(), "GET", "sessionserver/session/minecraft/profile/" + active.id() + "?unsigned=false", null, null);
        @Nullable String texture = null;
        boolean modelSlim = false;
        if (profile.has("properties")) for (JsonElement element : profile.getAsJsonArray("properties")) {
            JsonObject property = element.getAsJsonObject();
            if ("textures".equals(optional(property, "name"))) {
                String value = required(property, "value");
                if (value.length() > 65536) throw new SaoAccountException("皮肤信息无效", "invalidTexture");
                JsonObject decoded = JsonParser.parseString(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)).getAsJsonObject();
                @Nullable JsonObject textures = decoded.getAsJsonObject("textures");
                if (textures != null && textures.has("SKIN")) {
                    JsonObject skinData = textures.getAsJsonObject("SKIN");
                    texture = required(skinData, "url");
                    if (skinData.get("metadata") instanceof JsonObject model) modelSlim = "slim".equals(optional(model, "model"));
                }
            }
        }
        SaoOathSkins storage = Objects.requireNonNull(profileCache);
        storage.current(texture == null ? null : client.textureAt(active.root(), texture));
        skin = storage.current(); slim = texture != null && modelSlim;
    }

    /// Ensures no bearer is ever reused after an API configuration change or local signout.
    private Session requireSession() throws SaoAccountException {
        @Nullable Session active = session;
        if (closed.get() || active == null || !active.root().equals(client.root())) {
            clear(); throw new SaoAccountException("请先登录 ARGUS ID", "signedOut");
        }
        return active;
    }

    /// Resolves the per-user library while refusing writes after shutdown.
    private SaoOathSkins requireLibrary() throws SaoAccountException {
        if (closed.get()) throw new SaoAccountException("账号服务已关闭", "closed");
        return library;
    }

    /// Clears invalid credentials from protected storage, as required by an explicit forget or password change.
    private void forget() throws IOException { synchronized (gate) { credentials.delete(); remembered = null; } }
    /// Drops all ephemeral account/texture state without deleting an explicitly remembered password.
    private void clear() { currentInherited = SaoTextureState.empty(); friends.session(null); session = null; current = null; currentCard = null; skin = null; slim = false; premiumOnly = false; profileCache = null; }
    /// Invalidates state from a launch-token check and publishes the signout.
    private void invalidSession() { clear(); publish(this::publishState); }

    /// A network drop, server error or rate limit says nothing about the session; the player stays signed in.
    static boolean transientFailure(Throwable failure) {
        return failure instanceof SaoAccountException account
                && (account.code().equals("network") || account.code().equals("unavailable") || account.code().equals("rateLimited"));
    }

    /// Takes ownership immediately, wiping the caller's input before any asynchronous operation can fail.
    private char @Nullable [] own(char @Nullable [] supplied) {
        if (supplied == null) return null;
        char[] owned = supplied.clone(); Arrays.fill(supplied, '\0'); passwords.add(owned); return owned;
    }

    /// Serializes account work, wipes queued secrets and completes futures on JavaFX, or directly when headless.
    private CompletableFuture<Void> submit(Operation operation, char @Nullable []... owned) {
        CompletableFuture<Void> future = new CompletableFuture<>(); futures.add(future);
        long expected = generation.get();
        synchronized (gate) {
            if (closed.get() || frozen) {
                for (char @Nullable [] password : owned) if (password != null) { Arrays.fill(password, '\0'); passwords.remove(password); }
                publish(() -> { future.completeExceptionally(new SaoAccountException("账号服务已关闭", "closed")); futures.remove(future); });
                return future;
            }
            pending.incrementAndGet();
        }
        Runnable action = () -> {
            @Nullable SaoAccountException problem = null;
            try {
                if (closed.get() || expected != generation.get()) throw new SaoAccountException("账号操作已取消", "cancelled");
                for (char @Nullable [] password : owned) if (password != null && (password.length == 0 || password.length > 4096))
                    throw new SaoAccountException("密码长度无效", "invalidInput");
                operation.run();
                if (closed.get() || expected != generation.get()) { clear(); throw new SaoAccountException("账号操作已取消", "cancelled"); }
            } catch (SaoAccountException failure) {
                problem = failure;
            }
            catch (IllegalArgumentException failure) { problem = new SaoAccountException("请检查输入内容或皮肤尺寸", "invalidInput"); }
            catch (Exception failure) { problem = new SaoAccountException("账号操作失败，请稍后重试", "operationFailed"); }
            finally {
                // An operation can fail at its save fence after accepting a late HTTP response.
                // Clear that response's internal authorization even when its screen publication was cancelled.
                if (closed.get() || expected != generation.get()) clear();
                // A failed mutation may have committed and revoked the old epoch before its response was lost.
                if (problem != null && session != null && !closed.get() && expected == generation.get())
                    try { refresh(); } catch (Exception invalid) { if (!transientFailure(invalid)) clear(); }
                for (char @Nullable [] password : owned) if (password != null) { Arrays.fill(password, '\0'); passwords.remove(password); }
                pending.decrementAndGet();
            }
            @Nullable SaoAccountException result = problem;
            publish(() -> {
                if (expected == generation.get()) publishState();
                if (result == null && !closed.get() && expected == generation.get()) future.complete(null);
                else future.completeExceptionally(result == null ? new SaoAccountException("账号操作已取消", "cancelled") : result);
                futures.remove(future);
            });
        };
        try { worker.execute(action); }
        catch (RejectedExecutionException rejected) {
            for (char @Nullable [] password : owned) if (password != null) { Arrays.fill(password, '\0'); passwords.remove(password); }
            pending.decrementAndGet(); publish(() -> { future.completeExceptionally(new SaoAccountException("账号服务已关闭", "closed")); futures.remove(future); });
        }
        return future;
    }

    /// Publishes state and the matching library together, before the operation's future completes.
    private void publishState() {
        account.set(current);
        inherited.set(currentInherited);
        card.set(currentCard);
        saved.setAll(libraryEntries);
    }

    /// Refreshes the per-user library view after a completed local file mutation.
    private void publishLibrary(SaoOathSkins storage) throws IOException {
        @org.jetbrains.annotations.Unmodifiable List<SaoData.SavedSkin> entries = storage.list();
        libraryEntries = entries;
        publish(() -> { if (library == storage && !closed.get()) saved.setAll(entries); });
    }

    /// Delivers callbacks on JavaFX, with an explicit headless-toolkit fallback.
    private static void publish(Runnable action) {
        try { Platform.runLater(action); }
        catch (IllegalStateException noToolkit) { action.run(); }
    }

    /// Reads one bounded required string without exposing malformed JSON content to exception messages.
    private static String required(@Nullable JsonObject json, String key) throws SaoAccountException {
        @Nullable String value = json == null ? null : optional(json, key);
        if (value == null || value.isEmpty() || value.length() > 8192) throw new SaoAccountException("账号服务响应无效", "invalidResponse");
        return value;
    }

    /// Reads an optional JSON string, treating null or incorrect types as absent.
    private static @Nullable String optional(JsonObject json, String key) {
        @Nullable JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    /// Creates request JSON from fixed public field names; callers remove password/token members after sending.
    private static JsonObject object(String... pairs) {
        JsonObject value = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) value.addProperty(pairs[i], pairs[i + 1]);
        return value;
    }

    /// Separates libraries for identical profile IDs issued by different account servers.
    private static String namespace(URI endpoint) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(endpoint.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /// Cancels all pending account work, wipes queued passwords and completes outstanding futures safely.
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        generation.incrementAndGet(); friends.close(); appearanceTimer.shutdownNow(); worker.shutdownNow(); clear();
        for (char[] password : passwords) Arrays.fill(password, '\0'); passwords.clear();
        publish(() -> {
            publishState();
            for (CompletableFuture<Void> future : futures) future.completeExceptionally(new SaoAccountException("账号服务已关闭", "closed"));
            futures.clear();
        });
    }
}
