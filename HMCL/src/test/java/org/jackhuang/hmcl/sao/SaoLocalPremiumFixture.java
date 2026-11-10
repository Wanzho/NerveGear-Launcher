package org.jackhuang.hmcl.sao;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/// Loads the released account server's core unchanged and gives its Mojang proof hook a loopback stub.
/// No dedicated game server or renderer is started by this fixture.
@NotNullByDefault
final class SaoLocalPremiumFixture implements AutoCloseable {
    /// Isolated loader for the optional local server artifact, avoiding a product/test dependency on its classes.
    private final URLClassLoader loader;
    /// Real account API exposed on an ephemeral loopback port.
    private final HttpServer api;
    /// Original-Mojang join stand-in, separate from the account API.
    private final HttpServer mojang;
    /// Real server router, loaded reflectively from its released jar.
    private final Object router;
    /// Real asynchronous mail worker, stopped when the fixture closes.
    private final Object mail;
    /// Synthetic session identity used by the local Mojang stand-in.
    private final UUID premiumId = UUID.randomUUID();
    /// Random session sentinel accepted only by the Mojang join stand-in.
    private final String microsoftToken = UUID.randomUUID().toString();
    /// Records the exact server challenge joined by the local launcher.
    private volatile @Nullable String joinedChallenge;
    /// True if an account request ever contains the synthetic Microsoft token.
    private final AtomicBoolean microsoftLeaked = new AtomicBoolean();
    /// Whether the launcher called the Mojang stand-in with its session identity and token.
    private final AtomicBoolean joined = new AtomicBoolean();
    /// Captures protocol-handler failures without exposing request bodies in logs.
    private volatile @Nullable Throwable handlerFailure;
    /// Optional one-request barrier used to prove signout fences an already running remembered login.
    private volatile @Nullable java.util.concurrent.CountDownLatch loginReached;
    /// Releases the synthetic delayed response without changing the real server's authentication behavior.
    private volatile @Nullable java.util.concurrent.CountDownLatch loginRelease;

    /// Creates the real API and isolated proof endpoints using only synthetic private storage.
    SaoLocalPremiumFixture(Path directory, Path serverJar) throws Exception {
        this(directory, serverJar, 48);
    }

