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

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import org.jetbrains.annotations.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/// Installs persistent protected owner-only Windows DACLs on the launcher's own ticket path.
@NotNullByDefault
final class SaoWindowsPrivatePaths {
    /// DACL_SECURITY_INFORMATION plus PROTECTED_DACL_SECURITY_INFORMATION disables inherited grants.
    static final int PROTECTED_DACL = 0x80000004;
    /// BUILTIN\Administrators, the owner of folders created by an elevated process.
    static final String ADMINISTRATORS = "S-1-5-32-544";
    /// FILE_ALL_ACCESS: standard required rights, synchronization and all file-specific rights.
    static final int FILE_ALL_ACCESS = 0x001f01ff;
    /// Utility class; no native library is loaded on other platforms.
    private SaoWindowsPrivatePaths() {}

    /// Lists only the owned launcher root through the instance; never touches its parent, home or drive ACL.
    static @Unmodifiable List<Path> plan(Path root, Path instance) throws IOException {
        Path base = root.toAbsolutePath().normalize(), target = instance.toAbsolutePath().normalize();
        if (base.getParent() == null || !target.startsWith(base))throw new IOException("启动凭证目录不在启动器内");
        var paths = new ArrayList<Path>();paths.add(base);Path next = base;
        for (Path part:base.relativize(target)) { next = next.resolve(part); if (!next.equals(base))paths.add(next); }
        return List.copyOf(paths);
    }

    /// Builds an owner-only inheritable full-control ACL; SYSTEM and Administrators receive no exception.
    static String descriptor(String sid, boolean directory) {
        if (!sid.matches("S-1-[0-9]+(?:-[0-9]+)+"))throw new IllegalArgumentException("Invalid owner SID");
        return "D:P(A;" + (directory ? "OICI" : "") + ";FA;;;" + sid + ")";
    }

    /// Protects every launcher-owned ancestor before any ticket bytes are written; protection remains after exit.
    static void prepare(Path root,Path instance) throws IOException {
        if (!Platform.isWindows()) return;
        Path existing = root.toAbsolutePath().normalize();
        while (existing.getParent() != null && !Files.exists(existing))existing = existing.getParent();
        // FAT/exFAT drives (USB sticks) keep no permissions, so the ticket cannot be made private there.
        if (!Files.getFileStore(existing).supportsFileAttributeView(java.nio.file.attribute.AclFileAttributeView.class))
            throw new IOException("启动器所在磁盘不支持权限保护（如 exFAT U 盘），请把启动器放到 NTFS 磁盘");
        for (Path directory:plan(root,instance)) {
            SaoUninstaller.rejectLinksBelowRoot(directory);
            Files.createDirectories(directory);
            protect(directory,true);
        }
    }

    /// Replaces the entire DACL and marks it protected, failing closed on foreign ownership or native errors.
    static void protect(Path path,boolean directory) throws IOException {
        try {
            protectNative(path, directory);
        } catch (IOException failure) {
            // The launch failure logger records the cause chain while the UI shows actionable guidance.
            throw new IOException("无法保护启动凭证，请不要用管理员身份运行启动器，或把启动器放在自己的用户目录下", failure);
        }
    }

    /// Applies and verifies the native DACL, retaining detailed failures for the launch log.
    private static void protectNative(Path path, boolean directory) throws IOException {
        WindowsSecurity api = Native.load("Advapi32",WindowsSecurity.class);
        WindowsKernel kernel = Native.load("Kernel32",WindowsKernel.class);
        String current = currentSid(api,kernel);
        var owner = new PointerByReference();var security = new PointerByReference();
        int status = api.GetNamedSecurityInfoW(new WString(path.toAbsolutePath().toString()),1,1,owner,null,null,null,security);
        if (status != 0)throw new IOException("GetNamedSecurityInfoW(owner) failed: " + status);
        try {
            @Nullable Pointer ownerSid = owner.getValue();
            if (ownerSid == null)throw new IOException("无法读取启动凭证目录所有者");
            // A folder first created by "以管理员身份运行" is owned by Administrators; that owner is accepted too.
            String found = sid(api,kernel,ownerSid);
            if (!current.equals(found) && !found.equals(ADMINISTRATORS))throw new IOException("启动凭证目录属于其他用户");
        }
        finally { kernel.LocalFree(security.getValue()); }
        try { verify(path,api,kernel); return; } catch (IOException changed){ /* First use or changed permissions require a protected replacement. */ }
        var descriptor = new PointerByReference();
        if (!api.ConvertStringSecurityDescriptorToSecurityDescriptorW(new WString(descriptor(current,directory)),1,descriptor,null))throw new IOException("ConvertStringSecurityDescriptorToSecurityDescriptorW failed: " + kernel.GetLastError());
        try { if (!api.SetFileSecurityW(new WString(path.toAbsolutePath().toString()),PROTECTED_DACL,descriptor.getValue()))throw new IOException("SetFileSecurityW failed: " + kernel.GetLastError()); }
        finally { kernel.LocalFree(descriptor.getValue()); }
        verify(path,api,kernel);
    }

