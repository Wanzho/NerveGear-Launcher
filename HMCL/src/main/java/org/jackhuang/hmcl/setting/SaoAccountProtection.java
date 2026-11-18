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
package org.jackhuang.hmcl.setting;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/// Narrow SAO adapter to the exact protection envelope used by HMCL account private data.
/// Native OS storage remains preferred. This portable fallback is application-key obfuscation, not device-bound secrecy.
@NotNullByDefault
public final class SaoAccountProtection {
    /// Utility class; callers cannot select another protection mode.
    private SaoAccountProtection() { }

    /// Protects opaque credential bytes with HMCL's account codec, ignoring development settings that permit plain JSON.
    public static byte[] protect(byte[] bytes) throws IOException {
        try {
            JsonObject envelope = new JsonObject();
            ProtectedPayload.ProtectionMode.OBFUSCATED_V1.writePayload(envelope, new JsonPrimitive(Base64.getEncoder().encodeToString(bytes)));
            return envelope.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException failure) { throw new IOException("无法保护已保存账号"); }
    }

    /// Reveals only the supported protected format; plaintext envelopes are explicitly refused.
    public static byte[] reveal(byte[] bytes) throws IOException {
        try {
            JsonObject envelope = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (ProtectedPayload.ProtectionMode.fromEnvelope(envelope) != ProtectedPayload.ProtectionMode.OBFUSCATED_V1)
                throw new IOException("已保存账号没有受到保护");
            JsonPrimitive payload = ProtectedPayload.ProtectionMode.OBFUSCATED_V1.read(envelope, JsonPrimitive.class);
            return Base64.getDecoder().decode(payload.getAsString());
        } catch (RuntimeException failure) { throw new IOException("无法读取受保护账号"); }
    }
}
