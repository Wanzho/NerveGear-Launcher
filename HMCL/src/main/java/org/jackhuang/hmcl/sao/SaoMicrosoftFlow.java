/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import javafx.beans.property.ReadOnlyObjectProperty;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.function.Supplier;

/// Screen-independent Microsoft login. Methods may be called from any thread; states publish on FX.
@NotNullByDefault
public interface SaoMicrosoftFlow {
    /// Lifecycle stages; the final three stages are terminal.
    @NotNullByDefault
    enum Kind { STARTING, BROWSER, DEVICE_CODE, SIGNING_IN, DONE, FAILED, CANCELLED }

    /// Only fields relevant to kind are populated; all other strings are empty, never null.
    /// url is the browser link; userCode/verificationUri are for device login; step and message are safe Chinese text.
    @NotNullByDefault
    record State(Kind kind, String url, String userCode, String verificationUri, String step, String name, String message) { }

    /// Observable state, published on the FX thread.
    ReadOnlyObjectProperty<State> state();
    /// Opens or reopens the current sign-in link in the default browser.
    void openBrowser();
    /// Cancels the browser attempt and starts device-code authentication.
    void useDeviceCode();
    /// Closes the local server, interrupts polling, and enters CANCELLED unless already terminal.
    void cancel();

    /// A page-owned presentation hook, invoked on FX; closing the page must cancel its flow.
    @FunctionalInterface
    @NotNullByDefault
    interface Presenter {
        /// Uses the supplied factory for every attempt and cancels the caller when the window closes.
        void present(Supplier<SaoMicrosoftFlow> start, Runnable cancel);
    }

    /// Installs the page hook, or null to restore the stock CreateAccountPane fallback.
    static void setPresenter(@Nullable Presenter presenter) { SaoMicrosoftFlows.presenter = presenter; }

    /// Starts and presents login through the installed service, invoking the continuation only after DONE.
    /// Without a presenter, uses the stock dialog without starting a second OAuth attempt.
    static void present(Runnable onDone) { SaoMicrosoftFlows.present(onDone); }
}
