/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see SaoPackState.java for the full notice.
 */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;

/// A 誓约 account call that failed, with a message ready to show in the launcher's language
/// (for example "密码错误", "验证码不对或已过期", "这个名字已被注册").
@NotNullByDefault
public final class SaoAccountException extends Exception {
    /// What went wrong, for the API's error code, so the screen can react (for example `needVerify`).
    private final String code;

    /// A failure with a message and no code.
    ///
    /// @param message shown to the player
    public SaoAccountException(String message) {
        this(message, "");
    }

    /// A failure with a message and the API's error code.
    ///
    /// @param message shown to the player
    /// @param code    the API's error code, or empty
    public SaoAccountException(String message, String code) {
        super(message, null, false, false);
        this.code = code;
    }

    /// The API's error code, or empty.
    public String code() {
        return code;
    }
}
