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
        try { return jsonAt(root(), method, route, body, bearer); }
        catch (SaoAccountException failure) { throw failure; }
        catch (RuntimeException invalid) { throw new SaoAccountException("账号服务响应无效", "invalidResponse"); }
    }

    /// Pins a social session's captured issuing origin, including cleanup after the configured API changes.
    JsonObject jsonAt(URI origin, String method, String route, @Nullable JsonObject body, @Nullable String bearer) throws SaoAccountException {
        byte @Nullable [] bytes = null;
        try {
            if (body != null) {
                body.addProperty("language", language());
                bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 16384) throw new SaoAccountException("输入内容过长", "invalidInput");
            }
            byte[] response = request(checkedRoot(origin.toString()).resolve(route), method, bytes, "application/json; charset=utf-8", bearer);
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
        String code = "";
        try {
            JsonObject error = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            message = error.get("errorMessage").getAsString();
            if (error.has("error")) code = error.get("error").getAsString();
        }
        catch (RuntimeException ignored) { }
        @Nullable SaoAccountException known = switch (code) {
            case "playerMissing" -> new SaoAccountException("没有找到这位玩家", "playerMissing");
            case "selfFriend" -> new SaoAccountException("不能添加自己", "selfFriend");
            case "playerBlocked" -> new SaoAccountException("无法向对方发送消息", "playerBlocked");
            case "alreadyFriends" -> new SaoAccountException("已经是好友了", "alreadyFriends");
            case "friendsFull" -> new SaoAccountException("好友列表已满", "friendsFull");
            case "requestsFull" -> new SaoAccountException("好友请求已满", "requestsFull");
            case "requestMissing" -> new SaoAccountException("好友请求已结束", "requestMissing");
            case "notFriends" -> new SaoAccountException("对方不在好友列表", "notFriends");
            case "invalidGroupName" -> new SaoAccountException("群名限 1—12 字，请勿使用空格或括号", "invalidGroupName");
            case "groupNameTaken" -> new SaoAccountException("这个群名已被使用", "groupNameTaken");
            case "groupsFull" -> new SaoAccountException("加入的群太多了", "groupsFull");
            case "groupNeedsFriends" -> new SaoAccountException("请至少选择一位好友", "groupNeedsFriends");
            case "groupFull" -> new SaoAccountException("群已满", "groupFull");
            case "notGroup" -> new SaoAccountException("这不是群聊", "notGroup");
            case "chatMissing" -> new SaoAccountException("会话已结束", "chatMissing");
            case "inviteTarget" -> new SaoAccountException("邀请的服务器不可用", "inviteTarget");
            case "inviteExpired" -> new SaoAccountException("邀请已过期，请重新邀请", "inviteExpired");
            case "notSender" -> new SaoAccountException("只能撤回自己的消息", "notSender");
            case "recallExpired" -> new SaoAccountException("只能撤回两分钟内的消息", "recallExpired");
            case "messageMissing" -> new SaoAccountException("消息不可用", "messageMissing");
            case "messageRecalled" -> new SaoAccountException("消息已撤回", "messageRecalled");
            case "typingRate" -> new SaoAccountException("输入状态更新过快", "typingRate");
            case "remarkLength" -> new SaoAccountException("备注最多十二个字，请勿换行", "remarkLength");
            case "searchLength" -> new SaoAccountException("搜索限 1—100 字，请勿换行", "searchLength");
            case "messageLength" -> new SaoAccountException("消息限 1—1024 字，请勿换行", "messageLength");
            case "messageRate" -> new SaoAccountException("发太快了，稍后再试", "messageRate");
            case "nonceConflict" -> new SaoAccountException("消息状态已改变，请重试", "nonceConflict");
            case "invalidNonce" -> new SaoAccountException("消息凭证无效，请重试", "invalidNonce");
            case "invalidCursor" -> new SaoAccountException("消息同步位置无效，请重新登录", "invalidCursor");
            case "invalidDevice" -> new SaoAccountException("设备信息无效，请重新登录", "invalidDevice");
            case "devicesFull" -> new SaoAccountException("聊天设备已满", "devicesFull");
            case "socialCapacity" -> new SaoAccountException("聊天空间已满，请联系管理员", "socialCapacity");
            case "socialInput" -> new SaoAccountException("请检查聊天内容", "socialInput");
            case "socialEndpoint" -> new SaoAccountException("请更新启动器后使用聊天", "socialEndpoint");
            case "socialMethod" -> new SaoAccountException("聊天操作无效", "socialMethod");
            case "socialSession" -> new SaoAccountException("登录已失效，请重新登录", "socialSession");
            case "mustRename" -> new SaoAccountException("昵称已被正版玩家使用，请在启动器里更换昵称", "mustRename");
            case "nameTaken" -> new SaoAccountException("这个昵称已被占用", "nameTaken");
            case "reservedName" -> new SaoAccountException("这个昵称暂不可用", "reservedName");
            case "renameLimit" -> new SaoAccountException("今天已经改了 3 次，明天再来", "renameLimit");
            case "invalidName" -> new SaoAccountException("昵称限 3—16 位字母、数字或下划线", "invalidName");
            case "accountOnline" -> new SaoAccountException("请先退出游戏再改昵称", "accountOnline");
            case "useRename" -> new SaoAccountException("请更新启动器后修改昵称", "useRename");
            case "invalidPremiumProof" -> new SaoAccountException("Microsoft 验证已过期，请重试", "invalidPremiumProof");
            case "premiumLinked" -> new SaoAccountException("这个 Microsoft 已绑定其他 ARGUS ID", "premiumLinked");
            case "launchRequired" -> new SaoAccountException("请从启动器 Link Start 进入", "launchRequired");
            case "invalidTicket" -> new SaoAccountException("启动凭证已过期，请重新 Link Start", "invalidTicket");
            default -> null;
        };
        if (known != null) return known;
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
