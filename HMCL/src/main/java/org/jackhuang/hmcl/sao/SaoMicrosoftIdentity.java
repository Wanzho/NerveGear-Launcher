/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import java.nio.file.Path;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Display-only Microsoft profile; never grants ARGUS access or contains credentials.
@NotNullByDefault
public record SaoMicrosoftIdentity(String name, @Nullable Path skin, boolean slim) { }
