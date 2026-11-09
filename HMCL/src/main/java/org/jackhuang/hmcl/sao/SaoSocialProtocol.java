/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.*;
import java.util.*;

/// Protocol-2 value decoder and exact server allowlist checks shared by production and offline UI fixtures.
@NotNullByDefault
public final class SaoSocialProtocol {
    /// Utility class only.
    private SaoSocialProtocol() { }
    /// Decodes additive fields while preserving compatibility with protocol-1 snapshots.
    public static SaoData.ChatMessage message(JsonObject m){
        @Nullable SaoData.Quote quote=null;@Nullable SaoData.PlayInvite invite=null;
        if(m.has("quote")){var q=m.getAsJsonObject("quote");quote=new SaoData.Quote(number(m,"replyTo"),string(q,"sender"),string(q,"from"),string(q,"text"));}
        SaoData.MessageKind kind=SaoData.MessageKind.valueOf(m.has("kind")?string(m,"kind"):"TEXT");
        if(kind==SaoData.MessageKind.INVITE)invite=new SaoData.PlayInvite(string(m,"target"),number(m,"expiresAt"));
        return new SaoData.ChatMessage(string(m,"from"),string(m,"text"),number(m,"time"),flag(m,"mine"),false,number(m,"id"),string(m,"chat"),string(m,"sender"),string(m,"nonce"),m.has("revision")?number(m,"revision"):number(m,"id"),kind,quote,invite,strings(m,"mentions"),flag(m,"notify"));
    }
    /// Exact public destinations, without URL parsing, suffix matching, userinfo or arbitrary ports.
    public static String target(String value,Collection<String> known)throws SaoAccountException {if(!known.contains(value)||!value.matches("[a-z0-9.-]+(?::[0-9]{1,5})?"))throw new SaoAccountException("邀请的服务器不可用","inviteTarget");return value;}
    /// Decodes optional strings from additive payloads.
    static String string(JsonObject m,String key){return m.has(key)?m.get(key).getAsString():"";}
    /// Decodes optional exact integer fields.
    static long number(JsonObject m,String key){return m.has(key)?m.get(key).getAsLong():0;}
    /// Decodes optional false-by-default flags.
    static boolean flag(JsonObject m,String key){return m.has(key)&&m.get(key).getAsBoolean();}
    /// Copies a UUID/name array without sharing Gson's mutable tree.
    static @Unmodifiable List<String> strings(JsonObject m,String key){var out=new ArrayList<String>();if(m.has(key))m.getAsJsonArray(key).forEach(v->out.add(v.getAsString()));return List.copyOf(out);}
}
