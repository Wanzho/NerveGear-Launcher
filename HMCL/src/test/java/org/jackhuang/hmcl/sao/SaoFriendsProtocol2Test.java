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

import com.google.gson.*;
import org.jetbrains.annotations.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises the production service engine with an in-memory transport: no server, socket, game or SMTP.
@NotNullByDefault
class SaoFriendsProtocol2Test {
    /// Isolated installation-owned nonsecret cursor/device storage.
    @TempDir Path root;
    /// Origin fixture without any actual network connection.
    private static final URI ORIGIN = URI.create("https://fixture.invalid/");
    /// Supplies deterministic state and records exact request values under the real worker serialization.
    @NotNullByDefault
    private static final class Transport implements SaoFriends.Transport {
        /// Requests are captured only in this synthetic unit fixture.
        final List<JsonObject> sends = new CopyOnWriteArrayList<>();
        /// Mutable current origin proves queued calls remain fenced.
        volatile URI origin = ORIGIN;
        /// Counts first ambiguous response and later retry.
        int attempts;
        /// Synthetic state with every newly decoded conversation field.
        final JsonObject state = JsonParser.parseString("{\"friends\":[{\"id\":\"peer\",\"name\":\"Peer\",\"presence\":\"GAME\",\"where\":\"誓约\",\"remark\":\"搭档\"}],\"requests\":[],\"chats\":[{\"id\":\"direct\",\"title\":\"Peer\",\"group\":false,\"members\":[\"Self\",\"Peer\"],\"unread\":1,\"lastText\":\"hello\",\"lastTime\":1,\"mentions\":0,\"muted\":true,\"pinned\":true,\"notify\":false,\"peerRead\":5,\"viewVersion\":0,\"typing\":[{\"id\":\"peer\",\"from\":\"Peer\",\"expiresAt\":5000}]}],\"cursor\":8}").getAsJsonObject();
        /// Returns only the test's captured configured origin.
        @Override public URI root() { return origin; }

        /// Mimics fixed safe responses while asserting the bearer stays pinned to the issuing origin.
        @Override public JsonObject jsonAt(URI origin,String method,String route,@Nullable JsonObject body,@Nullable String bearer)throws SaoAccountException {
            assertEquals(ORIGIN,origin);assertEquals("fixture-token",bearer);
            if (route.equals("sao/social/state")) return state.deepCopy();
            if (route.equals("sao/social/send") || route.equals("sao/social/playInvite")) { var request = Objects.requireNonNull(body);sends.add(request.deepCopy()); if (route.endsWith("/send") && attempts++ == 0)throw new SaoAccountException("网络不可用","network");var m = message(5,5,"Self",request.has("text") ? request.get("text").getAsString() : "Self 邀请你一起 Link Start",true);m.addProperty("nonce",request.get("nonce").getAsString()); if (request.has("replyTo")) { m.addProperty("replyTo",request.get("replyTo").getAsLong());m.add("quote",JsonParser.parseString("{\"sender\":\"peer\",\"from\":\"Peer\",\"text\":\"preview 😀\"}")); } if (route.endsWith("playInvite")) { m.addProperty("kind","INVITE");m.addProperty("target","mc.saooath.com");m.addProperty("expiresAt",Long.MAX_VALUE); } return m; }
            if (route.equals("sao/social/acceptInvite")) { assertEquals(5,Objects.requireNonNull(body).get("message").getAsLong()); return JsonParser.parseString("{\"target\":\"mc.saooath.com\"}").getAsJsonObject(); }
            if (route.equals("sao/social/typing")) { sends.add(Objects.requireNonNull(body).deepCopy()); return new JsonObject(); }
            if (route.equals("sao/social/search")) { var found = new JsonObject();var list = new JsonArray();list.add(message(5,5,"Peer","found 😀",false));found.add("messages",list); return found; }
            if (route.startsWith("sao/social/history")) { var result = new JsonObject();result.add("messages",new JsonArray());result.addProperty("nextBefore",0); return result; }
            return new JsonObject();
        }
    }

    /// Builds a synthetic durable record; values mimic protocol-2 wire responses.
    private static JsonObject message(long id,long revision,String from,String text,boolean mine) { var m = SaoFriends.object("chat","direct","from",from,"sender",mine ? "self" : "peer","text",text,"nonce","fixture_nonce_" + id,"kind","TEXT");m.addProperty("id",id);m.addProperty("revision",revision);m.addProperty("time",1);m.addProperty("mine",mine); return m; }

    /// Installs authorization without starting the real SSE reconnect loop.
    private SaoFriends engine(Transport transport)throws Exception { var engine = new SaoFriends(root,transport,e -> fail("Unexpected social error"));var field = SaoFriends.class.getDeclaredField("session");field.setAccessible(true);field.set(engine,new SaoFriends.Session(ORIGIN,"fixture-token","self","Self")); return engine; }

