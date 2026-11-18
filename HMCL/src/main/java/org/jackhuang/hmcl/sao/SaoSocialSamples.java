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

import javafx.collections.*;
import org.jetbrains.annotations.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/// Offline protocol-2 fixture decorator for the existing UI-owned SaoFakeService, without editing UI sources.
/// The UI constructs `SaoSocialSamples.decorate(new SaoFakeService(...))`; other launcher features delegate unchanged.
@NotNullByDefault
public final class SaoSocialSamples implements InvocationHandler {
    /// Wrapped fake launcher implementation; this decorator is never installed into the production service registry.
    private final SaoLauncherService base;
    /// Read/write fixture friends, published through a stable unmodifiable wrapper.
    private final ObservableList<SaoData.Friend> friends = FXCollections.observableArrayList();
    /// Fixture conversations include one pinned direct chat and one muted group.
    private final ObservableList<SaoData.Chat> chats = FXCollections.observableArrayList();
    /// Stable public wrappers keep the fake's listeners attached for its lifetime.
    private final @UnmodifiableView ObservableList<SaoData.Friend> friendsView = FXCollections.unmodifiableObservableList(friends);
    /// Stable conversation wrapper mirrors the production seam.
    private final @UnmodifiableView ObservableList<SaoData.Chat> chatsView = FXCollections.unmodifiableObservableList(chats);
    /// Retained message wrappers mirror the production stable-list contract.
    private final Map<String,@UnmodifiableView ObservableList<SaoData.ChatMessage>> views = new HashMap<>();
    /// Stable per-conversation message lists for screen listeners.
    private final Map<String,ObservableList<SaoData.ChatMessage>> messages = new HashMap<>();
    /// Monotonic local IDs used only for this synthetic demo.
    private long sequence = 10;
    /// Current synthetic self UUID, never a real account identity.
    private static final String SELF = "00000000-0000-0000-0000-000000000001";
    /// Current synthetic peer UUID used by mentions and receipts.
    private static final String PEER = "00000000-0000-0000-0000-000000000002";
    /// Initializes every requested feature with visible, nonpersonal fixture text.
    private SaoSocialSamples(SaoLauncherService base) {
        this.base = base;long now = System.currentTimeMillis();
        friends.addAll(new SaoData.Friend("Asuna",SaoData.Presence.GAME,"誓约",null,PEER,"攻略搭档"),new SaoData.Friend("Klein",SaoData.Presence.LAUNCHER,"",null,"00000000-0000-0000-0000-000000000003","老朋友"));
        var direct = FXCollections.<SaoData.ChatMessage>observableArrayList();
        direct.add(message(1,"demo-direct","Asuna","一起去下一层？ 👩🏽‍💻❤️",now - 90000,false,SaoData.MessageKind.TEXT,null,null,List.of()));
        direct.add(message(2,"demo-direct","Kirito","补给好了，马上出发 😀",now - 80000,true,SaoData.MessageKind.TEXT,new SaoData.Quote(1,PEER,"Asuna","一起去下一层？ 👩🏽‍💻❤️"),null,List.of()));
        direct.add(message(3,"demo-direct","Asuna","Asuna 邀请你一起 Link Start",now - 60000,false,SaoData.MessageKind.INVITE,null,new SaoData.PlayInvite("mc.saooath.com",now + 540000),List.of()));
        direct.add(message(4,"demo-direct","Asuna","Asuna 撤回了一条消息",now - 30000,false,SaoData.MessageKind.RECALLED,null,null,List.of()));messages.put("demo-direct",direct);
        messages.put("demo-group",FXCollections.observableArrayList(message(5,"demo-group","Asuna","@Kirito 集合，准备 Link Start！ 🎮",now - 10000,false,SaoData.MessageKind.TEXT,null,null,List.of(SELF))));
        chats.addAll(new SaoData.Chat("demo-direct","Asuna",false,List.of("Kirito","Asuna"),2,"Asuna 撤回了一条消息",now - 30000,0,false,true,true,2,List.of(new SaoData.Typing(PEER,"Asuna",now + 5000))),new SaoData.Chat("demo-group","攻略小队",true,List.of("Kirito","Asuna","Klein"),1,"@Kirito 集合，准备 Link Start！ 🎮",now - 10000,1,true,false,false,0,List.of()));
    }

