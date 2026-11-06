/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see SaoPackState.java for the full notice.
 */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;

/// The two supported HMCL account kinds; sao_auth is a separate one-use server password.
@NotNullByDefault
public enum SaoAccountKind {
    /// An offline name; the 誓约 server's sao_auth password guards it.
    OFFLINE,
    /// A Microsoft (正版) account through HMCL's Microsoft log-in.
    MICROSOFT
}
