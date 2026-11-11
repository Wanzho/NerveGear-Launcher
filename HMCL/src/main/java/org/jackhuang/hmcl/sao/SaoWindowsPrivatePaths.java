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
    /// Utility class; no native library is loaded on other platforms.
    private SaoWindowsPrivatePaths() {}

    /// Lists only the owned launcher root through the instance; never touches its parent, home or drive ACL.
    static @Unmodifiable List<Path> plan(Path root, Path instance) throws IOException {
        Path base=root.toAbsolutePath().normalize(), target=instance.toAbsolutePath().normalize();
        if(base.getParent()==null||!target.startsWith(base))throw new IOException("启动凭证目录不在启动器内");
        var paths=new ArrayList<Path>();paths.add(base);Path next=base;
        for(Path part:base.relativize(target)){next=next.resolve(part);if(!next.equals(base))paths.add(next);}
        return List.copyOf(paths);
    }

    /// Builds an owner-only inheritable full-control ACL; SYSTEM and Administrators receive no exception.
    static String descriptor(String sid, boolean directory) {
        if(!sid.matches("S-1-[0-9]+(?:-[0-9]+)+"))throw new IllegalArgumentException("Invalid owner SID");
        return "D:P(A;"+(directory?"OICI":"")+";FA;;;"+sid+")";
    }

    /// Protects every launcher-owned ancestor before any ticket bytes are written; protection remains after exit.
    static void prepare(Path root,Path instance) throws IOException {
        if(!Platform.isWindows())return;
        Path existing=root.toAbsolutePath().normalize();
        while(existing.getParent()!=null&&!Files.exists(existing))existing=existing.getParent();
        // FAT/exFAT drives (USB sticks) keep no permissions, so the ticket cannot be made private there.
        if(!Files.getFileStore(existing).supportsFileAttributeView(java.nio.file.attribute.AclFileAttributeView.class))
            throw new IOException("启动器所在磁盘不支持权限保护（如 exFAT U 盘），请把启动器放到 NTFS 磁盘");
        for(Path directory:plan(root,instance)){
            SaoUninstaller.rejectLinksBelowRoot(directory);
            Files.createDirectories(directory);
            protect(directory,true);
        }
    }

    /// Replaces the entire DACL and marks it protected, failing closed on foreign ownership or native errors.
    static void protect(Path path,boolean directory) throws IOException {
        WindowsSecurity api=Native.load("Advapi32",WindowsSecurity.class);
        WindowsKernel kernel=Native.load("Kernel32",WindowsKernel.class);
        String current=currentSid(api,kernel);
        var owner=new PointerByReference();var security=new PointerByReference();
        int status=api.GetNamedSecurityInfoW(new WString(path.toAbsolutePath().toString()),1,1,owner,null,null,null,security);
        if(status!=0)throw new IOException("无法读取启动凭证目录所有者");
        try{
            @Nullable Pointer ownerSid=owner.getValue();
            if(ownerSid==null)throw new IOException("无法读取启动凭证目录所有者");
            // A folder first created by "以管理员身份运行" is owned by Administrators; that owner is accepted too.
            String found=sid(api,kernel,ownerSid);
            if(!current.equals(found)&&!found.equals(ADMINISTRATORS))throw new IOException("启动凭证目录属于其他用户");
        }
        finally{kernel.LocalFree(security.getValue());}
        try{verify(path,api,kernel);return;}catch(IOException changed){ /* First use or changed permissions require a protected replacement. */ }
        var descriptor=new PointerByReference();
        if(!api.ConvertStringSecurityDescriptorToSecurityDescriptorW(new WString(descriptor(current,directory)),1,descriptor,null))throw new IOException("无法创建私有启动凭证权限");
        try{if(!api.SetFileSecurityW(new WString(path.toAbsolutePath().toString()),PROTECTED_DACL,descriptor.getValue()))throw new IOException("无法保护启动凭证目录");}
        finally{kernel.LocalFree(descriptor.getValue());}
        verify(path,api,kernel);
    }

    /// Reads the resulting protected bit and exact single owner grant rather than trusting a successful setter.
    private static void verify(Path path,WindowsSecurity api,WindowsKernel kernel)throws IOException {
        var security=new PointerByReference();
        if(api.GetNamedSecurityInfoW(new WString(path.toAbsolutePath().toString()),1,4,null,null,null,null,security)!=0)throw new IOException("无法检查启动凭证权限");
        try{
            var control=new ShortByReference();var revision=new IntByReference();
            if(!api.GetSecurityDescriptorControl(security.getValue(),control,revision)||(control.getValue()&0x1000)==0)throw new IOException("启动凭证仍继承外部权限");
        }finally{kernel.LocalFree(security.getValue());}
        @Nullable java.nio.file.attribute.AclFileAttributeView view=Files.getFileAttributeView(path,java.nio.file.attribute.AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(view==null)throw new IOException("无法检查启动凭证权限");
        var entries=view.getAcl();
        if(entries.size()!=1||entries.get(0).type()!=java.nio.file.attribute.AclEntryType.ALLOW||!entries.get(0).principal().equals(view.getOwner())
                ||!entries.get(0).permissions().containsAll(EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)))throw new IOException("启动凭证不是仅所有者可用");
    }

    /// Obtains the current process token's user SID, independent of localized account names.
    private static String currentSid(WindowsSecurity api,WindowsKernel kernel)throws IOException {
        var token=new PointerByReference();
        if(!api.OpenProcessToken(kernel.GetCurrentProcess(),8,token))throw new IOException("无法读取当前用户");
        try{
            var needed=new IntByReference();api.GetTokenInformation(token.getValue(),1,null,0,needed);
            if(needed.getValue()<Native.POINTER_SIZE||needed.getValue()>65536)throw new IOException("当前用户信息无效");
            try(var buffer=new Memory(needed.getValue())){
                if(!api.GetTokenInformation(token.getValue(),1,buffer,(int)buffer.size(),needed))throw new IOException("无法读取当前用户");
                return sid(api,kernel,Objects.requireNonNull(buffer.getPointer(0)));
            }
        }finally{kernel.CloseHandle(token.getValue());}
    }

    /// Converts an owned native SID into its canonical public identifier and frees the native string.
    private static String sid(WindowsSecurity api,WindowsKernel kernel,Pointer value)throws IOException {
        var text=new PointerByReference();if(!api.ConvertSidToStringSidW(value,text))throw new IOException("用户标识无效");
        try{return Objects.requireNonNull(text.getValue()).getWideString(0);}finally{kernel.LocalFree(text.getValue());}
    }

    /// Native security functions used without shell commands or application registration values.
    @NotNullByDefault
    interface WindowsSecurity extends StdCallLibrary {
        /// Reads owner or DACL plus an allocated security descriptor.
        int GetNamedSecurityInfoW(WString path,int type,int information,@Nullable PointerByReference owner,@Nullable PointerByReference group,@Nullable PointerByReference dacl,@Nullable PointerByReference sacl,PointerByReference descriptor);
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
        /// Returns the current-process pseudo-handle.
        @Nullable Pointer GetCurrentProcess();
        /// Frees memory allocated by the security APIs.
        @Nullable Pointer LocalFree(@Nullable Pointer memory);
        /// Releases an opened token handle.
        boolean CloseHandle(@Nullable Pointer handle);
    }
}
