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

import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/// Pure Windows ACL policy regressions; no native library or launcher UI is loaded.
@NotNullByDefault
final class SaoWindowsPrivatePathsTest {
    /// Synthetic process-token SID, unrelated to any real account.
    private static final String USER = "S-1-5-21-11-22-33-1001";
    /// Explicit full-control grant for the synthetic process user.
    private static final SaoWindowsPrivatePaths.Ace GRANT = new SaoWindowsPrivatePaths.Ace(USER, 0, 0, 0x1f01ff);

    /// Both normal and elevated default ownership permit the same current-user grant.
    @Test void acceptsUserAndAdministratorsOwners() {
        assertNull(SaoWindowsPrivatePaths.rejection(USER, List.of(GRANT), USER, true));
        assertNull(SaoWindowsPrivatePaths.rejection("S-1-5-32-544", List.of(GRANT), USER, true));
    }

    /// Additional grants, absent grants and foreign owners remain forbidden.
    @Test void rejectsWrongOwnerOrEntryCount() {
        assertEquals("foreign owner", SaoWindowsPrivatePaths.rejection("S-1-5-18", List.of(GRANT), USER, true));
        assertEquals("expected one ACE", SaoWindowsPrivatePaths.rejection(USER, List.of(GRANT, GRANT), USER, true));
        assertEquals("expected one ACE", SaoWindowsPrivatePaths.rejection(USER, List.of(), USER, true));
    }

    /// DACL protection and a non-inherited, effective grant are independently required.
    @Test void rejectsInheritanceAndInheritOnly() {
        assertEquals("unprotected DACL", SaoWindowsPrivatePaths.rejection(USER, List.of(GRANT), USER, false));
        assertEquals("inherited or inherit-only ACE", check(new SaoWindowsPrivatePaths.Ace(USER, 0, 0x10, 0x1f01ff)));
        assertEquals("inherited or inherit-only ACE", check(new SaoWindowsPrivatePaths.Ace(USER, 0, 0x08, 0x1f01ff)));
        assertNull(check(new SaoWindowsPrivatePaths.Ace(USER, 0, 3, 0x1f01ff)));
    }

    /// Everyone, Administrators, DENY and incomplete masks cannot replace the process-user grant.
    @Test void rejectsWrongTrusteeTypeAndMask() {
        assertEquals("ACE is not current user", check(new SaoWindowsPrivatePaths.Ace("S-1-1-0", 0, 0, 0x1f01ff)));
        assertEquals("ACE is not current user", check(new SaoWindowsPrivatePaths.Ace("S-1-5-32-544", 0, 0, 0x1f01ff)));
        assertEquals("ACE is not ALLOW", check(new SaoWindowsPrivatePaths.Ace(USER, 1, 0, 0x1f01ff)));
        for (int bit = 1; bit <= 0x100000; bit <<= 1) {
            if ((0x1f01ff & bit) != 0)
                assertEquals("ACE is not full control", check(new SaoWindowsPrivatePaths.Ace(USER, 0, 0, 0x1f01ff & ~bit)));
        }
    }

    /// SDDL contains exactly one protected full-control grant, inheritable only for directories.
    @Test void descriptorUsesOnlyTokenSid() {
        assertEquals("D:P(A;;FA;;;" + USER + ")", SaoWindowsPrivatePaths.descriptor(USER, false));
        assertEquals("D:P(A;OICI;FA;;;" + USER + ")", SaoWindowsPrivatePaths.descriptor(USER, true));
        assertThrows(IllegalArgumentException.class, () -> SaoWindowsPrivatePaths.descriptor(USER + ")(A;;FA;;;WD)", false));
    }

    /// Applies a single candidate grant against an otherwise valid policy snapshot.
    private static @Nullable String check(SaoWindowsPrivatePaths.Ace ace) {
        return SaoWindowsPrivatePaths.rejection(USER, List.of(ace), USER, true);
    }
}