    /// Invokes the actual worker-owned message replacement path directly in a deterministic unit fixture.
    private static void remember(SaoFriends engine,JsonObject value)throws Exception { var method = SaoFriends.class.getDeclaredMethod("remember",JsonObject.class);method.setAccessible(true);method.invoke(engine,value); }

    /// Invokes actual snapshot publication, preserving its session fence.
    private static void apply(SaoFriends engine,JsonObject state)throws Exception { var method = SaoFriends.class.getDeclaredMethod("apply",SaoFriends.Session.class,JsonObject.class);method.setAccessible(true);var field = SaoFriends.class.getDeclaredField("session");field.setAccessible(true);method.invoke(engine,field.get(engine),state); }

    /// Reply retry uses one nonce and ID; snapshots retain rich fields and immutable list identity.
    @Test void replyRetryAndDecodedSnapshots()throws Exception { var transport = new Transport(); try (var engine = engine(transport)) { engine.send("direct","👩🏽‍💻❤️🇨🇭",1,false).get(5,TimeUnit.SECONDS);assertEquals(2,transport.sends.size());assertEquals(transport.sends.get(0),transport.sends.get(1));assertEquals(1,transport.sends.get(0).get("replyTo").getAsLong());var view = engine.messages("direct");assertSame(view,engine.messages("direct"));assertEquals(1,view.size());assertEquals(5,view.get(0).id());assertEquals("preview 😀",view.get(0).quote().text());assertEquals("搭档",engine.friends().get(0).remark());var chat = engine.chats().get(0);assertTrue(chat.muted() && chat.pinned());assertFalse(chat.shouldNotify());assertEquals(5,chat.peerRead());assertEquals(5000,chat.typing().get(0).expiresAt());assertTrue(view.get(0).readByPeer(chat)); } }

    /// Acceptance ignores a forged display host, uses the stable ID, and validates the authority's response again.
    @Test void inviteAndTypingAndSearch()throws Exception { var transport = new Transport(); try (var engine = engine(transport)) { engine.send("direct","",0,true).get(5,TimeUnit.SECONDS);var invite = engine.messages("direct").get(0);assertEquals("mc.saooath.com",engine.acceptInvite(invite,List.of("mc.saooath.com")).get(5,TimeUnit.SECONDS));assertTrue(engine.acceptInvite(invite,List.of("zz.saooath.com")).handle((v,e) -> e != null).get(5,TimeUnit.SECONDS));transport.sends.clear();engine.typing("direct").get(5,TimeUnit.SECONDS);engine.typing("direct").get(5,TimeUnit.SECONDS);assertEquals(1,transport.sends.size());var found = engine.search("FOUND","",10).get(5,TimeUnit.SECONDS);assertEquals("found 😀",found.get(0).text());assertThrows(UnsupportedOperationException.class,found::clear); } }

    /// New revisions replace IDs, erase cached quotes, and reject stale pages arriving after the tombstone.
    @Test void recallReplacesAndScrubsQuotes()throws Exception { var transport = new Transport(); try (var engine = engine(transport)) { engine.messages("direct");engine.operation("fixture",new JsonObject()).get(5,TimeUnit.SECONDS);var source = message(1,1,"Peer","erased original",false);var reply = message(2,2,"Self","reply",true);reply.addProperty("replyTo",1);reply.add("quote",JsonParser.parseString("{\"sender\":\"peer\",\"from\":\"Peer\",\"text\":\"erased original\"}"));remember(engine,source);remember(engine,reply);var recall = message(1,9,"Peer","Peer 撤回了一条消息",false);recall.addProperty("kind","RECALLED");remember(engine,recall);remember(engine,source);apply(engine,transport.state);var view = engine.messages("direct");assertEquals(2,view.size());assertEquals(SaoData.MessageKind.RECALLED,view.get(0).kind());assertEquals(9,view.get(0).revision());assertEquals("Peer 撤回了一条消息",view.get(1).quote().text());remember(engine,reply);apply(engine,transport.state);assertEquals("Peer 撤回了一条消息",view.get(1).quote().text()); } }

    /// Changed visibility drops cached group/direct content, including quoted text, before publishing the next view.
    @Test void visibilityInvalidatesAndOriginFences()throws Exception { var transport = new Transport(); try (var engine = engine(transport)) { engine.messages("direct");engine.operation("fixture",new JsonObject()).get(5,TimeUnit.SECONDS);remember(engine,message(1,1,"Peer","cached",false));apply(engine,transport.state);var view = engine.messages("direct");assertEquals(1,view.size());var changed = transport.state.deepCopy();changed.getAsJsonArray("chats").get(0).getAsJsonObject().addProperty("viewVersion",10);apply(engine,changed);assertTrue(view.isEmpty());transport.origin = URI.create("https://other.invalid/");assertTrue(engine.send("direct","fenced",0,false).handle((v,e) -> e != null).get(5,TimeUnit.SECONDS));assertTrue(transport.sends.isEmpty()); } }
}
