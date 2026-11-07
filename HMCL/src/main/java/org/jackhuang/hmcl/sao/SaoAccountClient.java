/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;

/// Bounded account HTTP transport. Redirects are refused so credentials never change origin.
@NotNullByDefault
final class SaoAccountClient {
    /// Default production endpoint, including the explicit relay port.
    static final String DEFAULT_API = "https://auth.saooath.com:61784/";
    /// Largest accepted JSON or texture response.
    private static final int MAX_RESPONSE = 1024 * 1024;
    /// Client does not share HMCL's logged request helpers or follow redirects.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    /// The current configured endpoint is resolved when an operation begins.
    private final Supplier<URI> endpoint;
    /// The launcher's current locale, normalized for the server.
    private final Supplier<String> language;

    /// Creates an injectable local-fixture or production transport.
    SaoAccountClient(Supplier<URI> endpoint, Supplier<String> language) {
        this.endpoint = endpoint;
        this.language = language;
    }

    /// Validates a root before any secrets can be sent. DNS names are never treated as loopback HTTP.
    static URI checkedRoot(String value) {
        URI uri = URI.create(value);
        @Nullable String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()) && literalLoopback(host)))
            throw new IllegalArgumentException("账号地址必须使用 HTTPS");
        return URI.create(uri.toASCIIString().endsWith("/") ? uri.toASCIIString() : uri.toASCIIString() + "/");
    }

    /// Recognizes only numeric loopback literals, without DNS or private-network exceptions.
    private static boolean literalLoopback(String host) {
        if (host.equals("[::1]") || host.equals("::1") || host.equals("[0:0:0:0:0:0:0:1]")) return true;
        if (!host.matches("127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) return false;
        for (String component : host.split("\\.")) if (Integer.parseInt(component) > 255) return false;
        return true;
    }

    /// Returns the validated currently selected root.
    URI root() { return checkedRoot(endpoint.get().toString()); }

    /// Returns the API's compact locale tag from either launcher or JVM locale spelling.
    String language() { return language.get().toLowerCase(Locale.ROOT).startsWith("zh") ? "zh_cn" : "en_us"; }

    /// Sends one JSON operation with optional ephemeral bearer authorization; request bytes are wiped afterwards.
    JsonObject json(String method, String route, @Nullable JsonObject body, @Nullable String bearer) throws SaoAccountException {
        byte @Nullable [] bytes = null;
        try {
            if (body != null) {
                body.addProperty("language", language());
                bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 16384) throw new SaoAccountException("输入内容过长", "invalidInput");
            }
            byte[] response = request(root().resolve(route), method, bytes, "application/json; charset=utf-8", bearer);
            try {
                if (response.length == 0) return new JsonObject();
                return JsonParser.parseString(new String(response, StandardCharsets.UTF_8)).getAsJsonObject();
            } finally { Arrays.fill(response, (byte) 0); }
        } catch (SaoAccountException failure) { throw failure; }
        catch (RuntimeException failure) { throw new SaoAccountException("账号服务响应无效", "invalidResponse"); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    /// Sends bounded PNG multipart data, or deletes the association when no PNG is supplied.
    void skin(String profile, @Nullable byte[] png, boolean slim, String bearer) throws SaoAccountException {
        if (!profile.matches("[a-fA-F0-9]{32}")) throw new SaoAccountException("角色信息无效", "invalidProfile");
        if (png == null) { request(root().resolve("api/user/profile/" + profile + "/skin"), "DELETE", null, "", bearer); return; }
        String boundary = "SaoSkin" + java.util.UUID.randomUUID().toString().replace("-", "");
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" + (slim ? "slim" : "")
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[prefix.length + png.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(png, 0, body, prefix.length, png.length);
        System.arraycopy(suffix, 0, body, prefix.length + png.length, suffix.length);
        try { request(root().resolve("api/user/profile/" + profile + "/skin"), "PUT", body, "multipart/form-data; boundary=" + boundary, bearer); }
        finally { Arrays.fill(body, (byte) 0); }
    }

    /// Fetches a profile-owned texture only from this API's immutable blob route, without bearer credentials.
    byte[] texture(String url) throws SaoAccountException {
        URI root = root();
        URI target;
        try { target = URI.create(url); }
        catch (RuntimeException failure) { throw new SaoAccountException("皮肤地址无效", "invalidTexture"); }
        String prefix = root.getPath() + "textures/";
        if (!root.getScheme().equalsIgnoreCase(target.getScheme()) || !root.getAuthority().equalsIgnoreCase(target.getAuthority())
                || target.getRawQuery() != null || target.getRawFragment() != null || !target.getPath().startsWith(prefix)
                || !target.getPath().substring(prefix.length()).matches("[a-fA-F0-9]{64}"))
            throw new SaoAccountException("皮肤地址无效", "invalidTexture");
        return request(target, "GET", null, "", null);
    }

    /// Builds the original Mojang URI from components so injector constant rewriting cannot replace its origin.
    static URI originalMojangJoin() {
        try { return new URI("https", null, String.join(".", "sessionserver", "mojang", "com"), -1,
                "/session/minecraft/join", null, null); }
        catch (java.net.URISyntaxException impossible) { throw new AssertionError(impossible); }
    }

    /// Performs the original Mojang ownership join, with no SAO authorization or redirection.
    void mojangJoin(URI target, JsonObject proof) throws SaoAccountException {
        URI checked = checkedRoot(target.toString());
        if (!literalLoopback(checked.getHost()) && !target.equals(originalMojangJoin()))
            throw new SaoAccountException("正版验证地址无效", "invalidProofEndpoint");
        byte[] bytes = proof.toString().getBytes(StandardCharsets.UTF_8);
        try { request(target, "POST", bytes, "application/json; charset=utf-8", null); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    /// Executes one request with fixed timeouts and a streaming cap, exposing only curated public failures.
    private byte[] request(URI target, String method, @Nullable byte[] body, String contentType, @Nullable String bearer) throws SaoAccountException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(20))
                    .header("Accept-Language", language()).header("Accept", "application/json,image/png");
            if (!contentType.isEmpty()) builder.header("Content-Type", contentType);
            if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(MAX_RESPONSE + 1);
                if (bytes.length > MAX_RESPONSE) throw new SaoAccountException("账号服务响应过大", "invalidResponse");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try { throw mapped(response.statusCode(), bytes, target.getPath()); }
                finally { Arrays.fill(bytes, (byte) 0); }
            }
            return bytes;
        } catch (SaoAccountException failure) { throw failure; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new SaoAccountException("账号操作已取消", "cancelled"); }
        catch (IOException | RuntimeException failure) { throw new SaoAccountException("连接账号服务器失败", "network"); }
    }

    /// Translates only known messages; arbitrary server/provider text and submitted fields never reach the screen.
    private static SaoAccountException mapped(int status, byte[] bytes, String route) {
        String message = "";
        try { message = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().get("errorMessage").getAsString(); }
        catch (RuntimeException ignored) { }
        if (status == 429) return new SaoAccountException("请求太频繁，稍后再试", "rateLimited");
        if (status >= 500) return new SaoAccountException("账号服务暂不可用，请稍后重试", "unavailable");
        return switch (message) {
            case "Email verification required." -> new SaoAccountException("请先验证邮箱", "needVerify");
            case "Confirmation expired." -> new SaoAccountException("验证码不对或已过期", "invalidCode");
            case "Name reserved." -> new SaoAccountException("这个名字暂不可用", "reservedName");
            case "Destination already registered." -> new SaoAccountException("这个名字已被注册", "nameTaken");
            case "Console approval required for account move." -> new SaoAccountException("账号迁移需要管理员处理", "approvalRequired");
            default -> status == 401 || status == 403 ? new SaoAccountException(
                    route.endsWith("/sao/register") ? "这个名字已被注册" : route.endsWith("/sao/login") || route.endsWith("/sao/password") ? "密码错误" : "登录已失效，请重新登录", "invalidCredentials")
                    : new SaoAccountException("账号操作失败，请检查输入后重试", "badRequest");
        };
    }
}