    /// Allows a zero-hour verification grace to prove locked management sessions cannot enter the launch pipeline.
    SaoLocalPremiumFixture(Path directory, Path serverJar, int verificationGraceHours) throws Exception {
        loader = new URLClassLoader(new java.net.URL[]{serverJar.toUri().toURL()}, getClass().getClassLoader());
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mojang = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mojang.createContext("/session/minecraft/join", this::join);
        Object config = type("AuthConfig").getConstructor().newInstance();
        config.getClass().getField("verificationGraceHours").setInt(config, verificationGraceHours);
        Object ygg = config.getClass().getField("yggdrasil").get(config);
        ygg.getClass().getField("enabled").setBoolean(ygg, true);
        ygg.getClass().getField("publicUrl").set(ygg, root().toString());
        config.getClass().getField("accountFailureLimit").setInt(config, 100);
        Object store = type("AccountStore").getConstructor(Path.class).newInstance(directory.resolve("sao-auth/accounts.json"));
        Object identity = type("AuthCrypto$Identity").getMethod("load", Path.class).invoke(null, directory.resolve("identity"));
        Class<?> senderType = type("AccountMail$Sender");
        Object sender = Proxy.newProxyInstance(loader, new Class<?>[]{senderType}, (proxy, method, args) -> null);
        mail = type("AccountMail").getConstructor(senderType).newInstance(sender);
        Object flows = type("AccountFlows").getConstructor(Path.class, type("AccountStore"), type("AuthConfig"),
                type("AuthCrypto$Identity"), type("AccountMail")).newInstance(directory.resolve("sao-auth/account-flows.json"), store, config, identity, mail);
        Class<?> hooksType = type("YggdrasilService$Hooks");
        Object hooks = Proxy.newProxyInstance(loader, new Class<?>[]{hooksType}, (proxy, method, args) -> {
            if (method.getName().equals("operator")) return false;
            if (method.getName().equals("rename")) {
                // Match the server's shared stable-ID transaction; the dedicated harness verifies game coordination.
                Object target = java.util.Objects.requireNonNull(args)[0], expected = args[1];
                String previous = (String) type("AccountStore$Account").getMethod("name").invoke(expected);
                if (type("AccountStore").getMethod("find", String.class).invoke(target, previous) != expected)
                    throw new IOException("Fixture account changed");
                Object files = type("FileTransaction").getConstructor(Path.class).newInstance(directory);
                Object plan = type("MigrationPlan").getConstructor(type("FileTransaction"), type("AccountStore"), String.class,
                        String.class, boolean.class, long.class).newInstance(files, target, previous, args[2], false, args[3]);
                type("AccountFlows").getMethod("addApprovalToPlan", type("MigrationPlan")).invoke(null, plan);
                type("MigrationPlan").getMethod("execute").invoke(plan);
                type("AccountStore").getMethod("reload").invoke(target);
                type("AccountFlows").getMethod("reload").invoke(flows);
                return type("AccountStore").getMethod("find", String.class).invoke(target, args[2]);
            }
            if (method.getName().equals("premiumClaim")) {
                // Use the real journaled account snapshot; only the dedicated fixture may prove game coordination.
                Object[] claim = java.util.Objects.requireNonNull(args);
                Object target = claim[0], expected = claim[1], premiumAccount = claim[2];
                String previous = (String) type("AccountStore$Account").getMethod("name").invoke(expected);
                String temporary = "R_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
                Object files = type("FileTransaction").getConstructor(Path.class).newInstance(directory);
                Object plan = type("MigrationPlan").getConstructor(type("FileTransaction"), type("AccountStore"), String.class,
                        String.class, boolean.class).newInstance(files, target, previous, temporary, false);
                byte[] replacement = (byte[]) type("AccountStore").getMethod("premiumClaimBytes", type("AccountStore$Account"),
                        String.class, type("AccountStore$Account")).invoke(target, expected, temporary, premiumAccount);
                @SuppressWarnings("unchecked") Map<String, byte[]> changes = (Map<String, byte[]>) type("MigrationPlan").getField("changes").get(plan);
                changes.put("sao-auth/accounts.json", replacement);
                type("AccountFlows").getMethod("addApprovalToPlan", type("MigrationPlan")).invoke(null, plan);
                type("MigrationPlan").getMethod("execute").invoke(plan);
                type("AccountStore").getMethod("reload").invoke(target);
                type("AccountFlows").getMethod("reload").invoke(flows);
                return type("AccountStore").getMethod("find", String.class).invoke(target, previous);
            }
            if (method.getName().equals("premium")) {
                if (args == null || !"PremiumFixture".equals(args[0]) || !args[1].equals(joinedChallenge)) return null;
                return type("PremiumProof$Identity").getConstructor(String.class, UUID.class).newInstance(args[0], premiumId);
            }
            if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
            return null;
        });
        router = type("YggdrasilService").getConstructor(Path.class, type("AccountStore"), type("AccountFlows"),
                type("AuthConfig"), hooksType, java.util.function.LongSupplier.class).newInstance(directory, store, flows, config, hooks, (java.util.function.LongSupplier) System::currentTimeMillis);
        api.createContext("/", this::route);
        api.start();
        mojang.start();
    }

    /// Returns the loopback account root, never a public endpoint.
    URI root() { return URI.create("http://127.0.0.1:" + api.getAddress().getPort() + "/"); }

    /// Returns the separate original-Mojang join stand-in.
    URI mojangJoin() { return URI.create("http://127.0.0.1:" + mojang.getAddress().getPort() + "/session/minecraft/join"); }

    /// Returns the synthetic Minecraft profile ID, retained in memory only.
    UUID premiumId() { return premiumId; }

    /// Returns the random Microsoft-session sentinel, retained in memory only.
    String microsoftToken() { return microsoftToken; }

    /// Indicates the original-Mojang endpoint accepted exactly the intended session and challenge.
    boolean joined() { return joined.get(); }

