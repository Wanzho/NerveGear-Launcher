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
