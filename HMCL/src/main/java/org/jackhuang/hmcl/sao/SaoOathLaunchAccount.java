/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.auth.*;
import org.jackhuang.hmcl.auth.authlibinjector.*;
import org.jackhuang.hmcl.auth.yggdrasil.YggdrasilSession;
import org.jackhuang.hmcl.game.Arguments;
import org.jackhuang.hmcl.game.LaunchOptions;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.net.URI;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/// An engine-owned HMCL injector account with no offline fallback, protected-store persistence or password handoff.
@NotNullByDefault
final class SaoOathLaunchAccount extends AuthlibInjectorAccount {
    /// Issues the short-lived instance file only after installation and final authentication are complete.
    @FunctionalInterface
    @NotNullByDefault
    interface TicketIssuer {
        /// Creates one owner-only launch handoff; all ticket bytes remain out of arguments and logs.
        SaoOathLaunchTicket issue(Path instance, String server) throws Exception;
    }
    /// The exact refreshed session whose stable profile was checked by GET /sao/account.
    private final YggdrasilSession session;
    /// The bounded same-origin transport used for the final launch validation.
    private final SaoAccountClient client;
    /// Public metadata prefetched through the checked account transport.
    private final String metadata;
    /// Bundled and SHA-256-verified Java agent.
    private final AuthlibInjectorArtifactInfo artifact;
    /// Account API root captured at preparation, preventing later endpoint changes from rebinding the bearer.
    private final String api;
    /// Ensures signout, account switching or uninstall cannot launch a previously prepared token.
    private final BooleanSupplier active;
    /// Clears the controller's ephemeral state after a revoked/expired token.
    private final Runnable invalid;
    /// Bound to this exact prepared session and generation.
    private final TicketIssuer tickets;

    /// Creates an account solely from an already authenticated refreshed session, never from an offline name.
    SaoOathLaunchAccount(AuthlibInjectorServer server, AuthlibInjectorArtifactProvider provider, String name,
                         YggdrasilSession session, SaoAccountClient client, String metadata,
                         AuthlibInjectorArtifactInfo artifact, BooleanSupplier active, Runnable invalid, TicketIssuer tickets) {
        super(AccountID.generate(), server, provider, name, session);
        this.session = session; this.client = client; this.metadata = metadata; this.artifact = artifact;
        this.api = server.getUrl(); this.active = active; this.invalid = invalid; this.tickets = tickets;
    }

    /// The public issuing API namespace for local play-time accounting.
    URI apiRoot() { return URI.create(api); }
    /// Stable ARGUS ID profile key, preserved across nickname changes.
    String profileId() { return session.getSelectedProfile().getId().toString().replace("-", ""); }
    /// Mints a ticket immediately before process construction, refusing stale prepared accounts.
    SaoOathLaunchTicket ticket(Path instance, String server) throws Exception {
        if (!active.getAsBoolean()) throw new SaoAccountException("请重新登录 ARGUS ID", "invalidSession");
        return tickets.issue(instance, server);
    }

    /// Validates the refreshed SAO token immediately before game process construction and attaches the pinned agent.
    /// The game receives only a game-scoped token (join and validate only); the launcher session token stays in this
    /// process. Against an account server without game tokens the session token is passed as before, and logged.
    @Override public synchronized AuthInfo logIn() throws AuthenticationException {
        try {
            if (!active.getAsBoolean()) throw new SaoAccountException("请重新登录 ARGUS ID", "invalidSession");
            if (!api.equals(client.root().toString())) throw new SaoAccountException("账号地址已改变，请重新登录", "invalidSession");
            JsonObject validate = new JsonObject(); validate.addProperty("accessToken", session.getAccessToken());
            validate.addProperty("clientToken", session.getClientToken());
            client.json("POST", "authserver/validate", validate, null);
            String gameToken = gameToken();
            if (!active.getAsBoolean()) throw new SaoAccountException("请重新登录 ARGUS ID", "invalidSession");
            AuthInfo full = session.toAuthInfo();
            return new InjectedAuth(new AuthInfo(full.getUsername(), full.getUUID(), gameToken, full.getUserType(), full.getUserProperties()),
                    artifact, api, metadata);
        } catch (SaoAccountException failure) {
            // A transport outage or rate limit rejects this attempt, but does not revoke the issuing session.
            if (!SaoOathAccounts.transientFailure(failure)) invalid.run();
            throw new AuthenticationException(failure.getMessage());
        }
    }

    /// Mints the game-scoped token for this launch from the launcher session. Only an explicit "endpoint missing" reply
    /// (an account server older than sao_auth 1.9.3) falls back to the session token; every other failure stops the launch.
    private String gameToken() throws SaoAccountException {
        JsonObject reply;
        try { reply = client.json("POST", "sao/game-token", new JsonObject(), session.getAccessToken()); }
        catch (SaoAccountException failure) {
            if (!failure.code().equals("gameTokenUnavailable")) throw failure;
            Logger.LOG.warning("ARGUS ID server has no game-scoped token (older than sao_auth 1.9.3); the game gets the launcher session token");
            return session.getAccessToken();
        }
        try {
            String token = reply.get("accessToken").getAsString();
            String id = reply.getAsJsonObject("selectedProfile").get("id").getAsString();
            if (!token.matches("[A-Za-z0-9_-]{32,512}") || token.equals(session.getAccessToken()) || !id.equalsIgnoreCase(profileId()))
                throw new SaoAccountException("账号服务响应无效", "invalidResponse");
            Logger.registerAccessToken(token);
            return token;
        } catch (RuntimeException invalid) { throw new SaoAccountException("账号服务响应无效", "invalidResponse"); }
        finally { reply.remove("accessToken"); }
    }

    /// Refuses HMCL's offline fallback so an invalid SAO account can never launch with an offline identity.
    @Override public AuthInfo playOffline() throws AuthenticationException { throw new AuthenticationException("请重新登录 ARGUS ID"); }
    /// Password exchange belongs to the SAO account API; the game launch pipeline cannot initiate another login.
    @Override public AuthInfo logInWithPassword(String password) throws AuthenticationException { throw new AuthenticationException("请在 ARGUS ID 窗口重新登录"); }
    /// Refuses accidental persistence of the memory-only SAO token in HMCL account stores.
    @Override public void writePrivateData(JsonObject output) { throw new IllegalStateException("ARGUS ID 会话不能保存到磁盘"); }

    /// Authentication information whose only extra JVM values are public API metadata and the checked agent path.
    @NotNullByDefault
    private static final class InjectedAuth extends AuthInfo {
        /// Verified agent location.
        private final AuthlibInjectorArtifactInfo artifact;
        /// Captured public API root.
        private final String api;
        /// Base64 public server metadata.
        private final String metadata;

        /// Copies the real SAO profile and the game-scoped token into the standard HMCL launch authentication record.
        private InjectedAuth(AuthInfo auth, AuthlibInjectorArtifactInfo artifact, String api, String metadata) {
            super(auth.getUsername(), auth.getUUID(), auth.getAccessToken(), auth.getUserType(), auth.getUserProperties());
            this.artifact = artifact; this.api = api; this.metadata = metadata;
        }

        /// Adds the same injector arguments as HMCL, with no password and no legacy stdin account handoff.
        @Override public Arguments getLaunchArguments(@Nullable LaunchOptions options) {
            return new Arguments().addJVMArguments("-javaagent:" + artifact.location() + "=" + api,
                    "-Dauthlibinjector.side=client", "-Dauthlibinjector.yggdrasil.prefetched=" + metadata);
        }
    }
}
