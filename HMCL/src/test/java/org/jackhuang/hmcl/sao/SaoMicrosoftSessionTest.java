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
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/// Isolates the account notification toolkit from backend fixtures that intentionally run without JavaFX.
@NotNullByDefault
class SaoMicrosoftSessionTest {
    /// A valid freshly installed or restored Microsoft session performs zero validation/refresh calls.
    @Test void freshSessionIsReusedUntilProviderExpiry() throws Exception {
        org.jackhuang.hmcl.JavaFXLauncher.start();
        Class<?> @Unmodifiable [] interfaces = {org.jackhuang.hmcl.auth.OAuth.Callback.class};
        org.jackhuang.hmcl.auth.OAuth.Callback callback = (org.jackhuang.hmcl.auth.OAuth.Callback)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        interfaces,
                        (Object proxy, java.lang.reflect.Method method, Object @Nullable [] arguments) -> { throw new AssertionError("No OAuth allowed"); });
        AtomicInteger refreshes = new AtomicInteger();
        var service = new org.jackhuang.hmcl.auth.microsoft.MicrosoftService(callback) {
            /// Never connects to Microsoft during this test.
            @Override public boolean validate(long expiry, String type, String token) { return false; }

            /// Produces one renewed synthetic session after expiry.
            @Override public org.jackhuang.hmcl.auth.microsoft.MicrosoftSession refresh(org.jackhuang.hmcl.auth.microsoft.MicrosoftSession old) {
                refreshes.incrementAndGet();
                return new org.jackhuang.hmcl.auth.microsoft.MicrosoftSession(old.tokenType(), old.accessToken(),
                        System.currentTimeMillis() + 60000, old.refreshToken(), old.user(), old.profile());
            }

            /// Suppresses any incidental profile-property lookup.
            @Override public Optional<org.jackhuang.hmcl.auth.yggdrasil.CompleteGameProfile> getCompleteGameProfile(UUID id) { return Optional.empty(); }
        };
        for (boolean expired : new boolean @Unmodifiable [] {false, true}) {
            var metadata = new com.google.gson.JsonObject();
            metadata.addProperty("accountID", "account:22222222-2222-2222-2222-222222222222");
            metadata.addProperty("profileID", "11111111-1111-1111-1111-111111111111");
            var data = new com.google.gson.JsonObject();
            data.addProperty("profileName", "Fixture"); data.addProperty("tokenType", "Bearer");
            data.addProperty("accessToken", "fixture"); data.addProperty("refreshToken", "fixture");
            data.addProperty("userid", "fixture"); data.addProperty("notAfter", expired ? 0 : System.currentTimeMillis() + 60000);
            var account = new org.jackhuang.hmcl.auth.microsoft.MicrosoftAccountFactory(service).fromStorage(metadata, data);
            assertEquals("Fixture", SaoMicrosoftLogin.reuse(account).getUsername());
            SaoMicrosoftLogin.reuse(account);
            assertEquals(expired ? 1 : 0, refreshes.get());
        }
    }

}
