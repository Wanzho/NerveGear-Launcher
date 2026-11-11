/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/// Platform-independent ACL planning contracts; these do not claim Windows native runtime validation.
@NotNullByDefault
class SaoPrivateAclTest {
    /// Disposable plan root; no existing launcher or game directory is accessed.
    @TempDir Path temporary;
    /// The complete controlled ancestor chain excludes the user-home/system parent and is immutable.
    @Test void planContainsOnlyControlledAncestors() throws Exception {
        Path root=temporary.resolve("launcher"), instance=root.resolve("minecraft/versions/oath");
        @Unmodifiable List<Path> plan=SaoWindowsPrivatePaths.plan(root,instance);
        assertEquals(List.of(root,root.resolve("minecraft"),root.resolve("minecraft/versions"),instance),plan);
        assertThrows(UnsupportedOperationException.class,()->plan.add(temporary));
        assertEquals(List.of(root),SaoWindowsPrivatePaths.plan(root,root));
        assertThrows(java.io.IOException.class,()->SaoWindowsPrivatePaths.plan(root,temporary.resolve("outside")));
        assertThrows(java.io.IOException.class,()->SaoWindowsPrivatePaths.plan(root,root.resolve("../outside")));
    }
    /// Production splits per-user account storage from the data root that holds the instance; tickets plan from the latter.
    @Test void ticketRootOwnsInstanceWithSplitRoots() throws Exception {
        Path user=temporary.resolve("AppData/.sao-oath-launcher"), data=temporary.resolve("Games/.sao-oath-launcher");
        Path instance=data.resolve("minecraft/versions/sao-oath");
        assertThrows(java.io.IOException.class,()->SaoWindowsPrivatePaths.plan(user,instance));
        java.net.URI none=java.net.URI.create("http://127.0.0.1:1/");
        try(var accounts=new SaoOathAccounts(user,data,()->none,()->"zh_cn",new SaoOathCredentialStore(user,false),
                ()->{throw new AssertionError("Microsoft must not open");},none,account->{throw new AssertionError("Game must not start");},message->{})){
            assertEquals(data,accounts.ticketRoot());
            assertEquals(instance,SaoWindowsPrivatePaths.plan(accounts.ticketRoot(),instance).get(3));
        }
    }
        /// D:P removes inheritance and the single full-control ACE belongs only to the current token SID.
    @Test void protectedDaclHasExactlyOneOwnerGrant() {
        String sid="S-1-5-21-11-22-33-1001";
        assertEquals("D:P(A;OICI;FA;;;"+sid+")",SaoWindowsPrivatePaths.descriptor(sid,true));
        assertEquals("D:P(A;;FA;;;"+sid+")",SaoWindowsPrivatePaths.descriptor(sid,false));
        assertEquals(0x80000004,SaoWindowsPrivatePaths.PROTECTED_DACL);
        assertFalse(SaoWindowsPrivatePaths.descriptor(sid,true).contains(";;;SY"));
        assertFalse(SaoWindowsPrivatePaths.descriptor(sid,true).contains(";;;BA"));
        assertThrows(IllegalArgumentException.class,()->SaoWindowsPrivatePaths.descriptor("injected)ACE",true));
    }
    /// Older dedicated-server floor text cannot leak back into launcher friend status.
    @Test void gamePresenceHasNoFloor(){
        assertEquals("誓约",SaoFriends.location(SaoData.Presence.GAME,"第 7 层"));
        assertEquals("誓约",SaoFriends.location(SaoData.Presence.GAME,"誓约"));
        assertEquals("单人游戏",SaoFriends.location(SaoData.Presence.GAME,"单人游戏"));
        assertEquals("",SaoFriends.location(SaoData.Presence.OFFLINE,"第 7 层"));
    }

}