    /// Immutable native ACE values; sid is canonical, and type/flags/mask retain their Win32 values.
    /// @param sid canonical trustee SID (empty for unsupported ACE types)
    /// @param type ACE_HEADER.AceType
    /// @param flags ACE_HEADER.AceFlags
    /// @param mask ACCESS_ALLOWED_ACE.Mask (zero for unsupported types)
    @NotNullByDefault
    record Ace(String sid, int type, int flags, int mask) {}

    /// Returns a diagnostic rejection reason, or null for a protected current-process-user full-control grant.
    /// Ownership by Administrators is allowed for elevated creation, but never grants that group an ACE.
    static @Nullable String rejection(String ownerSid, List<Ace> entries, String currentSid, boolean protectedDacl) {
        if (!ownerSid.equals(currentSid) && !ownerSid.equals(ADMINISTRATORS)) return "foreign owner";
        if (!protectedDacl) return "unprotected DACL";
        if (entries.size() != 1) return "expected one ACE";
        Ace ace = entries.get(0);
        if (ace.type() != 0) return "ACE is not ALLOW";
        if ((ace.flags() & 0x18) != 0) return "inherited or inherit-only ACE";
        if (!ace.sid().equals(currentSid)) return "ACE is not current user";
        if ((ace.mask() & FILE_ALL_ACCESS) != FILE_ALL_ACCESS) return "ACE is not full control";
        return null;
    }

    /// Reads owner, protected bit and native ACEs from one descriptor; never compares localized principal names.
    private static void verify(Path path, WindowsSecurity api, WindowsKernel kernel) throws IOException {
        String current = currentSid(api, kernel);
        var security = new PointerByReference();
        var owner = new PointerByReference();
        var dacl = new PointerByReference();
        int status = api.GetNamedSecurityInfoW(new WString(path.toAbsolutePath().toString()), 1, 5,
                owner, null, dacl, null, security);
        if (status != 0) throw new IOException("GetNamedSecurityInfoW(owner/DACL) failed: " + status);
        try {
            @Nullable Pointer ownerSid = owner.getValue();
            @Nullable Pointer acl = dacl.getValue();
            if (ownerSid == null || acl == null) throw new IOException("Missing owner or NULL DACL");
            var control = new ShortByReference();
            var revision = new IntByReference();
            if (!api.GetSecurityDescriptorControl(security.getValue(), control, revision))
                throw new IOException("GetSecurityDescriptorControl failed: " + kernel.GetLastError());
            if (!api.IsValidAcl(acl)) throw new IOException("Invalid DACL");
            int count;
            // ACL_SIZE_INFORMATION consists of three DWORDs; query the count through the Win32 API.
            try (var information = new Memory(12)) {
                if (!api.GetAclInformation(acl, information, 12, 2))
                    throw new IOException("GetAclInformation failed: " + kernel.GetLastError());
                count = information.getInt(0);
            }
            if (count < 0 || count > 65535) throw new IOException("Invalid ACE count");
            var entries = new ArrayList<Ace>(count);
            for (int index = 0; index < count; index++) {
                var result = new PointerByReference();
                if (!api.GetAce(acl, index, result)) throw new IOException("GetAce failed: " + kernel.GetLastError());
                @Nullable Pointer ace = result.getValue();
                if (ace == null) throw new IOException("Missing ACE");
                int type = Byte.toUnsignedInt(ace.getByte(0));
                int flags = Byte.toUnsignedInt(ace.getByte(1));
                // Only ACCESS_ALLOWED_ACE has the layout accepted here: DWORD mask at 4, SID at 8.
                // Do not interpret object/callback ACEs as this layout; the policy rejects their type.
                if (type != 0) entries.add(new Ace("", type, flags, 0));
                else {
                    int size = Short.toUnsignedInt(ace.getShort(2));
                    if (size < 16) throw new IOException("Truncated ALLOW ACE");
                    int sidSize = 8 + 4 * Byte.toUnsignedInt(ace.getByte(9));
                    if (8 + sidSize > size) throw new IOException("Truncated ACE SID");
                    entries.add(new Ace(sid(api, kernel, ace.share(8)), type, flags, ace.getInt(4)));
                }
            }
            @Nullable String reason = rejection(sid(api, kernel, ownerSid), entries, current, (control.getValue() & 0x1000) != 0);
            if (reason != null) throw new IOException("Launch ticket ACL rejected: " + reason);
        } finally {
            kernel.LocalFree(security.getValue());
        }
    }

