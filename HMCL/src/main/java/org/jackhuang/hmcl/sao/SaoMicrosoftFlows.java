/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import org.jackhuang.hmcl.auth.*;
import org.jackhuang.hmcl.auth.microsoft.*;
import org.jackhuang.hmcl.game.OAuthServer;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/// Production adapters and presentation routing; no new UI is constructed here.
@NotNullByDefault
final class SaoMicrosoftFlows {
    /// Page-installed presenter; null preserves upstream UI until the new page is ready.
    static volatile SaoMicrosoftFlow.@Nullable Presenter presenter;
    /// Latest presented flow; superseded continuations must never fire later.
    private static @Nullable SaoMicrosoftFlow active;
    /// Identifies the latest presentation, including stock-dialog fallbacks.
    private static long presentationGeneration;
    /// Utility class.
    private SaoMicrosoftFlows() { }
    /// Creates an immediately running production flow with a five-minute deadline.
    static SaoMicrosoftFlow start() {
        return new SaoMicrosoftFlowEngine(HmclTransport::new, Platform::runLater, FXUtils::openLink, 300_000);
    }
    /// Routes the page's continuation through the backend or the existing fallback.
    static void present(Runnable onDone) { present(onDone, message -> { }); }
    /// Routes login with a safe terminal-error callback for worker callers.
    static Runnable present(Runnable onDone, Consumer<String> onFailure) {
        long id = ++presentationGeneration;
        if (active != null) { active.cancel(); active = null; }
        SaoMicrosoftFlow.@Nullable Presenter installed = presenter;
        if (installed == null) {
            @Nullable Account previous = Accounts.getSelectedAccount();
            CreateAccountPane pane = new CreateAccountPane(Accounts.FACTORY_MICROSOFT);
            pane.addEventHandler(DialogCloseEvent.CLOSE, event -> {
                if (id != presentationGeneration) return;
                if (Accounts.getSelectedAccount() instanceof MicrosoftAccount && Accounts.getSelectedAccount() != previous) onDone.run();
                else onFailure.accept("已取消 Microsoft 登录");
            });
            Controllers.dialog(pane);
            return () -> { if (presentationGeneration == id) ++presentationGeneration; };
        }
        SaoMicrosoftFlow flow = SaoServices.service().microsoftSignIn();
        active = flow;
        observe(flow, () -> {
            if (id == presentationGeneration) { active = null; onDone.run(); }
        }, message -> {
            if (id == presentationGeneration) active = null;
            onFailure.accept(message);
        });
        try { installed.present(flow); }
        catch (RuntimeException failure) { flow.cancel(); throw failure; }
        return flow::cancel;
    }
    /// Listens until one terminal state, including an already-completed scripted flow.
    static void observe(SaoMicrosoftFlow flow, Runnable onDone, Consumer<String> onFailure) {
        ChangeListener<SaoMicrosoftFlow.State> listener = new ChangeListener<>() {
            /// Removes itself before invoking user code, preventing duplicate continuations.
            @Override public void changed(javafx.beans.value.ObservableValue<? extends SaoMicrosoftFlow.State> observable,
                    SaoMicrosoftFlow.@Nullable State previous, SaoMicrosoftFlow.State state) {
                switch (state.kind()) {
                    case DONE -> { flow.state().removeListener(this); onDone.run(); }
                    case FAILED, CANCELLED -> {
                        flow.state().removeListener(this);
                        onFailure.accept(state.kind() == SaoMicrosoftFlow.Kind.CANCELLED ? "已取消 Microsoft 登录" : state.message());
                    }
                    default -> { }
                }
            }
        };
        flow.state().addListener(listener);
        listener.changed(flow.state(), null, flow.state().get());
    }
    /// Converts known failures without returning arbitrary provider text, credentials or stack traces.
    static String error(Throwable failure) {
        for (@Nullable Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String text = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
            if (text.contains("invalid_request") && text.contains("redirect_uri")) return "登录配置有误，请联系管理员";
            if (cause instanceof OAuthServer.MicrosoftAuthenticationNotSupportedException) return "登录配置有误，请联系管理员";
            if (cause instanceof MicrosoftService.MinecraftJavaEditionProfileNotFoundException
                    || cause instanceof MicrosoftService.MinecraftJavaEditionLicenseNotFoundException
                    || cause instanceof NoCharacterException) return "这个微软账号还没有购买 Minecraft";
            if (cause instanceof MicrosoftService.XboxAuthorizationException xbox) {
                long code = xbox.getErrorCode();
                // Chinese plain-text equivalents of HMCL's linked help messages, without HTML in State.
                if (code == MicrosoftService.XboxAuthorizationException.ADD_FAMILY)
                    return "请更改你的账户出生日期，使年龄满 18 岁以上，或将账户加入到家庭中。";
                if (code == MicrosoftService.XboxAuthorizationException.COUNTRY_UNAVAILABLE)
                    return "你所在的国家或地区不受 XBOX Live 的支持。";
                if (code == MicrosoftService.XboxAuthorizationException.MISSING_XBOX_ACCOUNT)
                    return "请确认你已经购买了 Minecraft。若已购买，请创建游戏档案。";
                if (code == MicrosoftService.XboxAuthorizationException.BANNED) return "你的账户可能被 XBOX Live 封禁。";
                return "Xbox 账号验证失败，请重试";
            }
            if (cause instanceof TimeoutException || cause instanceof NoSelectedCharacterException) return "登录超时";
        }
        for (@Nullable Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException || cause instanceof ServerDisconnectException) return "网络连接失败，请重试";
        }
        return "Microsoft 登录失败，请重试";
    }
    /// Per-attempt OAuth callback, independent of the global callbacks used by stock dialogs.
    @NotNullByDefault
    private static final class HmclTransport implements SaoMicrosoftFlowEngine.Transport, OAuth.Callback {
        /// Upstream server factory supplies the configured client id and ports 29111–29115.
        private final OAuthServer.Factory factory = new OAuthServer.Factory();
        /// Active server, closed on cancellation even while waitFor is blocked.
        private OAuth.@Nullable Session session;
        /// Closing before server creation prevents a late server from escaping cancellation.
        private boolean closed;
        /// Progress sink replaced before authentication begins.
        private Consumer<SaoMicrosoftFlow.State> emit = state -> { };
        /// Authenticates using the upstream Microsoft account factory and service.
        @Override public SaoMicrosoftFlowEngine.Result run(boolean device, Consumer<SaoMicrosoftFlow.State> emit) throws Exception {
            this.emit = emit;
            MicrosoftAccount account = new MicrosoftAccountFactory(new MicrosoftService(this))
                    .create(null, null, null, null, device ? OAuth.GrantFlow.DEVICE : OAuth.GrantFlow.AUTHORIZATION_CODE);
            emit.accept(new SaoMicrosoftFlow.State(SaoMicrosoftFlow.Kind.SIGNING_IN, "", "", "", "正在保存 Minecraft 账号", "", ""));
            return new SaoMicrosoftFlowEngine.Result(account.getProfileName(), () -> {
                if (Accounts.isAccountFilesReadOnly(account)) {
                    try { Accounts.forceOverwriteAccountFiles(account); }
                    catch (IOException failure) { throw new IllegalStateException("Account storage failed"); }
                }
                int index = Accounts.getAccounts().indexOf(account);
                if (index == -1) Accounts.getAccounts().add(account);
                else { Accounts.getAccounts().remove(index); Accounts.getAccounts().add(index, account); }
                Accounts.setSelectedAccount(account);
            }, install -> {
                if (Accounts.isAccountFilesReadOnly(account))
                    Controllers.confirmBackupAndOverwrite(org.jackhuang.hmcl.util.i18n.I18n.i18n("account.storage.read_only"), install::run);
                else install.run();
            });
        }
        /// Opens HMCL's localhost server and wraps its successful callback with truthful progress.
        @Override public synchronized OAuth.Session startServer() throws IOException, AuthenticationException {
            if (closed) throw new IOException("Cancelled");
            OAuth.Session opened = factory.startServer(); session = opened;
            return new OAuth.Session() {
                /// PKCE verifier remains private to OAuth.
                @Override public String getCodeVerifier() { return opened.getCodeVerifier(); }
                /// CSRF state remains private to OAuth.
                @Override public String getState() { return opened.getState(); }
                /// Uses the upstream localhost redirect URI.
                @Override public String getRedirectURI() { return opened.getRedirectURI(); }
                /// Waits for redirect before announcing downstream authentication.
                @Override public String waitFor() throws InterruptedException, java.util.concurrent.ExecutionException {
                    String code = opened.waitFor(); signingIn(); return code;
                }
                /// Releases the upstream listener.
                @Override public void close() { opened.close(); }
            };
        }
        /// Publishes the verification code; the device secret is never exposed.
        @Override public void grantDeviceCode(String userCode, String verificationURI) {
            emit.accept(new SaoMicrosoftFlow.State(SaoMicrosoftFlow.Kind.DEVICE_CODE, "", userCode, verificationURI, "", "", ""));
        }
        /// Announces Xbox authentication after device authorization completes.
        @Override public void loginCompletedDeviceCode() { signingIn(); }
        /// Publishes authorization URLs; device URLs remain available through DEVICE_CODE.
        @Override public void openBrowser(OAuth.GrantFlow flow, String url) {
            if (flow == OAuth.GrantFlow.AUTHORIZATION_CODE)
                emit.accept(new SaoMicrosoftFlow.State(SaoMicrosoftFlow.Kind.BROWSER, url, "", "", "", "", ""));
        }
        /// Shares the launcher's configured Microsoft application id.
        @Override public String getClientId() { return factory.getClientId(); }
        /// Emits a safe description of downstream account verification.
        private void signingIn() {
            emit.accept(new SaoMicrosoftFlow.State(SaoMicrosoftFlow.Kind.SIGNING_IN, "", "", "", "正在验证 Xbox 账号", "", ""));
        }
        /// Idempotently closes the callback server; the engine interrupts polling separately.
        @Override public synchronized void close() { closed = true; if (session != null) session.close(); }
    }
}
