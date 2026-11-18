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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/// Two independent real launcher engines use the released account router over loopback with no JavaFX toolkit.
@NotNullByDefault
@EnabledIfSystemProperty(named = "sao.account.proof.serverJar",matches = ".+")
class SaoFriendsTest {
    /// Fresh installation roots and synthetic account-server world, never a real game directory.
    @TempDir Path temporary;
    /// Constructs a headless engine pinned to the fixture API, with explicit nonproduction credential storage.
    private SaoPackEngine engine(String label,SaoLocalPremiumFixture server)throws Exception {
        Path root = temporary.resolve(label); return new SaoPackEngine(root,server::root,new SaoOathCredentialStore(root,false),() -> { throw new IllegalStateException("No Microsoft flow in social test"); },server.mojangJoin());
    }

    /// Waits for a fixed state predicate without logging chat bodies or credentials.
    private static void until(BooleanSupplier condition)throws Exception { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20); while (!condition.getAsBoolean()) { if (System.nanoTime() > end)fail("Timed out awaiting social state");Thread.sleep(40); } }

    /// Waits for a seam future and preserves fixed public errors on failure.
    private static <T> T await(CompletableFuture<T> value)throws Exception { return value.get(20,TimeUnit.SECONDS); }

    /// Verifies a fixed Chinese mapping rather than arbitrary server response text.
    private static void failure(CompletableFuture<?> future,String message)throws Exception { try { await(future);fail("Expected social failure"); } catch (ExecutionException expected) { Throwable e = expected.getCause(); while (e instanceof CompletionException && e.getCause() != null)e = e.getCause();assertInstanceOf(SaoAccountException.class,e);assertEquals(message,e.getMessage()); } }

    /// Covers fallback delivery, pending reconciliation, groups, unread/read and blocking between real engines.
    @Test void twoEnginesFriendsGroupsAndFallback()throws Exception {
        Path jar = Path.of(System.getProperty("sao.account.proof.serverJar"));
        try (var server = new SaoLocalPremiumFixture(temporary.resolve("server"),jar);var a = engine("one",server);var b = engine("two",server)) {
            char[] pass = UUID.randomUUID().toString().toCharArray();char[] second = UUID.randomUUID().toString().toCharArray();
            await(a.oathRegister("SocialOne","one@example.test",pass));await(b.oathRegister("SocialTwo","two@example.test",second));
            await(a.addFriend("SocialTwo"));until(() -> b.friendRequests().stream().anyMatch(SaoData.FriendRequest::incoming));
            await(b.answerFriend("SocialOne",false));until(() -> a.friendRequests().isEmpty());
            await(b.addFriend("SocialOne"));until(() -> a.friendRequests().stream().anyMatch(SaoData.FriendRequest::incoming));await(a.answerFriend("SocialTwo",true));until(() -> b.friends().size() == 1);
            String direct = await(a.directChat("SocialTwo"));until(() -> b.chats().stream().anyMatch(c -> c.id().equals(direct)));
            var av = a.messages(direct);var bv = b.messages(direct);assertSame(a.friends(),a.friends());assertSame(a.chats(),a.chats());assertSame(a.friendRequests(),a.friendRequests());assertSame(av,a.messages(direct));String body = UUID.randomUUID().toString();
            CompletableFuture<Void> send = a.sendMessage(direct,body);assertTrue(av.stream().anyMatch(SaoData.ChatMessage::pending));await(send);until(() -> bv.size() == 1);assertFalse(av.get(0).pending());assertEquals(body,bv.get(0).text());
            until(() -> b.chats().stream().anyMatch(c -> c.id().equals(direct) && c.unread() == 1));b.markRead(direct);until(() -> b.chats().stream().anyMatch(c -> c.id().equals(direct) && c.unread() == 0));
            Thread.sleep(600);await(b.sendMessage(direct,UUID.randomUUID().toString()));until(() -> av.size() == 2);
            String group = await(a.createGroup("测试小队",List.of("SocialTwo")));until(() -> b.chats().stream().anyMatch(c -> c.id().equals(group)));var bg = b.messages(group);Thread.sleep(600);await(a.sendMessage(group,UUID.randomUUID().toString()));until(() -> bg.size() == 1);
            await(b.leaveGroup(group));until(() -> b.chats().stream().noneMatch(c -> c.id().equals(group)));await(a.inviteToGroup(group,List.of("SocialTwo")));until(() -> b.chats().stream().anyMatch(c -> c.id().equals(group)));assertTrue(b.messages(group).isEmpty());
            failure(a.sendMessage(direct,"x".repeat(1025)),"消息限 1—1024 字，请勿换行");failure(a.createGroup("invalid title",List.of("SocialTwo")),"群名限 1—12 字，请勿使用空格或括号");
            await(b.blockPlayer("SocialOne"));until(() -> a.friends().isEmpty());failure(a.addFriend("SocialTwo"),"无法向对方发送消息");assertTrue(a.messages(direct).isEmpty());
            a.oathLogout();until(() -> a.chats().isEmpty());failure(a.addFriend("SocialTwo"),"请先登录 ARGUS ID");server.assertHealthy();
        }
    }

    /// Gets the package social controller for a deliberate socket-drop and process-presence proof.
    private static SaoFriends social(SaoPackEngine engine)throws Exception { var field = SaoPackEngine.class.getDeclaredField("oath");field.setAccessible(true); return ((SaoOathAccounts)field.get(engine)).friends; }

    /// Exercises the real same-port Netty SSE transport between two engines on the isolated dedicated server.
    @Test @EnabledIfSystemProperty(named = "sao.social.live",matches = "http://127\\.0\\.0\\.1:[0-9]+/")
    void twoEnginesSseResumeAndProcessPresence()throws Exception {
        var api = java.net.URI.create(System.getProperty("sao.social.live"));String suffix = Long.toUnsignedString(System.nanoTime(),36);suffix = suffix.substring(Math.max(0,suffix.length() - 6));String one = "EngineA" + suffix,two = "EngineB" + suffix;
        Path ar = temporary.resolve("live-one"),br = temporary.resolve("live-two");
        try (var a = new SaoPackEngine(ar,() -> api,new SaoOathCredentialStore(ar,false),() -> { throw new IllegalStateException(); },api.resolve("session/minecraft/join"));
            var b = new SaoPackEngine(br,() -> api,new SaoOathCredentialStore(br,false),() -> { throw new IllegalStateException(); },api.resolve("session/minecraft/join"))) {
            await(a.oathRegister(one,"engine-a@example.test",UUID.randomUUID().toString().toCharArray()));await(b.oathRegister(two,"engine-b@example.test",UUID.randomUUID().toString().toCharArray()));
            await(a.addFriend(two));until(() -> b.friendRequests().size() == 1);await(b.answerFriend(one,true));until(() -> a.friends().size() == 1);
            SaoFriends sa = social(a),sb = social(b);until(() -> sa.liveConnections.get() > 0 && sb.liveConnections.get() > 0);until(() -> a.friends().get(0).presence() == SaoData.Presence.LAUNCHER);
            String direct = await(a.directChat(two));var received = b.messages(direct);var sent = a.messages(direct);String first = UUID.randomUUID().toString();await(a.sendMessage(direct,first));until(() -> received.size() == 1);assertFalse(sent.get(0).pending());
            long connections = sb.liveConnections.get();var method = SaoFriends.class.getDeclaredMethod("closeInput");method.setAccessible(true);method.invoke(sb);Thread.sleep(600);String next = UUID.randomUUID().toString();await(a.sendMessage(direct,next));until(() -> received.size() == 2 && sb.liveConnections.get() > connections);Thread.sleep(1500);assertEquals(1,received.stream().filter(m -> m.text().equals(next)).count());
            String group = await(a.createGroup("队" + suffix,List.of(two)));var grouped = b.messages(group);Thread.sleep(600);await(a.sendMessage(group,UUID.randomUUID().toString()));until(() -> grouped.size() == 1);b.markRead(group);until(() -> b.chats().stream().anyMatch(c -> c.id().equals(group) && c.unread() == 0));
            sa.playing(true);
            until(() -> b.friends().get(0).presence() == SaoData.Presence.GAME);
            assertEquals("单人游戏",b.friends().get(0).where());
            System.out.println("PASS live engine process GAME presence");
            sa.playing(false);
            until(() -> b.friends().get(0).presence() == SaoData.Presence.LAUNCHER);
            System.out.println("PASS live engine process exit LAUNCHER presence");
            a.oathLogout();until(() -> a.chats().isEmpty());
        }
    }

}