    /// Obtains the current process token's user SID, independent of localized account names.
    private static String currentSid(WindowsSecurity api,WindowsKernel kernel)throws IOException {
        var token = new PointerByReference();
        if (!api.OpenProcessToken(kernel.GetCurrentProcess(),8,token))throw new IOException("无法读取当前用户");
        try {
            var needed = new IntByReference();api.GetTokenInformation(token.getValue(),1,null,0,needed);
            if (needed.getValue() < Native.POINTER_SIZE || needed.getValue() > 65536)throw new IOException("当前用户信息无效");
            try (var buffer = new Memory(needed.getValue())) {
                if (!api.GetTokenInformation(token.getValue(),1,buffer,(int)buffer.size(),needed))throw new IOException("无法读取当前用户");
                return sid(api,kernel,Objects.requireNonNull(buffer.getPointer(0)));
            }
        } finally { kernel.CloseHandle(token.getValue()); }
    }

    /// Converts an owned native SID into its canonical public identifier and frees the native string.
    private static String sid(WindowsSecurity api,WindowsKernel kernel,Pointer value)throws IOException {
        var text = new PointerByReference(); if (!api.ConvertSidToStringSidW(value,text))throw new IOException("用户标识无效");
        try { return Objects.requireNonNull(text.getValue()).getWideString(0); } finally { kernel.LocalFree(text.getValue()); }
    }

    /// Native security functions used without shell commands or application registration values.
    @NotNullByDefault
    interface WindowsSecurity extends StdCallLibrary {
        /// Reads owner or DACL plus an allocated security descriptor.
        int GetNamedSecurityInfoW(WString path,int type,int information,@Nullable PointerByReference owner,@Nullable PointerByReference group,@Nullable PointerByReference dacl,@Nullable PointerByReference sacl,PointerByReference descriptor);

        /// Validates the ACL header and ACE boundaries before reading native fields.
        boolean IsValidAcl(Pointer acl);

        /// Reads ACL_SIZE_INFORMATION (class 2) without depending on the ACL header layout.
        boolean GetAclInformation(Pointer acl, Pointer information, int length, int informationClass);

        /// Returns a borrowed ACE pointer valid until the containing security descriptor is freed.
        boolean GetAce(Pointer acl, int index, PointerByReference ace);

        /// Parses the protected owner-only DACL.
        boolean ConvertStringSecurityDescriptorToSecurityDescriptorW(WString text,int revision,PointerByReference result,@Nullable IntByReference size);

        /// Applies the complete protected DACL.
        boolean SetFileSecurityW(WString path,int information,@Nullable Pointer descriptor);

        /// Checks whether inheritance is disabled.
        boolean GetSecurityDescriptorControl(@Nullable Pointer descriptor,ShortByReference control,IntByReference revision);

        /// Opens the process token for TOKEN_QUERY only.
        boolean OpenProcessToken(@Nullable Pointer process,int access,PointerByReference token);

        /// Reads TokenUser into bounded memory.
        boolean GetTokenInformation(@Nullable Pointer token,int type,@Nullable Pointer data,int length,IntByReference needed);

        /// Allocates a canonical SID string.
        boolean ConvertSidToStringSidW(Pointer value,PointerByReference result);
    }

    /// Minimal native allocation and token-handle lifecycle.
    @NotNullByDefault
    interface WindowsKernel extends StdCallLibrary {
        /// Returns the calling thread's last native error code immediately after a failed Win32 call.
        int GetLastError();

        /// Returns the current-process pseudo-handle.
        @Nullable Pointer GetCurrentProcess();

        /// Frees memory allocated by the security APIs.
        @Nullable Pointer LocalFree(@Nullable Pointer memory);

        /// Releases an opened token handle.
        boolean CloseHandle(@Nullable Pointer handle);
    }
}