    /// Decorates an existing UI fake with protocol-2 samples and reversible in-memory social actions.
    public static SaoLauncherService decorate(SaoLauncherService fake) { return (SaoLauncherService)Proxy.newProxyInstance(SaoLauncherService.class.getClassLoader(),new Class<?>[]{SaoLauncherService.class},new SaoSocialSamples(fake)); }

    /// Creates one immutable synthetic message with the same fields as production.
    private static SaoData.ChatMessage message(long id,String chat,String from,String text,long time,boolean mine,SaoData.MessageKind kind,@Nullable SaoData.Quote quote,@Nullable SaoData.PlayInvite invite,List<String> mentions) { return new SaoData.ChatMessage(from,text,time,mine,false,id,chat,mine ? SELF : PEER,"demo_nonce_" + id,id,kind,quote,invite,mentions,!mine && kind != SaoData.MessageKind.RECALLED && !chat.equals("demo-group")); }

    /// Produces a first-60-scalar quote preview without splitting supplementary emoji.
    private static String preview(String text) { return text.substring(0,text.offsetByCodePoints(0,Math.min(60,text.codePointCount(0,text.length())))); }

    /// Finds a fixture conversation; arbitrary IDs cannot introduce a remote target.
    private SaoData.Chat chat(String id) { return chats.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown demo chat")); }

    /// Replaces private conversation flags while preserving every other fixture field.
    private void flags(String id,boolean muted,boolean pinned,boolean read) { var c = chat(id);chats.set(chats.indexOf(c),new SaoData.Chat(c.id(),c.title(),c.group(),c.members(),read ? 0 : c.unread(),c.lastText(),c.lastTime(),read ? 0 : c.mentions(),muted,pinned,!muted && !read && c.unread() > 0,c.peerRead(),c.typing()));chats.sort(Comparator.comparing((SaoData.Chat c2) -> !c2.pinned()).thenComparing(Comparator.comparingLong(SaoData.Chat::lastTime).reversed())); }

    /// Handles the social seam only; all other methods call the wrapped UI fake implementation.
    @Override public @Nullable Object invoke(Object proxy,Method method,Object @Nullable [] args)throws Throwable {
        Object[] a = args == null ? new Object[0] : args;
        try {
            return switch(method.getName()) {
                case "friends" -> friendsView;
                case "chats" -> chatsView;
                case "messages" -> views.computeIfAbsent((String)a[0],k -> FXCollections.unmodifiableObservableList(messages.computeIfAbsent(k,id -> FXCollections.observableArrayList())));
                case "friendRequests" -> FXCollections.emptyObservableList();
                case "loadOlderMessages" -> null;
                case "markRead" -> { var c = chat((String)a[0]);flags(c.id(),c.muted(),c.pinned(),true);yield null; }
                case "setChatPreferences" -> { flags((String)a[0],(boolean)a[1],(boolean)a[2],false);yield CompletableFuture.completedFuture(null); }
                case "setFriendRemark" -> { String remark = (String)a[1]; if (remark.codePointCount(0,remark.length()) > 12 || remark.codePoints().anyMatch(Character::isISOControl))throw new SaoAccountException("备注最多十二个字，请勿换行","remarkLength"); for (int i = 0;i < friends.size();i++) { var f = friends.get(i); if (f.name().equals(a[0]))friends.set(i,new SaoData.Friend(f.name(),f.presence(),f.where(),f.head(),f.id(),remark)); } yield CompletableFuture.completedFuture(null); }
                case "typing" -> CompletableFuture.completedFuture(null);
                case "searchMessages" -> { String query = ((String)a[0]).toLowerCase(Locale.ROOT),id = (String)a[1];int limit = (int)a[2]; if (query.isBlank() || query.length() > 100 || limit < 1 || limit > 100)throw new SaoAccountException("搜索限 1—100 字，请勿换行","searchLength");var found = messages.values().stream().flatMap(Collection::stream).filter(m -> m.kind() == SaoData.MessageKind.TEXT && (id.isEmpty() || m.chat().equals(id)) && m.text().toLowerCase(Locale.ROOT).contains(query)).sorted(Comparator.comparingLong(SaoData.ChatMessage::id).reversed()).limit(limit).toList();yield CompletableFuture.completedFuture(found); }
                case "acceptInvite" -> { var m = (SaoData.ChatMessage)a[0];var stored = messages.getOrDefault(m.chat(),FXCollections.emptyObservableList()).stream().filter(v -> v.id() == m.id()).findFirst(); if (stored.isEmpty() || stored.get().invite() == null || stored.get().kind() != SaoData.MessageKind.INVITE)throw new SaoAccountException("邀请的服务器不可用","inviteTarget");var card = Objects.requireNonNull(stored.get().invite()); if (System.currentTimeMillis() >= card.expiresAt())throw new SaoAccountException("邀请已过期，请重新邀请","inviteExpired");yield CompletableFuture.completedFuture(SaoSocialProtocol.target(card.target(),base.knownServers())); }
                case "sendMessage","sendPlayInvite" -> { String id = (String)a[0];chat(id);boolean invite = method.getName().equals("sendPlayInvite");String text = invite ? "Kirito 邀请你一起 Link Start" : (String)a[1]; if (!invite && (text.isBlank() || text.length() > 1024 || text.codePoints().anyMatch(x -> Character.isISOControl(x) || x == 0xa7 || x >= 0xd800 && x <= 0xdfff)))throw new SaoAccountException("消息限 1—1024 字，请勿换行","messageLength");@Nullable SaoData.Quote quote = null; if (a.length == 3 && (long)a[2] > 0) { long source = (long)a[2];var q = messages.get(id).stream().filter(m -> m.id() == source).findFirst().orElseThrow();quote = new SaoData.Quote(q.id(),q.sender(),q.from(),preview(q.text())); } long now = System.currentTimeMillis();messages.get(id).add(message(++sequence,id,"Kirito",text,now,true,invite ? SaoData.MessageKind.INVITE : SaoData.MessageKind.TEXT,quote,invite ? new SaoData.PlayInvite("mc.saooath.com",now + 600000) : null,List.of()));yield CompletableFuture.completedFuture(null); }
                case "recallMessage" -> { var requested = (SaoData.ChatMessage)a[0];var list = messages.get(requested.chat());var m = list.stream().filter(v -> v.id() == requested.id()).findFirst().orElseThrow(); if (!m.mine())throw new SaoAccountException("只能撤回自己的消息","notSender"); if (System.currentTimeMillis() - m.time() > 120000)throw new SaoAccountException("只能撤回两分钟内的消息","recallExpired");String text = m.from() + " 撤回了一条消息";list.set(list.indexOf(m),message(m.id(),m.chat(),m.from(),text,m.time(),true,SaoData.MessageKind.RECALLED,null,null,List.of())); for (int i = 0;i < list.size();i++) { var r = list.get(i); if (r.quote() != null && r.quote().replyTo() == m.id())list.set(i,message(r.id(),r.chat(),r.from(),r.text(),r.time(),r.mine(),r.kind(),new SaoData.Quote(m.id(),m.sender(),m.from(),text),r.invite(),r.mentions())); } yield CompletableFuture.completedFuture(null); }
                default -> method.invoke(base,args);
            }; } catch (SaoAccountException failure) { return CompletableFuture.failedFuture(failure); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
