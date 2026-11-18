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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/// Protected remembered-name/password storage, independent of ephemeral authentication tokens.
@NotNullByDefault
interface SaoOathCredentials {
    /// A decrypted remembered credential; its password belongs to the caller and must be wiped.
    @NotNullByDefault
    record Credential(String name, char[] password) { }

    /// Reads the protected pair, or null when the player has not opted in.
    @Nullable Credential read() throws IOException;

    /// Atomically replaces the protected pair; never retains the supplied password array.
    void write(String name, char[] password) throws IOException;

    /// Removes every native and fallback copy owned by this launcher root.
    void delete() throws IOException;

    /// The active protection provider, without account or root identifiers.
    String backend();
}
