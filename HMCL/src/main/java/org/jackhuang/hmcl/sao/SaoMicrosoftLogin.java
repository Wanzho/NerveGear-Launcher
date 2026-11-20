/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import javafx.application.Platform;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.setting.Accounts;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/// Reuses HMCL's Microsoft sign-in and protected account store without changing any screen source.
@NotNullByDefault
final class SaoMicrosoftLogin {
    /// Utility class.
    private SaoMicrosoftLogin() { }

    /// Production provider exposes remembered identity before any credential refresh.
    static SaoOathAccounts.PremiumProof provider() {
        return new SaoOathAccounts.PremiumProof() {
            /// Acquires a valid session without repeating a fresh login.
            public AuthInfo session() throws Exception { return SaoMicrosoftLogin.session(); }

            /// Refreshes the stored linked Microsoft profile when the account needs to prove ownership.
            @Override
            public AuthInfo session(@Nullable String preferredName) throws Exception { return SaoMicrosoftLogin.session(preferredName); }

            /// Reads an existing profile without network access or a login window.
            public @Nullable AuthInfo known() throws Exception { return SaoMicrosoftLogin.known(); }

            /// Reads optional premium sources; missing or expired sessions still allow the local library.
            public java.util.@org.jetbrains.annotations.Unmodifiable List<SaoPremiumSkins.Entry> library(@Nullable String name) {
                @Nullable AuthInfo auth = null;
                try { auth = stored(name); } catch (Exception ignored) { }
                return SaoPremiumSkins.load(auth == null ? null : auth.getAccessToken(),
                        SaoPremiumSkins.officialFile(System.getProperty("os.name"), java.nio.file.Path.of(System.getProperty("user.home")), System.getenv("APPDATA")),
                        SaoMicrosoftSkin::download);
            }

            /// Uses only the linked stored account, without renewal or interactive fallback.
            public @Nullable AuthInfo stored(@Nullable String name) throws Exception {
                return name == null ? SaoMicrosoftLogin.known() : SaoMicrosoftLogin.available(name);
            }
        };
    }

    /// Finds the preferred stored Microsoft identity on JavaFX without authenticating it.
    static @Nullable AuthInfo known() throws Exception {
        CompletableFuture<@Nullable AuthInfo> result = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
            try {
                if (Accounts.getSelectedAccount() instanceof MicrosoftAccount selected) result.complete(selected.playOffline());
                else {
                    for (Account account : Accounts.getAccounts()) if (account instanceof MicrosoftAccount microsoft) {
                        result.complete(microsoft.playOffline()); return;
                    }
                    result.complete(null);
                }
            } catch (Exception failure) { result.completeExceptionally(failure); }
        }); } catch (IllegalStateException noToolkit) { return null; }
        return result.get();
    }

    /// Reuses an unexpired provider session, including one just installed by the DONE flow.
    static AuthInfo reuse(MicrosoftAccount account) throws Exception {
        @Nullable AuthInfo fresh = account.validSession();
        return fresh != null ? fresh : SaoLoginSteps.timed("microsoft-refresh", account::logIn);
    }

    /// Reads only an already stored matching session; never opens login UI or refreshes credentials.
    static @Nullable AuthInfo available(String name) throws Exception {
        CompletableFuture<@Nullable AuthInfo> result = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
            try {
                for (Account account : Accounts.getAccounts())
                    if (account instanceof MicrosoftAccount microsoft && microsoft.getProfileName().equalsIgnoreCase(name)) {
                        result.complete(microsoft.playOffline()); return;
                    }
                result.complete(null);
            } catch (Exception failure) { result.completeExceptionally(failure); }
        }); } catch (IllegalStateException noToolkit) { return null; }
        return result.get();
    }

    /// Runs on the account worker; selected or existing Microsoft accounts are preferred over a new sign-in.
    static AuthInfo session() throws Exception {
        return session(null);
    }

    /// Acquires a session for the preferred stored profile, if available, before falling back to the selected one.
    private static AuthInfo session(@Nullable String preferredName) throws Exception {
        CompletableFuture<AuthInfo> result = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
            @Nullable MicrosoftAccount microsoft = null;
            if (preferredName != null) {
                for (Account account : Accounts.getAccounts()) {
                    if (account instanceof MicrosoftAccount match && match.getProfileName().equalsIgnoreCase(preferredName)) {
                        microsoft = match;
                        break;
                    }
                }
            }
            if (microsoft == null && Accounts.getSelectedAccount() instanceof MicrosoftAccount selected) microsoft = selected;
            if (microsoft == null) for (Account account : Accounts.getAccounts()) if (account instanceof MicrosoftAccount found) { microsoft = found; break; }
            if (microsoft != null) {
                MicrosoftAccount existing = microsoft;
                CompletableFuture.runAsync(() -> {
                    try { result.complete(reuse(existing)); }
                    catch (Exception failure) { Platform.runLater(() -> showLogin(existing, result)); }
                });
            } else showLogin(null, result);
        }); } catch (IllegalStateException noToolkit) { throw new SaoAccountException("请先登录 Microsoft", "noMicrosoftAccount"); }
        try { return result.get(); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof SaoAccountException safe) throw safe;
            throw new SaoAccountException("Microsoft 登录失败，请重试", "microsoftLoginFailed");
        } catch (InterruptedException interrupted) { result.cancel(false); Thread.currentThread().interrupt(); throw new SaoAccountException("已取消 Microsoft 登录", "cancelled"); }
    }

    /// Routes a new login through the page hook, retaining upstream UI only when no hook is installed.
    private static void showLogin(@Nullable MicrosoftAccount previous, CompletableFuture<AuthInfo> result) {
        if (result.isDone()) return;
        Runnable cancel = SaoMicrosoftFlows.present(() -> {
            if (!(Accounts.getSelectedAccount() instanceof MicrosoftAccount account)) {
                result.completeExceptionally(new SaoAccountException("Microsoft 登录失败，请重试", "microsoftLoginFailed"));
                return;
            }
            CompletableFuture.runAsync(() -> {
                try { result.complete(reuse(account)); }
                catch (Exception failure) { result.completeExceptionally(failure); }
            });
        }, message -> result.completeExceptionally(new SaoAccountException(message, "microsoftLoginFailed")));
        result.whenComplete((auth, failure) -> { if (result.isCancelled()) Platform.runLater(cancel); });
    }
}
