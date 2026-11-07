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

    /// Owns all HTTP calls and sensitive state transitions.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sao-oath-accounts"); thread.setDaemon(true); return thread;
    });
    /// Transport enforcing origin, TLS, bounds, locale and safe failure text.
    private final SaoAccountClient client;
    /// Root enclosing only this launcher's credentials, texture cache and libraries.
    private final Path root;
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
        this.root = root; this.client = new SaoAccountClient(endpoint, language); this.credentials = credentials;
        this.premium = premium; this.mojangJoin = mojangJoin; this.launch = launch; this.errors = errors;
        this.injector = new SaoOathInjector(root);
        this.library = new SaoOathSkins(root);
        try { libraryEntries = library.list(); } catch (IOException ignored) { }
        publish(this::publishState);
        try {
            SaoOathCredentials.@Nullable Credential credential = credentials.read();
            if (credential != null) { remembered = credential.name(); Arrays.fill(credential.password(), '\0'); }
        } catch (IOException failure) { errors.accept("读取已保存账号失败，请重新输入密码"); }
    }

    /// The current account property; only refreshed server values are published.
    ReadOnlyObjectProperty<SaoData.@Nullable OathAccount> account() { return account.getReadOnlyProperty(); }
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

    /// Logs in explicitly, optionally reading a matching protected remembered credential, and refreshes server state.
    CompletableFuture<Void> login(String name, char @Nullable [] supplied, boolean remember) {
        char @Nullable [] password = own(supplied);
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
                if (remember) { credentials.write(requireSession().name(), actual); remembered = requireSession().name(); }
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

    /// Changes the mailbox, then clears the session if the server revoked its credential epoch as specified.
    CompletableFuture<Void> changeEmail(String email) { return authenticated("sao/email", object("email", email), true); }

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
            JsonObject challenge = client.json("POST", "sao/premium/challenge", object("username", microsoft.getUsername()), null);
            String challengeToken = required(challenge, "challengeToken");
            String serverId = required(challenge, "serverId");
            JsonObject join = object("accessToken", microsoft.getAccessToken(), "selectedProfile", microsoft.getUUID().toString().replace("-", ""), "serverId", serverId);
            try { client.mojangJoin(mojangJoin, join); }
            finally { join.remove("accessToken"); }
            JsonObject complete = client.json("POST", "sao/premium/complete", object("challengeToken", challengeToken), null);
            JsonObject link = object("premiumToken", required(complete, "premiumToken"));
            try { client.json("POST", "sao/premium/link", link, active.access()); }
            finally { link.remove("premiumToken"); }
            refreshRevoked();
        });
    }

    /// Immediately signs out locally, then revokes the old token without deleting opted-in remembered credentials.
    void logout() {
        generation.incrementAndGet();
        @Nullable Session previous = session;
        clear(); publish(this::publishState);
        if (previous != null && !closed.get()) {
            pending.incrementAndGet();
            try { worker.execute(() -> {
                try { if (previous.root().equals(client.root())) client.json("POST", "authserver/invalidate", object("accessToken", previous.access()), null); }
                catch (SaoAccountException ignored) { }
                finally { pending.decrementAndGet(); }
            }); } catch (RejectedExecutionException ignored) { pending.decrementAndGet(); }
        }
    }

    /// Refreshes before handing the account into the same pack launch pipeline; locked accounts never launch.
    void linkStart() {
        if (current == null || current.locked()) return;
        submit(() -> launch.accept(prepareLaunchAccount())).exceptionally(failure -> {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            errors.accept(cause instanceof SaoAccountException accountFailure ? accountFailure.getMessage() : "启动验证失败，请重新登录");
            return null;
        });
    }

    /// Production launch preparation shared with headless tests; issues no game process and never falls back offline.
    AuthlibInjectorAccount prepareLaunchAccount() throws Exception {
        try {
            Session active = requireSession();
            JsonObject refreshed = client.json("POST", "authserver/refresh", object("accessToken", active.access(), "clientToken", active.client()), null);
            JsonObject selected = refreshed.getAsJsonObject("selectedProfile");
            if (!active.id().equalsIgnoreCase(required(selected, "id")) || !active.name().equals(required(selected, "name")))
                throw new SaoAccountException("角色信息已改变，请重新登录", "invalidSession");
            accept(refreshed);
            refresh();
            SaoData.OathAccount checked = Objects.requireNonNull(current);
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
                    () -> !closed.get() && generation.get() == preparedGeneration && session == prepared && current != null && !current.locked(),
                    () -> { if (session == prepared) invalidSession(); });
        } catch (Exception failure) {
            if (!(failure instanceof SaoAccountException accountFailure && accountFailure.code().equals("needVerify"))) invalidSession();
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
        if (previous != null && !previous.id().equalsIgnoreCase(id)) { skin = null; profileCache = null; }
        Logger.registerAccessToken(access);
        session = new Session(client.root(), access, clientToken, name, id);
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
                    state.get("emailVerified").getAsBoolean(), accountState.equals("lockedVerify"), Math.max(0, state.get("graceSeconds").getAsLong()), premiumName);
            profileCache = new SaoOathSkins(root, namespace(active.root()), active.id());
            refreshSkin(active);
        } catch (Exception failure) { clear(); throw failure; }
    }

    /// Observes server-side epoch revocation without converting a successfully completed mutation into a failure.
    private void refreshRevoked() throws Exception {
        try { refresh(); }
        catch (SaoAccountException failure) { if (!failure.code().equals("invalidCredentials")) throw failure; }
    }

    /// Downloads only an API-origin texture declared by the current profile; association removal clears the cache.
    private void refreshSkin(Session active) throws Exception {
        JsonObject profile = client.json("GET", "sessionserver/session/minecraft/profile/" + active.id() + "?unsigned=false", null, null);
        @Nullable String texture = null;
        boolean modelSlim = false;
        if (profile.has("properties")) for (JsonElement element : profile.getAsJsonArray("properties")) {
            JsonObject property = element.getAsJsonObject();
            if ("textures".equals(optional(property, "name"))) {
                String value = required(property, "value");
                if (value.length() > 65536) throw new SaoAccountException("皮肤信息无效", "invalidTexture");
                JsonObject decoded = JsonParser.parseString(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject textures = decoded.getAsJsonObject("textures");
                if (textures != null && textures.has("SKIN")) {
                    JsonObject skinData = textures.getAsJsonObject("SKIN");
                    texture = required(skinData, "url");
                    if (skinData.get("metadata") instanceof JsonObject model) modelSlim = "slim".equals(optional(model, "model"));
                }
            }
        }
        SaoOathSkins storage = Objects.requireNonNull(profileCache);
        storage.current(texture == null ? null : client.texture(texture));
        skin = storage.current(); slim = texture != null && modelSlim;
    }

    /// Ensures no bearer is ever reused after an API configuration change or local signout.
    private Session requireSession() throws SaoAccountException {
        @Nullable Session active = session;
        if (closed.get() || active == null || !active.root().equals(client.root())) {
            clear(); throw new SaoAccountException("请先登录誓约账号", "signedOut");
        }
        return active;
    }

    /// Resolves the per-user library while refusing writes after shutdown.
    private SaoOathSkins requireLibrary() throws SaoAccountException {
        if (closed.get()) throw new SaoAccountException("账号服务已关闭", "closed");
        return library;
    }

    /// Clears invalid credentials from protected storage, as required by an explicit forget or password change.
    private void forget() throws IOException { credentials.delete(); remembered = null; }
    /// Drops all ephemeral account/texture state without deleting an explicitly remembered password.
    private void clear() { session = null; current = null; skin = null; slim = false; profileCache = null; }
    /// Invalidates state from a launch-token check and publishes the signout.
    private void invalidSession() { clear(); publish(this::publishState); }

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
                // A failed mutation may have committed and revoked the old epoch before its response was lost.
                if (problem != null && session != null && !closed.get() && expected == generation.get())
                    try { refresh(); } catch (Exception invalid) { clear(); }
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
        generation.incrementAndGet(); worker.shutdownNow(); clear();
        for (char[] password : passwords) Arrays.fill(password, '\0'); passwords.clear();
        publish(() -> {
            publishState();
            for (CompletableFuture<Void> future : futures) future.completeExceptionally(new SaoAccountException("账号服务已关闭", "closed"));
            futures.clear();
        });
    }
}
