/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.auth.*;
import org.jackhuang.hmcl.auth.authlibinjector.*;
import org.jackhuang.hmcl.auth.yggdrasil.YggdrasilSession;
import org.jackhuang.hmcl.game.Arguments;
import org.jackhuang.hmcl.game.LaunchOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.function.BooleanSupplier;

/// An engine-owned HMCL injector account with no offline fallback, protected-store persistence or password handoff.
@NotNullByDefault
final class SaoOathLaunchAccount extends AuthlibInjectorAccount {
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

    /// Creates an account solely from an already authenticated refreshed session, never from an offline name.
    SaoOathLaunchAccount(AuthlibInjectorServer server, AuthlibInjectorArtifactProvider provider, String name,
                         YggdrasilSession session, SaoAccountClient client, String metadata,
                         AuthlibInjectorArtifactInfo artifact, BooleanSupplier active, Runnable invalid) {
        super(AccountID.generate(), server, provider, name, session);
        this.session = session; this.client = client; this.metadata = metadata; this.artifact = artifact;
        this.api = server.getUrl(); this.active = active; this.invalid = invalid;
    }

    /// Validates the refreshed SAO token immediately before game process construction and attaches the pinned agent.
    @Override public synchronized AuthInfo logIn() throws AuthenticationException {
        try {
            if (!active.getAsBoolean()) throw new SaoAccountException("请重新登录誓约账号", "invalidSession");
            if (!api.equals(client.root().toString())) throw new SaoAccountException("账号地址已改变，请重新登录", "invalidSession");
            JsonObject validate = new JsonObject(); validate.addProperty("accessToken", session.getAccessToken());
            validate.addProperty("clientToken", session.getClientToken());
            client.json("POST", "authserver/validate", validate, null);
            if (!active.getAsBoolean()) throw new SaoAccountException("请重新登录誓约账号", "invalidSession");
            return new InjectedAuth(session.toAuthInfo(), artifact, api, metadata);
        } catch (SaoAccountException failure) { invalid.run(); throw new AuthenticationException(failure.getMessage()); }
    }

    /// Refuses HMCL's offline fallback so an invalid SAO account can never launch with an offline identity.
    @Override public AuthInfo playOffline() throws AuthenticationException { throw new AuthenticationException("请重新登录誓约账号"); }
    /// Password exchange belongs to the SAO account API; the game launch pipeline cannot initiate another login.
    @Override public AuthInfo logInWithPassword(String password) throws AuthenticationException { throw new AuthenticationException("请在誓约账号窗口重新登录"); }
    /// Refuses accidental persistence of the memory-only SAO token in HMCL account stores.
    @Override public void writePrivateData(JsonObject output) { throw new IllegalStateException("誓约会话不能保存到磁盘"); }

    /// Authentication information whose only extra JVM values are public API metadata and the checked agent path.
    @NotNullByDefault
    private static final class InjectedAuth extends AuthInfo {
        /// Verified agent location.
        private final AuthlibInjectorArtifactInfo artifact;
        /// Captured public API root.
        private final String api;
        /// Base64 public server metadata.
        private final String metadata;

        /// Copies the real SAO profile and token into the standard HMCL launch authentication record.
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
