/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Pure protocol decoding and offline social seam tests; no toolkit, account server, game or network required.
@NotNullByDefault
class SaoSocial2Test {
    /// Lightweight base fake used only to supply non-social defaults to the decorator.
    private final SaoLauncherService base=(SaoLauncherService)java.lang.reflect.Proxy.newProxyInstance(SaoLauncherService.class.getClassLoader(),new Class<?>[]{SaoLauncherService.class},(proxy,method,args)->{if(method.isDefault())return java.lang.reflect.InvocationHandler.invokeDefault(proxy,method,args);throw new UnsupportedOperationException();});
    /// New sample values cover every requested UI feature and use immutable snapshots.
    @Test void samplesCoverFeaturesAndSourceCompatibility()throws Exception {
        var service=SaoSocialSamples.decorate(base);assertEquals("攻略搭档",service.friends().get(0).remark());assertTrue(service.chats().get(0).pinned());assertTrue(service.chats().get(1).muted());assertFalse(service.chats().get(1).shouldNotify());assertEquals(1,service.chats().get(1).mentions());assertTrue(service.chats().get(0).typing().get(0).active(System.currentTimeMillis()));
        var messages=service.messages("demo-direct");assertTrue(messages.get(1).readByPeer(service.chats().get(0)));assertNotNull(messages.get(1).quote());assertEquals(SaoData.MessageKind.INVITE,messages.get(2).kind());assertEquals("mc.saooath.com",service.acceptInvite(messages.get(2)).get());assertEquals(SaoData.MessageKind.RECALLED,messages.get(3).kind());assertTrue(messages.get(0).text().contains("👩🏽‍💻❤️"));assertThrows(UnsupportedOperationException.class,()->messages.clear());assertThrows(UnsupportedOperationException.class,()->service.chats().get(0).typing().clear());
        assertEquals("",new SaoData.Friend("Fixture",SaoData.Presence.OFFLINE,"",null).remark());assertEquals(SaoData.MessageKind.TEXT,new SaoData.ChatMessage("Fixture","😀",0,false,false).kind());
    }
    /// Exact allowlisting rejects host lookalikes, arbitrary ports, URLs, whitespace and forged cards.
    @Test void inviteTargetsAreExact()throws Exception {
        var known=List.of("mc.saooath.com","zz.saooath.com");assertEquals("zz.saooath.com",SaoSocialProtocol.target("zz.saooath.com",known));for(String target:List.of("evil.example","mc.saooath.com.evil.test","https://mc.saooath.com","mc.saooath.com:25565","mc.saooath.com ","user@mc.saooath.com"))assertThrows(SaoAccountException.class,()->SaoSocialProtocol.target(target,known));
        var service=SaoSocialSamples.decorate(base);var forged=new SaoData.ChatMessage("Fixture","card",0,false,false,999,"demo-direct","","",999,SaoData.MessageKind.INVITE,null,new SaoData.PlayInvite("evil.example",Long.MAX_VALUE),List.of(),true);assertTrue(service.acceptInvite(forged).isCompletedExceptionally());
    }
    /// Real protocol-1 JSON remains readable; protocol-2 preserves Unicode and stable message identity.
    @Test void decodingAndUnicode(){
        var m=JsonParser.parseString("{\"id\":5,\"chat\":\"fixture\",\"from\":\"Peer\",\"sender\":\"uuid\",\"nonce\":\"fixture_nonce\",\"time\":1,\"mine\":false,\"text\":\"👩🏽‍💻❤️🇨🇭\"}").getAsJsonObject();var old=SaoSocialProtocol.message(m);assertEquals(5,old.revision());assertEquals("👩🏽‍💻❤️🇨🇭",old.text());assertNull(old.quote());m.addProperty("kind","RECALLED");m.addProperty("revision",9);m.addProperty("text","Peer 撤回了一条消息");assertEquals(9,SaoSocialProtocol.message(m).revision());assertEquals(SaoData.MessageKind.RECALLED,SaoSocialProtocol.message(m).kind());
        var q=new JsonObject();q.addProperty("sender","uuid");q.addProperty("from","Peer");q.addProperty("text","😀");m.add("quote",q);m.addProperty("replyTo",4);assertEquals("😀",SaoSocialProtocol.message(m).quote().text());
    }
    /// Demo operations exercise the same API signatures the UI will call, including successful and refused recalls.
    @Test void offlineServiceActions()throws Exception {
        var service=SaoSocialSamples.decorate(base);var messages=service.messages("demo-direct");service.sendMessage("demo-direct","👩🏽‍💻❤️🇨🇭",1).get();var own=messages.get(messages.size()-1);assertNotNull(own.quote());service.sendMessage("demo-direct","reply",own.id()).get();service.recallMessage(own).get();assertEquals(SaoData.MessageKind.RECALLED,messages.get(messages.size()-2).kind());assertTrue(messages.get(messages.size()-1).quote().text().contains("撤回"));assertTrue(service.recallMessage(messages.get(0)).isCompletedExceptionally());
        service.setChatPreferences("demo-group",false,true).get();assertTrue(service.chats().stream().filter(c->c.id().equals("demo-group")).findFirst().orElseThrow().pinned());service.setFriendRemark("Asuna","队友").get();assertEquals("队友",service.friends().get(0).displayName());assertTrue(service.setFriendRemark("Asuna","x".repeat(13)).isCompletedExceptionally());assertFalse(service.searchMessages("REPLY","",100).get().isEmpty());assertTrue(service.searchMessages("x","",101).isCompletedExceptionally());service.sendPlayInvite("demo-direct").get();assertEquals("mc.saooath.com",service.acceptInvite(messages.get(messages.size()-1)).get());service.markRead("demo-group");assertEquals(0,service.chats().stream().filter(c->c.id().equals("demo-group")).findFirst().orElseThrow().mentions());
    }
    /// Read receipts are direct-only, confirmed-own-only and never infer reading from delivery alone.
    @Test void receiptsAndTypingExpiry(){var message=new SaoData.ChatMessage("Self","😀",1,true,false,8,"d","self","nonce",8,SaoData.MessageKind.TEXT,null,null,List.of(),false);var direct=new SaoData.Chat("d","Peer",false,List.of("Self","Peer"),0,"",1,0,false,false,false,8,List.of());assertTrue(message.readByPeer(direct));assertFalse(message.readByPeer(new SaoData.Chat("g","Group",true,List.of("Self","Peer"),0,"",1,0,false,false,false,8,List.of())));assertTrue(new SaoData.Typing("uuid","Peer",5000).active(4999));assertFalse(new SaoData.Typing("uuid","Peer",5000).active(5000));}
}