    /// Indicates that no SAO request contained the synthetic Microsoft-session token.
    boolean tokenStayedLocal() { return !microsoftLeaked.get(); }

    /// Fails with a fixed label if a local HTTP handler encountered an unexpected exception.
    void assertHealthy() {
        if (handlerFailure != null) throw new AssertionError("Local premium fixture handler failed");
    }

    /// Delays the next login response until releaseLogin is called; requests and credentials are never retained.
    void delayLogin() {
        loginReached = new java.util.concurrent.CountDownLatch(1); loginRelease = new java.util.concurrent.CountDownLatch(1);
    }
    /// Waits only for the real API to have completed authentication, without inspecting its sensitive response.
    void awaitDelayedLogin() throws InterruptedException {
        if (!java.util.Objects.requireNonNull(loginReached).await(10, java.util.concurrent.TimeUnit.SECONDS))
            throw new AssertionError("Delayed login did not arrive");
    }
    /// Allows the delayed synthetic HTTP response to complete.
    void releaseLogin() { java.util.Objects.requireNonNull(loginRelease).countDown(); }

    /// Resolves one released server core class without copying or modifying server source.
    private Class<?> type(String name) throws ClassNotFoundException { return loader.loadClass("dev.sao.auth.core." + name); }

    /// Dispatches actual HTTP requests through the real released account router.
    private void route(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (new String(body, StandardCharsets.UTF_8).contains(microsoftToken)) microsoftLeaked.set(true);
        if (exchange.getRequestHeaders().values().stream().flatMap(java.util.Collection::stream).anyMatch(value -> value.contains(microsoftToken)))
            microsoftLeaked.set(true);
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((key, value) -> headers.put(key.toLowerCase(java.util.Locale.ROOT), value.get(0)));
        try {
            Map<String, String> query = new HashMap<>();
            @Nullable String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery != null) for (String pair : rawQuery.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2) query.put(java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8), java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            Object request = type("YggdrasilService$Request").getConstructor(String.class, String.class, Map.class, Map.class,
                    byte[].class, String.class).newInstance(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    query, headers, body, "127.0.0.1");
            Object response = router.getClass().getMethod("route", request.getClass()).invoke(router, request);
            if (exchange.getRequestURI().getPath().equals("/sao/login")) {
                @Nullable java.util.concurrent.CountDownLatch reached = loginReached, release = loginRelease;
                if (reached != null && release != null) {
                    reached.countDown();
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Delayed response was not released");
                }
            }
            int status = (int) response.getClass().getMethod("status").invoke(response);
            byte[] bytes = (byte[]) response.getClass().getMethod("body").invoke(response);
            @SuppressWarnings("unchecked") Map<String, String> outputHeaders = (Map<String, String>) response.getClass().getMethod("headers").invoke(response);
            outputHeaders.forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) exchange.getResponseBody().write(bytes);
        } catch (Exception failure) {
            handlerFailure = failure;
            exchange.sendResponseHeaders(500, -1);
        } finally { exchange.close(); }
    }

    /// Accepts the synthetic Microsoft token only on the independent Mojang join endpoint.
    private void join(HttpExchange exchange) throws IOException {
        try {
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            boolean valid = exchange.getRequestMethod().equals("POST") && microsoftToken.equals(request.get("accessToken").getAsString())
                    && premiumId.toString().replace("-", "").equals(request.get("selectedProfile").getAsString());
            if (valid) { joinedChallenge = request.get("serverId").getAsString(); joined.set(true); }
            exchange.sendResponseHeaders(valid ? 204 : 403, -1);
        } catch (Exception failure) { handlerFailure = failure; exchange.sendResponseHeaders(500, -1); }
        finally { exchange.close(); }
    }

    /// Stops both loopback HTTP endpoints, the mail worker, and the isolated class loader.
    @Override public void close() throws Exception {
        @Nullable java.util.concurrent.CountDownLatch release = loginRelease;
        if (release != null) release.countDown();
        api.stop(0);
        mojang.stop(0);
        ((AutoCloseable) router).close();
        ((AutoCloseable) mail).close();
        loader.close();
    }
}
