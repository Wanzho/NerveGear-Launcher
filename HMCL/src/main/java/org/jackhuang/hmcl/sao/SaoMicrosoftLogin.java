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

    /// Runs on the account worker; selected or existing Microsoft accounts are preferred over a new sign-in.
    static AuthInfo session() throws Exception {
        CompletableFuture<AuthInfo> result = new CompletableFuture<>();
        try { Platform.runLater(() -> {
            @Nullable MicrosoftAccount microsoft = Accounts.getSelectedAccount() instanceof MicrosoftAccount selected ? selected : null;
            if (microsoft == null) for (Account account : Accounts.getAccounts()) if (account instanceof MicrosoftAccount found) { microsoft = found; break; }
            if (microsoft != null) {
                MicrosoftAccount existing = microsoft;
                CompletableFuture.runAsync(() -> {
                    try { result.complete(existing.logIn()); }
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
                try { result.complete(account.logIn()); }
                catch (Exception failure) { result.completeExceptionally(failure); }
            });
        }, message -> result.completeExceptionally(new SaoAccountException(message, "microsoftLoginFailed")));
        result.whenComplete((auth, failure) -> { if (result.isCancelled()) Platform.runLater(cancel); });
    }
}
